package com.model_store.modern.identity.participant

import com.amazonaws.services.s3.AmazonS3
import com.model_store.modern.identity.participant.application.AgentProfileCommands
import com.model_store.modern.identity.participant.application.AgentProfileFailure
import com.model_store.modern.identity.participant.application.AgentProfilePatch
import com.model_store.modern.identity.participant.application.AgentProfileSnapshot
import com.model_store.modern.identity.participant.application.ParticipantStore
import com.model_store.modern.identity.participant.application.UpdateParticipant
import com.model_store.modern.identity.participant.domain.AgentProfileForbidden
import com.model_store.modern.identity.participant.domain.ParticipantImageNotFound
import com.model_store.modern.identity.participant.domain.ParticipantStatus
import com.model_store.modern.identity.verification.application.VerificationMail
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

@SpringBootTest(properties = ["app.public-key-path=keys/test_public_key.pem", "app.private-key-path=keys/test_private_key.pem"])
@ActiveProfiles("modern")
class AgentProfileCommandsIntegrationTest {
    @field:MockitoBean lateinit var s3: AmazonS3
    @field:MockitoBean lateinit var jwtDecoder: JwtDecoder
    @field:MockitoBean lateinit var mail: VerificationMail
    @Autowired lateinit var commands: AgentProfileCommands
    @Autowired lateinit var updateParticipant: UpdateParticipant
    @Autowired lateinit var store: ParticipantStore
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var txManager: PlatformTransactionManager

    private fun participant(role: String = "USER", status: String = "ACTIVE", agent: Boolean = false): Long {
        val login = "agent-profile-${UUID.randomUUID()}"
        return jdbc.queryForObject(
            """INSERT INTO participant(login, mail, password, role, status, is_agent, full_name, phone_number,
                deadline_sending, deadline_payment) VALUES (?, ?, 'secret', ?::participant_role,
                ?::participant_status, ?, 'Original', '+79990000000', 3, 5) RETURNING id""",
            Long::class.java, login, "$login@example.test", role, status, agent,
        )!!
    }

    private fun image(owner: Long?, boundTo: Long? = null, status: String = "ACTIVE"): Long =
        jdbc.queryForObject(
            "INSERT INTO image(tag, status, uploaded_by, entity_id) VALUES ('PARTICIPANT', ?::image_status, ?, ?) RETURNING id",
            Long::class.java, status, owner, boundTo,
        )!!

    @Test
    fun `admin reads and patches selected fields without changing protected columns`() {
        val admin = participant("ADMIN")
        val agent = participant(agent = true)
        val before = store.find(agent)!!
        val originalImage = image(null, agent)
        val initial = commands.read(admin, agent)
        assertEquals(originalImage, initial.imageId)
        assertEquals("ACTIVE", initial.status)

        val result = commands.update(admin, agent, AgentProfilePatch(fullName = "Updated", deadlineSending = 60))
        assertEquals(before.login, result.login)
        assertEquals("Updated", result.fullName)
        assertEquals("+79990000000", result.phoneNumber)
        assertEquals(60, result.deadlineSending)
        assertEquals(5, result.deadlinePayment)
        assertEquals(originalImage, result.imageId)
        assertEquals(before.copy(fullName = "Updated", deadlineSending = 60), store.find(agent))
    }

    @Test
    fun `only active non-agent admin may read or write`() {
        val agent = participant(agent = true)
        val callers = listOf(
            participant(), participant("ADMIN", "BLOCKED"), participant("ADMIN", "DELETED"),
            participant("ADMIN", "WAITING_VERIFY"), participant("ADMIN", agent = true),
            Long.MAX_VALUE,
        )
        callers.forEach { caller ->
            assertThrows(AgentProfileFailure.AdminForbidden::class.java) { commands.read(caller, agent) }
            assertThrows(AgentProfileFailure.AdminForbidden::class.java) {
                commands.update(caller, agent, AgentProfilePatch(fullName = "Forbidden"))
            }
        }
        assertEquals("Original", store.find(agent)?.fullName)
    }

    @Test
    fun `missing and ordinary participants are not agents`() {
        val admin = participant("ADMIN")
        val regular = participant()
        listOf(regular, Long.MAX_VALUE).forEach { target ->
            assertThrows(AgentProfileFailure.AgentNotFound::class.java) { commands.read(admin, target) }
            assertThrows(AgentProfileFailure.AgentNotFound::class.java) {
                commands.update(admin, target, AgentProfilePatch(fullName = "No"))
            }
        }
        assertEquals("Original", store.find(regular)?.fullName)
    }

    @Test
    fun `blocked and deleted target agents retain their status`() {
        val admin = participant("ADMIN")
        listOf("BLOCKED", "DELETED", "WAITING_VERIFY").forEach { status ->
            val agent = participant(status = status, agent = true)
            assertEquals(status, commands.read(admin, agent).status)
            assertEquals(status, commands.update(admin, agent, AgentProfilePatch(phoneNumber = "123")).status)
            assertEquals(ParticipantStatus.valueOf(status), store.find(agent)?.status)
            assertEquals("123", store.find(agent)?.phoneNumber)
        }
    }

    @Test
    fun `null fields are no-op and deadlines use the actual smallint range`() {
        val admin = participant("ADMIN")
        val agent = participant(agent = true)
        val before = store.find(agent)
        assertEquals("Original", commands.update(admin, agent, AgentProfilePatch()).fullName)
        assertEquals(before, store.find(agent))
        assertEquals(Short.MIN_VALUE.toInt(), commands.update(admin, agent,
            AgentProfilePatch(deadlineSending = Short.MIN_VALUE.toInt())).deadlineSending)
        assertEquals(Short.MAX_VALUE.toInt(), commands.update(admin, agent,
            AgentProfilePatch(deadlinePayment = Short.MAX_VALUE.toInt())).deadlinePayment)
        listOf(Short.MIN_VALUE.toInt() - 1, Short.MAX_VALUE.toInt() + 1).forEach { invalid ->
            assertThrows(AgentProfileFailure.InvalidDeadline::class.java) {
                commands.update(admin, agent, AgentProfilePatch(fullName = "Bad", deadlineSending = invalid))
            }
            assertThrows(AgentProfileFailure.InvalidDeadline::class.java) {
                commands.update(admin, agent, AgentProfilePatch(deadlinePayment = invalid))
            }
        }
        assertEquals("Original", store.find(agent)?.fullName)
    }

    @Test
    fun `image ownership is enforced and both profile and image roll back on failure`() {
        val admin = participant("ADMIN")
        val agent = participant(agent = true)
        val other = participant()
        val old = image(null, agent)
        assertEquals(old, commands.update(admin, agent, AgentProfilePatch(imageId = old)).imageId)
        val wrongTag = jdbc.queryForObject(
            "INSERT INTO image(tag, status, uploaded_by) VALUES ('PRODUCT', 'ACTIVE', ?) RETURNING id",
            Long::class.java, agent,
        )!!
        for ((candidate, expectedOwner) in listOf(
            image(other) to null, image(null) to null, image(agent, status = "DELETE") to null,
            image(null, other) to other, wrongTag to null,
        )) {
            assertThrows(ParticipantImageNotFound::class.java) {
                commands.update(admin, agent, AgentProfilePatch(fullName = "Rejected", imageId = candidate))
            }
            assertEquals("Original", store.find(agent)?.fullName)
            assertEquals(expectedOwner, jdbc.queryForObject("SELECT entity_id FROM image WHERE id = ?", Long::class.java, candidate))
            assertEquals("ACTIVE", jdbc.queryForObject("SELECT status::text FROM image WHERE id = ?", String::class.java, old))
        }
        val own = image(agent)
        assertEquals(own, commands.update(admin, agent, AgentProfilePatch(imageId = own)).imageId)
        assertEquals("DELETE", jdbc.queryForObject("SELECT status::text FROM image WHERE id = ?", String::class.java, old))

        val next = image(agent)
        assertThrows(DataIntegrityViolationException::class.java) {
            commands.update(admin, agent, AgentProfilePatch(phoneNumber = "x".repeat(41), imageId = next))
        }
        assertNull(jdbc.queryForObject("SELECT entity_id FROM image WHERE id = ?", Long::class.java, next))
        assertEquals("ACTIVE", jdbc.queryForObject("SELECT status::text FROM image WHERE id = ?", String::class.java, own))
        assertEquals("+79990000000", store.find(agent)?.phoneNumber)
    }

    @Test
    fun `concurrent admin edits serialize on participant lock and ordinary update stays forbidden`() {
        val admin = participant("ADMIN")
        val agent = participant(agent = true)
        assertThrows(AgentProfileForbidden::class.java) {
            updateParticipant.execute(agent, "stolen", "No", null, null, null, null)
        }
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val first = pool.submit {
                TransactionTemplate(txManager).execute {
                    val current = store.findForUpdate(agent)!!
                    locked.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                    store.save(current.copy(fullName = "First"))
                }
            }
            assertTrue(locked.await(10, TimeUnit.SECONDS))
            val second = pool.submit(Callable<AgentProfileSnapshot> {
                commands.update(admin, agent, AgentProfilePatch(phoneNumber = "Second"))
            })
            assertThrows(TimeoutException::class.java) { second.get(200, TimeUnit.MILLISECONDS) }
            release.countDown()
            first.get(10, TimeUnit.SECONDS)
            val result = second.get(10, TimeUnit.SECONDS)
            assertEquals("First", result.fullName)
            assertEquals("Second", result.phoneNumber)
            assertEquals("First", store.find(agent)?.fullName)
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun `admin revocation wins when it holds the caller row lock first`() {
        val admin = participant("ADMIN")
        val agent = participant(agent = true)
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val revoke = pool.submit {
                TransactionTemplate(txManager).execute {
                    val caller = store.findForUpdate(admin)!!
                    locked.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                    store.save(caller.copy(status = ParticipantStatus.BLOCKED))
                }
            }
            assertTrue(locked.await(10, TimeUnit.SECONDS))
            val edit = pool.submit(Callable<AgentProfileSnapshot> {
                commands.update(admin, agent, AgentProfilePatch(fullName = "Forbidden"))
            })
            assertThrows(TimeoutException::class.java) { edit.get(200, TimeUnit.MILLISECONDS) }
            release.countDown()
            revoke.get(10, TimeUnit.SECONDS)
            val failure = assertThrows(ExecutionException::class.java) { edit.get(10, TimeUnit.SECONDS) }
            assertTrue(failure.cause is AgentProfileFailure.AdminForbidden)
            assertEquals("Original", store.find(agent)?.fullName)
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    companion object {
        private val postgres = EmbeddedPostgres.start()

        @JvmStatic
        @DynamicPropertySource
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
