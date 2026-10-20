package com.model_store.modern.seller.agent.profile

import com.amazonaws.services.s3.AmazonS3
import com.model_store.modern.identity.participant.application.AgentProfileFailure
import com.model_store.modern.identity.verification.application.VerificationMail
import com.model_store.modern.seller.address.application.AddressUseCases
import com.model_store.modern.seller.address.domain.AddressFields
import com.model_store.modern.seller.agent.profile.application.AgentProfileWrites
import com.model_store.modern.seller.account.domain.AccountDetails
import com.model_store.modern.seller.account.domain.TransferMoney
import com.model_store.modern.seller.social.domain.SocialNetworkFields
import com.model_store.modern.seller.social.domain.SocialNetworkType
import com.model_store.modern.seller.transfer.domain.ShippingMethod
import com.model_store.modern.seller.transfer.domain.TransferCurrency
import com.model_store.modern.seller.transfer.domain.TransferFields
import com.model_store.modern.shared.domain.Actor
import com.model_store.modern.shared.domain.TokenType
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doAnswer
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Exercises the administrative write facade against a concurrent role revocation. */
@SpringBootTest(properties = ["app.public-key-path=keys/test_public_key.pem",
    "app.private-key-path=keys/test_private_key.pem"])
@ActiveProfiles("modern")
class AgentProfileAuthorizationRaceTest {
    @field:MockitoBean lateinit var s3: AmazonS3
    @field:MockitoBean lateinit var mail: VerificationMail
    @Autowired lateinit var writes: AgentProfileWrites
    @field:MockitoSpyBean lateinit var addresses: AddressUseCases
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var transactions: PlatformTransactionManager

    @Test
    fun `all twelve detail writes require current administrator and agent inside JPA proxy`() {
        val admin = participant("ADMIN", false)
        val agent = participant("USER", true)
        val actor = Actor(admin, "admin", "ADMIN", TokenType.ACCESS)
        val address = AddressFields("RU", "Guarded", null, null, null, null)
        val account = AccountDetails(TransferMoney.BANK_CARD, null, "guarded", null)
        val transfer = TransferFields(ShippingMethod.PRODUCT_PICKUP, 1, TransferCurrency.RUB)
        val network = SocialNetworkFields(SocialNetworkType.TELEGRAM, "guarded")
        val writesToCheck: List<() -> Any?> = listOf(
            { writes.createAddress(actor, agent, address) },
            { writes.updateAddress(actor, agent, Long.MAX_VALUE, address) },
            { writes.deleteAddress(actor, agent, Long.MAX_VALUE) },
            { writes.createAccount(actor, agent, account) },
            { writes.updateAccount(actor, agent, Long.MAX_VALUE, account) },
            { writes.deleteAccount(actor, agent, Long.MAX_VALUE) },
            { writes.createTransfer(actor, agent, transfer) },
            { writes.updateTransfer(actor, agent, Long.MAX_VALUE, transfer) },
            { writes.deleteTransfer(actor, agent, Long.MAX_VALUE) },
            { writes.createSocialNetwork(actor, agent, network) },
            { writes.updateSocialNetwork(actor, agent, Long.MAX_VALUE, network) },
            { writes.deleteSocialNetwork(actor, agent, Long.MAX_VALUE) },
        )
        jdbc.update("UPDATE participant SET role = 'USER'::participant_role WHERE id = ?", admin)
        writesToCheck.forEach { operation ->
            assertThrows(AgentProfileFailure.AdminForbidden::class.java) { operation() }
        }
        jdbc.update("UPDATE participant SET role = 'ADMIN'::participant_role, status = 'BLOCKED'::participant_status WHERE id = ?", admin)
        writesToCheck.forEach { operation ->
            assertThrows(AgentProfileFailure.AdminForbidden::class.java) { operation() }
        }
        jdbc.update("UPDATE participant SET status = 'ACTIVE'::participant_status WHERE id = ?", admin)
        jdbc.update("UPDATE participant SET is_agent = false WHERE id = ?", agent)
        writesToCheck.forEach { operation ->
            assertThrows(AgentProfileFailure.AgentNotFound::class.java) { operation() }
        }
        assertEquals(0, addressCount(agent, "Guarded"))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM account WHERE participant_id = ?", Int::class.java, agent))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM transfer WHERE participant_id = ?", Int::class.java, agent))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM social_network WHERE participant_id = ?", Int::class.java, agent))
    }

    @Test
    fun `revocation before facade guard rejects the write`() {
        val admin = participant("ADMIN", false)
        val agent = participant("USER", true)
        val actor = Actor(admin, "admin", "ADMIN", TokenType.ACCESS)
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val revoke = executor.submit {
                TransactionTemplate(transactions).executeWithoutResult {
                    jdbc.queryForObject("SELECT id FROM participant WHERE id = ? FOR UPDATE", Long::class.java, admin)
                    locked.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                    jdbc.update("UPDATE participant SET role = 'USER'::participant_role WHERE id = ?", admin)
                }
            }
            assertTrue(locked.await(10, TimeUnit.SECONDS))
            val pending = executor.submit<Long> {
                writes.createAddress(actor, agent, AddressFields("RU", "Denied after revocation", null, null, null, null))
            }
            assertThrows(TimeoutException::class.java) { pending.get(250, TimeUnit.MILLISECONDS) }
            release.countDown()
            revoke.get(10, TimeUnit.SECONDS)
            assertTrue(assertThrows(ExecutionException::class.java) { pending.get(10, TimeUnit.SECONDS) }
                .cause is AgentProfileFailure.AdminForbidden)
            assertEquals("USER", jdbc.queryForObject(
                "SELECT role::text FROM participant WHERE id = ?", String::class.java, admin))
            assertEquals(0, addressCount(agent, "Denied after revocation"))
        } finally {
            release.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `revocation after facade guard waits for the owner write to commit`() {
        val admin = participant("ADMIN", false)
        val agent = participant("USER", true)
        val actor = Actor(admin, "admin", "ADMIN", TokenType.ACCESS)
        val fields = AddressFields("RU", "Allowed before revocation", null, null, null, null)
        val guarded = CountDownLatch(1)
        val release = CountDownLatch(1)
        doAnswer { invocation ->
            guarded.countDown()
            check(release.await(10, TimeUnit.SECONDS))
            invocation.callRealMethod()
        }.`when`(addresses).create(agent, fields)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val pending = executor.submit<Long> { writes.createAddress(actor, agent, fields) }
            assertTrue(guarded.await(10, TimeUnit.SECONDS), "owner use case was not reached")
            val revoke = executor.submit<Int> {
                jdbc.update("UPDATE participant SET role = 'USER'::participant_role WHERE id = ?", admin)
            }
            assertThrows(TimeoutException::class.java) { revoke.get(250, TimeUnit.MILLISECONDS) }
            release.countDown()
            val savedId = pending.get(10, TimeUnit.SECONDS)
            assertEquals(1, revoke.get(10, TimeUnit.SECONDS))
            assertEquals(1, addressCount(agent, "Allowed before revocation"))
            assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM address WHERE id = ?", Int::class.java, savedId))
            assertEquals("USER", jdbc.queryForObject(
                "SELECT role::text FROM participant WHERE id = ?", String::class.java, admin))
        } finally {
            release.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `target flag change after facade guard waits for the owner write`() {
        val admin = participant("ADMIN", false)
        val agent = participant("USER", true)
        val actor = Actor(admin, "admin", "ADMIN", TokenType.ACCESS)
        val fields = AddressFields("RU", "Before target change", null, null, null, null)
        val guarded = CountDownLatch(1)
        val release = CountDownLatch(1)
        doAnswer { invocation ->
            guarded.countDown()
            check(release.await(10, TimeUnit.SECONDS))
            invocation.callRealMethod()
        }.`when`(addresses).create(agent, fields)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val pending = executor.submit<Long> { writes.createAddress(actor, agent, fields) }
            assertTrue(guarded.await(10, TimeUnit.SECONDS))
            val change = executor.submit<Int> {
                jdbc.update("UPDATE participant SET is_agent = false WHERE id = ?", agent)
            }
            assertThrows(TimeoutException::class.java) { change.get(250, TimeUnit.MILLISECONDS) }
            release.countDown()
            pending.get(10, TimeUnit.SECONDS)
            assertEquals(1, change.get(10, TimeUnit.SECONDS))
            assertEquals(1, addressCount(agent, "Before target change"))
        } finally {
            release.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `owner failure after insert rolls back the whole facade transaction`() {
        val admin = participant("ADMIN", false)
        val agent = participant("USER", true)
        val actor = Actor(admin, "admin", "ADMIN", TokenType.ACCESS)
        val fields = AddressFields("RU", "Rolled back owner write", null, null, null, null)
        doAnswer { invocation ->
            invocation.callRealMethod()
            throw IllegalStateException("failure after owner insert")
        }.`when`(addresses).create(agent, fields)
        assertThrows(IllegalStateException::class.java) { writes.createAddress(actor, agent, fields) }
        assertEquals(0, addressCount(agent, "Rolled back owner write"))
        assertEquals("ADMIN", jdbc.queryForObject(
            "SELECT role::text FROM participant WHERE id = ?", String::class.java, admin))
    }

    private fun addressCount(agent: Long, city: String): Int = jdbc.queryForObject(
        """SELECT count(*) FROM address a JOIN participant_address pa ON pa.address_id = a.id
            WHERE pa.participant_id = ? AND a.city = ?""", Int::class.java, agent, city)!!

    private fun participant(role: String, agent: Boolean): Long {
        val login = "agent-profile-race-${UUID.randomUUID()}"
        return jdbc.queryForObject(
            """INSERT INTO participant(login, mail, password, role, status, is_agent, deadline_sending, deadline_payment)
                VALUES (?, ?, 'secret', ?::participant_role, 'ACTIVE', ?, 1, 1) RETURNING id""",
            Long::class.java, login, "$login@example.test", role, agent,
        )!!
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
        @JvmStatic @AfterAll fun close() = postgres.close()
    }
}
