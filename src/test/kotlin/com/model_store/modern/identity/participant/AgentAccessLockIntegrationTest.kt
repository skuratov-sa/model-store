package com.model_store.modern.identity.participant

import com.amazonaws.services.s3.AmazonS3
import com.model_store.modern.identity.participant.application.*
import com.model_store.modern.identity.participant.domain.ParticipantStatus
import com.model_store.modern.identity.participant.infrastructure.ParticipantEntity
import com.model_store.modern.identity.verification.application.VerificationMail
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.IllegalTransactionStateException
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import java.util.concurrent.*

@SpringBootTest(properties = ["app.public-key-path=keys/test_public_key.pem", "app.private-key-path=keys/test_private_key.pem"])
@ActiveProfiles("modern")
class AgentAccessLockIntegrationTest {
    @field:MockitoBean lateinit var s3: AmazonS3
    @field:MockitoBean lateinit var jwtDecoder: JwtDecoder
    @field:MockitoBean lateinit var mail: VerificationMail
    @Autowired lateinit var access: AgentAccessLock
    @Autowired lateinit var profiles: AgentProfileCommands
    @Autowired lateinit var store: ParticipantStore
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var txManager: PlatformTransactionManager
    @Autowired lateinit var entityManager: EntityManager

    private val tx get() = TransactionTemplate(txManager)

    private fun withPool(pool: ExecutorService, action: (ExecutorService) -> Unit) {
        try {
            action(pool)
        } finally {
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "Concurrent test workers did not stop")
        }
    }

    private fun participant(role: String = "USER", status: String = "ACTIVE", agent: Boolean = false): Long {
        val login = "agent-access-${UUID.randomUUID()}"
        return jdbc.queryForObject(
            """INSERT INTO participant(login, mail, password, role, status, is_agent, full_name)
               VALUES (?, ?, 'secret', ?::participant_role, ?::participant_status, ?, 'Original') RETURNING id""",
            Long::class.java, login, "$login@example.test", role, status, agent,
        )!!
    }

    private fun expectDenied(caller: Long, target: Long, failure: Class<out Throwable>) {
        assertThrows(failure) { tx.executeWithoutResult { access.lockActiveAdminAndAgent(caller, target) } }
    }

    @Test
    fun `requires the caller's real JPA transaction`() {
        val admin = participant("ADMIN")
        val agent = participant(agent = true)
        assertThrows(IllegalTransactionStateException::class.java) {
            access.lockActiveAdminAndAgent(admin, agent)
        }
        val foreign = TransactionTemplate(DataSourceTransactionManager(jdbc.dataSource!!))
        assertThrows(IllegalTransactionStateException::class.java) {
            foreign.executeWithoutResult { access.lockActiveAdminAndAgent(admin, agent) }
        }
        tx.executeWithoutResult { access.lockActiveAdminAndAgent(admin, agent) }
        val readOnly = TransactionTemplate(txManager).apply { isReadOnly = true }
        assertThrows(AgentAccessTransactionRequired::class.java) {
            readOnly.executeWithoutResult { access.lockActiveAdminAndAgent(admin, agent) }
        }
    }

    @Test
    fun `checks current caller rights and agent identity at every target status`() {
        val target = participant(agent = true)
        val rejected = listOf(
            participant(), participant("ADMIN", "BLOCKED"), participant("ADMIN", "DELETED"),
            participant("ADMIN", "WAITING_VERIFY"), participant("ADMIN", agent = true), Long.MAX_VALUE,
        )
        rejected.forEach { expectDenied(it, target, AgentProfileFailure.AdminForbidden::class.java) }
        val admin = participant("ADMIN")
        listOf(participant(), Long.MAX_VALUE).forEach {
            expectDenied(admin, it, AgentProfileFailure.AgentNotFound::class.java)
        }
        listOf("ACTIVE", "BLOCKED", "DELETED", "WAITING_VERIFY").forEach { status ->
            val agent = participant(status = status, agent = true)
            tx.executeWithoutResult { access.lockActiveAdminAndAgent(admin, agent) }
        }
        assertEquals("Original", store.find(target)?.fullName)
    }

    @Test
    fun `revocation before lock is seen after wait`() {
        val admin = participant("ADMIN")
        val agent = participant(agent = true)
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        withPool(Executors.newFixedThreadPool(2)) { pool ->
            val revoke = pool.submit {
                tx.executeWithoutResult {
                    store.findForUpdate(admin)
                    locked.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                    jdbc.update("UPDATE participant SET role = 'USER' WHERE id = ?", admin)
                }
            }
            try {
                assertTrue(locked.await(10, TimeUnit.SECONDS))
                val guard = pool.submit { tx.executeWithoutResult { access.lockActiveAdminAndAgent(admin, agent) } }
                assertThrows(TimeoutException::class.java) { guard.get(250, TimeUnit.MILLISECONDS) }
                release.countDown()
                revoke.get(10, TimeUnit.SECONDS)
                assertTrue(assertThrows(ExecutionException::class.java) { guard.get(10, TimeUnit.SECONDS) }
                    .cause is AgentProfileFailure.AdminForbidden)
            } finally { release.countDown() }
        }
    }

    @Test
    fun `revocation after lock waits and commits only after guard transaction`() {
        val admin = participant("ADMIN")
        val agent = participant(agent = true)
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        withPool(Executors.newFixedThreadPool(2)) { pool ->
            val owner = pool.submit {
                tx.executeWithoutResult {
                    access.lockActiveAdminAndAgent(admin, agent)
                    locked.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                    store.save(store.findForUpdate(agent)!!.copy(fullName = "Allowed"))
                }
            }
            try {
                assertTrue(locked.await(10, TimeUnit.SECONDS))
                val revoke = pool.submit(Callable { jdbc.update("UPDATE participant SET status = 'BLOCKED' WHERE id = ?", admin) })
                assertThrows(TimeoutException::class.java) { revoke.get(250, TimeUnit.MILLISECONDS) }
                release.countDown()
                owner.get(10, TimeUnit.SECONDS)
                assertEquals(1, revoke.get(10, TimeUnit.SECONDS))
                assertEquals("Allowed", store.find(agent)?.fullName)
                assertEquals(ParticipantStatus.BLOCKED, store.find(admin)?.status)
            } finally { release.countDown() }
        }
    }

    @Test
    fun `opposite IDs with invalid targets finish without a lock cycle`() {
        val low = participant("ADMIN")
        val high = participant("ADMIN")
        val start = CountDownLatch(1)
        withPool(Executors.newFixedThreadPool(2)) { pool ->
            val attempts = listOf(low to high, high to low).map { (caller, target) ->
                pool.submit {
                    check(start.await(10, TimeUnit.SECONDS))
                    assertThrows(AgentProfileFailure.AgentNotFound::class.java) {
                        tx.executeWithoutResult { access.lockActiveAdminAndAgent(caller, target) }
                    }
                }
            }
            start.countDown()
            attempts.forEach { it.get(10, TimeUnit.SECONDS) }
        }
    }

    @Test
    fun `guard and profile update share ascending order when agent ID is lower`() {
        val agent = participant(agent = true)
        val admin = participant("ADMIN")
        assertTrue(agent < admin)
        val start = CountDownLatch(1)
        withPool(Executors.newFixedThreadPool(2)) { pool ->
            val guard = pool.submit {
                check(start.await(10, TimeUnit.SECONDS))
                tx.executeWithoutResult { access.lockActiveAdminAndAgent(admin, agent) }
            }
            val update = pool.submit(Callable {
                check(start.await(10, TimeUnit.SECONDS))
                profiles.update(admin, agent, AgentProfilePatch(fullName = "Updated"))
            })
            start.countDown()
            guard.get(10, TimeUnit.SECONDS)
            assertEquals("Updated", update.get(10, TimeUnit.SECONDS).fullName)
        }
    }

    @Test
    fun `external owner failure rolls back write and releases locks`() {
        val admin = participant("ADMIN")
        val agent = participant(agent = true)
        assertThrows(IllegalStateException::class.java) {
            tx.executeWithoutResult {
                access.lockActiveAdminAndAgent(admin, agent)
                store.save(store.findForUpdate(agent)!!.copy(fullName = "Rolled back"))
                throw IllegalStateException("later owner command failed")
            }
        }
        assertEquals("Original", store.find(agent)?.fullName)
        tx.executeWithoutResult { access.lockActiveAdminAndAgent(admin, agent) }
    }

    @Test
    fun `preloaded managed caller is refreshed after external revocation`() {
        val admin = participant("ADMIN")
        val agent = participant(agent = true)
        val loaded = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val revokerLocked = CountDownLatch(1)
        val releaseRevoker = CountDownLatch(1)
        withPool(Executors.newFixedThreadPool(2)) { pool ->
            val guard = pool.submit {
                tx.executeWithoutResult {
                    assertEquals("ADMIN", entityManager.find(ParticipantEntity::class.java, admin).role.name)
                    loaded.countDown()
                    check(proceed.await(10, TimeUnit.SECONDS))
                    access.lockActiveAdminAndAgent(admin, agent)
                }
            }
            try {
                assertTrue(loaded.await(10, TimeUnit.SECONDS))
                val revoke = pool.submit {
                    tx.executeWithoutResult {
                        store.findForUpdate(admin)
                        jdbc.update("UPDATE participant SET role = 'USER' WHERE id = ?", admin)
                        revokerLocked.countDown()
                        check(releaseRevoker.await(10, TimeUnit.SECONDS))
                    }
                }
                assertTrue(revokerLocked.await(10, TimeUnit.SECONDS))
                proceed.countDown()
                assertThrows(TimeoutException::class.java) { guard.get(250, TimeUnit.MILLISECONDS) }
                releaseRevoker.countDown()
                revoke.get(10, TimeUnit.SECONDS)
                assertTrue(assertThrows(ExecutionException::class.java) { guard.get(10, TimeUnit.SECONDS) }
                    .cause is AgentProfileFailure.AdminForbidden)
            } finally {
                proceed.countDown()
                releaseRevoker.countDown()
            }
        }
    }

    @Test
    fun `preloaded managed agent is refreshed after agent flag is removed`() {
        val admin = participant("ADMIN")
        val agent = participant(agent = true)
        val loaded = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        withPool(Executors.newSingleThreadExecutor()) { pool ->
            val guard = pool.submit {
                tx.executeWithoutResult {
                    assertTrue(entityManager.find(ParticipantEntity::class.java, agent).isAgent)
                    loaded.countDown()
                    check(proceed.await(10, TimeUnit.SECONDS))
                    access.lockActiveAdminAndAgent(admin, agent)
                }
            }
            try {
                assertTrue(loaded.await(10, TimeUnit.SECONDS))
                jdbc.update("UPDATE participant SET is_agent = false WHERE id = ?", agent)
                proceed.countDown()
                assertTrue(assertThrows(ExecutionException::class.java) { guard.get(10, TimeUnit.SECONDS) }
                    .cause is AgentProfileFailure.AgentNotFound)
            } finally { proceed.countDown() }
        }
    }

    @Test
    fun `target row remains locked until caller rolls back`() {
        val admin = participant("ADMIN")
        val agent = participant(agent = true)
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        withPool(Executors.newFixedThreadPool(2)) { pool ->
            val owner = pool.submit {
                assertThrows(IllegalStateException::class.java) {
                    tx.executeWithoutResult {
                        access.lockActiveAdminAndAgent(admin, agent)
                        locked.countDown()
                        check(release.await(10, TimeUnit.SECONDS))
                        throw IllegalStateException("rollback")
                    }
                }
            }
            try {
                assertTrue(locked.await(10, TimeUnit.SECONDS))
                val edit = pool.submit(Callable { jdbc.update("UPDATE participant SET full_name = 'After rollback' WHERE id = ?", agent) })
                assertThrows(TimeoutException::class.java) { edit.get(250, TimeUnit.MILLISECONDS) }
                release.countDown()
                owner.get(10, TimeUnit.SECONDS)
                assertEquals(1, edit.get(10, TimeUnit.SECONDS))
                assertEquals("After rollback", store.find(agent)?.fullName)
            } finally { release.countDown() }
        }
    }

    @Test
    fun `locking a dirty managed participant preserves its pending change`() {
        val participantId = participant()
        tx.executeWithoutResult {
            val managed = entityManager.find(ParticipantEntity::class.java, participantId)
            managed.fullName = "Pending"
            assertEquals("Pending", store.findForUpdate(participantId)?.fullName)
        }
        assertEquals("Pending", store.find(participantId)?.fullName)
    }

    companion object {
        private val postgres = EmbeddedPostgres.start()

        @JvmStatic @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) {
            val url = "jdbc:postgresql://localhost:${postgres.port}/postgres"
            registry.add("spring.datasource.url") { url }
            registry.add("spring.datasource.username") { "postgres" }
            registry.add("spring.datasource.password") { "" }
            registry.add("spring.flyway.url") { url }
            registry.add("spring.flyway.user") { "postgres" }
            registry.add("spring.flyway.password") { "" }
        }
    }
}
