package com.model_store.modern.identity.participant

import com.amazonaws.services.s3.AmazonS3
import com.model_store.modern.identity.participant.application.RegisterParticipant
import com.model_store.modern.identity.participant.application.ReadParticipant
import com.model_store.modern.identity.participant.application.ParticipantImagePort
import com.model_store.modern.identity.participant.application.ParticipantStore
import com.model_store.modern.identity.participant.application.UpdateParticipant
import com.model_store.modern.identity.participant.application.ParticipantVerificationCommands
import com.model_store.modern.identity.participant.application.ParticipantVerificationFailure
import com.model_store.modern.identity.participant.domain.ParticipantStatus
import com.model_store.modern.identity.participant.domain.ParticipantImageNotFound
import com.model_store.modern.identity.verification.application.VerificationMail
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.context.WebApplicationContext
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import jakarta.servlet.Filter
import org.mockito.Mockito
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootTest
@ActiveProfiles("modern")
class ParticipantJpaIntegrationTest {
    @field:MockitoBean lateinit var s3: AmazonS3
    @field:MockitoBean lateinit var jwtDecoder: JwtDecoder
    @field:MockitoBean lateinit var mail: VerificationMail
    @Autowired lateinit var register: RegisterParticipant
    @Autowired lateinit var read: ReadParticipant
    @Autowired lateinit var update: UpdateParticipant
    @Autowired lateinit var verificationCommands: ParticipantVerificationCommands
    @Autowired lateinit var store: ParticipantStore
    @Autowired lateinit var images: ParticipantImagePort
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var transactionManager: PlatformTransactionManager
    @Autowired lateinit var webContext: WebApplicationContext

    @Test
    @Transactional
    fun `registration and profile update use existing schema`() {
        val id = register.execute("participant-jpa@example.test", "secret", 24)
        val created = store.find(id)!!
        assertEquals(ParticipantStatus.WAITING_VERIFY, created.status)
        assertEquals("user$id", created.login)
        assertEquals(24, created.age)
        store.save(created.copy(status = ParticipantStatus.ACTIVE))
        update.execute(id, "changed-jpa-$id", "Test User", null, null, null, null)
        assertEquals("changed-jpa-$id", store.find(id)?.login)
        assertEquals("participant-jpa@example.test", store.find(id)?.mail)
        val addressId = jdbc.queryForObject(
            "INSERT INTO address(country, city, street, house_number, apartment_number, \"index\") VALUES ('RU', 'Moscow', 'Main', '1', '2', 123456) RETURNING id",
            Long::class.java,
        )!!
        jdbc.update("INSERT INTO participant_address(participant_id, address_id) VALUES (?, ?)", id, addressId)
        jdbc.update("INSERT INTO account(transfer_money, username, entity_value, participant_id) VALUES ('BANK_CARD', 'Owner', '1234', ?)", id)
        jdbc.update("INSERT INTO transfer(sending, price, currency, participant_id) VALUES ('RUSSIAN_POST', 100, 'RUB', ?)", id)
        jdbc.update("INSERT INTO social_network(type, login, participant_id) VALUES ('TELEGRAM', 'owner', ?)", id)
        jdbc.update("INSERT INTO seller_rating(seller_id, average_rating, total_reviews) VALUES (?, 4.25, 2)", id)
        val profile = read.current(id)!!
        assertEquals(id, profile.id)
        assertEquals("participant-jpa@example.test", profile.mail)
        assertEquals(4.25f, profile.averageRating)
        assertEquals(2, profile.totalReviews)
        assertEquals("Moscow", profile.addresses.single()["city"])
        assertEquals("BANK_CARD", profile.accounts.single()["transferMoney"])
        assertEquals("RUSSIAN_POST", profile.transfers.single()["sending"])
        assertEquals("TELEGRAM", profile.socialNetworks.single()["type"])
    }

    @Test
    @Transactional
    fun `MVC registration accepts legacy JSON and search returns legacy fields`() {
        val mvc = MockMvcBuilders.webAppContextSetup(webContext).build()
        val registered = mvc.perform(post("/participant").contentType(MediaType.APPLICATION_JSON)
            .content("""{"mail":"participant-http@example.test","password":"secret","age":21}"""))
            .andExpect(status().isOk).andReturn().response.contentAsString.toLong()
        store.save(store.find(registered)!!.copy(status = ParticipantStatus.ACTIVE))
        val search = mvc.perform(post("/participants/find").contentType(MediaType.APPLICATION_JSON)
            .content("""{"name":"participant-http@example.test"}"""))
            .andExpect(status().isOk).andReturn().response.contentAsString
        assertTrue(search.contains("\"login\":\"user$registered\""))
        assertTrue(search.contains("\"orderCompletedCount\":0"))
    }

    @Test
    @Transactional
    fun `status route rejects a non-admin actor`() {
        val id = register.execute("participant-auth@example.test", "secret", 20)
        store.save(store.find(id)!!.copy(status = ParticipantStatus.ACTIVE))
        val jwt = Jwt.withTokenValue("participant-user-token")
            .header("alg", "RS256").claim("id", id).claim("role", "USER").claim("login", "user$id")
            .claim("type", "access").build()
        Mockito.`when`(jwtDecoder.decode("participant-user-token")).thenReturn(jwt)
        val securityFilter = webContext.getBean("springSecurityFilterChain") as Filter
        val builder = MockMvcBuilders.webAppContextSetup(webContext)
        builder.addFilters<DefaultMockMvcBuilder>(securityFilter)
        val mvc = builder.build()
        mvc.perform(put("/admin/actions/participants/$id/status")
            .header("Authorization", "Bearer participant-user-token"))
            .andExpect(status().isForbidden)
        assertEquals(ParticipantStatus.ACTIVE, store.find(id)?.status)
        val profile = mvc.perform(get("/participant").header("Authorization", "Bearer participant-user-token"))
            .andExpect(status().isOk).andReturn().response.contentAsString
        assertTrue(profile.contains("\"id\":$id"))
        assertFalse(profile.contains("password"))
        val original = store.find(id)!!
        val tampered = mvc.perform(put("/participant")
            .header("Authorization", "Bearer participant-user-token")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"login":"safe-$id","mail":"attacker@example.test","role":"ADMIN","status":"BLOCKED","password":"other","isAgent":true}"""))
            .andReturn().response.status
        assertTrue(tampered == 200 || tampered == 400)
        val after = store.find(id)!!
        assertEquals(original.mail, after.mail)
        assertEquals(original.role, after.role)
        assertEquals(original.status, after.status)
        assertEquals(original.passwordHash, after.passwordHash)
        assertFalse(after.isAgent)
    }

    @Test
    @Transactional
    fun `participant image cannot be claimed from another owner`() {
        val owner = register.execute("image-owner@example.test", "secret", 20)
        val other = register.execute("image-other@example.test", "secret", 20)
        val imageId = jdbc.queryForObject(
            "INSERT INTO image(tag, status, entity_id) VALUES ('PARTICIPANT', 'ACTIVE', ?) RETURNING id",
            Long::class.java, owner,
        )!!
        assertThrows(ParticipantImageNotFound::class.java) { images.replace(other, imageId) }
        assertEquals(owner, jdbc.queryForObject("SELECT entity_id FROM image WHERE id = ?", Long::class.java, imageId))
    }

    @Test
    fun `profile update accepts own unassigned image and preserves old image on rejected claims`() {
        val owner = register.execute("image-owner-${UUID.randomUUID()}@example.test", "secret", 20)
        val other = register.execute("image-other-${UUID.randomUUID()}@example.test", "secret", 20)
        TransactionTemplate(transactionManager).executeWithoutResult {
            store.save(store.find(owner)!!.copy(status = ParticipantStatus.ACTIVE))
        }
        val old = jdbc.queryForObject(
            "INSERT INTO image(tag, status, entity_id) VALUES ('PARTICIPANT', 'ACTIVE', ?) RETURNING id",
            Long::class.java, owner,
        )!!
        fun image(status: String, uploadedBy: Long?): Long = jdbc.queryForObject(
            "INSERT INTO image(tag, status, uploaded_by) VALUES ('PARTICIPANT', ?::image_status, ?) RETURNING id",
            Long::class.java, status, uploadedBy,
        )!!
        val foreign = image("ACTIVE", other)
        val unknownUploader = image("ACTIVE", null)
        val inactive = image("DELETE", owner)
        for (candidate in listOf(foreign, unknownUploader, inactive, Long.MAX_VALUE)) {
            assertThrows(ParticipantImageNotFound::class.java) {
                update.execute(owner, "rejected-$candidate", null, null, null, null, candidate)
            }
            assertEquals("ACTIVE", jdbc.queryForObject("SELECT status::text FROM image WHERE id = ?", String::class.java, old))
            assertEquals(owner, jdbc.queryForObject("SELECT entity_id FROM image WHERE id = ?", Long::class.java, old))
            assertEquals("user$owner", store.find(owner)?.login)
        }
        assertNull(jdbc.queryForObject("SELECT entity_id FROM image WHERE id = ?", Long::class.java, foreign))
        assertNull(jdbc.queryForObject("SELECT entity_id FROM image WHERE id = ?", Long::class.java, unknownUploader))
        assertNull(jdbc.queryForObject("SELECT entity_id FROM image WHERE id = ?", Long::class.java, inactive))

        val own = image("ACTIVE", owner)
        update.execute(owner, "image-owner-$owner", null, null, null, null, own)
        assertEquals(owner, jdbc.queryForObject("SELECT entity_id FROM image WHERE id = ?", Long::class.java, own))
        assertEquals("DELETE", jdbc.queryForObject("SELECT status::text FROM image WHERE id = ?", String::class.java, old))

        // A pre-migration image may have no uploaded_by, but its existing binding still identifies the owner.
        jdbc.update("UPDATE image SET status = 'ACTIVE' WHERE id = ?", old)
        update.execute(owner, null, null, null, null, null, old)
        assertEquals("ACTIVE", jdbc.queryForObject("SELECT status::text FROM image WHERE id = ?", String::class.java, old))
        assertEquals("DELETE", jdbc.queryForObject("SELECT status::text FROM image WHERE id = ?", String::class.java, own))
        update.execute(owner, null, null, null, null, null, old)
        assertEquals("ACTIVE", jdbc.queryForObject("SELECT status::text FROM image WHERE id = ?", String::class.java, old))
    }

    @Test
    fun `concurrent assignment is rechecked after image row lock`() {
        val owner = register.execute("image-race-owner-${UUID.randomUUID()}@example.test", "secret", 20)
        val other = register.execute("image-race-other-${UUID.randomUUID()}@example.test", "secret", 20)
        TransactionTemplate(transactionManager).executeWithoutResult {
            store.save(store.find(owner)!!.copy(status = ParticipantStatus.ACTIVE))
        }
        val old = jdbc.queryForObject(
            "INSERT INTO image(tag, status, entity_id) VALUES ('PARTICIPANT', 'ACTIVE', ?) RETURNING id",
            Long::class.java, owner,
        )!!
        val candidate = jdbc.queryForObject(
            "INSERT INTO image(tag, status, uploaded_by) VALUES ('PARTICIPANT', 'ACTIVE', ?) RETURNING id",
            Long::class.java, owner,
        )!!
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val competingAssignment = executor.submit {
                TransactionTemplate(transactionManager).executeWithoutResult {
                    jdbc.update("UPDATE image SET entity_id = ? WHERE id = ?", other, candidate)
                    locked.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                }
            }
            assertTrue(locked.await(10, TimeUnit.SECONDS))
            val claim = executor.submit {
                update.execute(owner, "lost-race-$owner", null, null, null, null, candidate)
            }
            assertThrows(java.util.concurrent.TimeoutException::class.java) { claim.get(200, TimeUnit.MILLISECONDS) }
            release.countDown()
            competingAssignment.get(10, TimeUnit.SECONDS)
            val failure = assertThrows(java.util.concurrent.ExecutionException::class.java) { claim.get(10, TimeUnit.SECONDS) }
            assertTrue(failure.cause is ParticipantImageNotFound)
            assertEquals(other, jdbc.queryForObject("SELECT entity_id FROM image WHERE id = ?", Long::class.java, candidate))
            assertEquals("ACTIVE", jdbc.queryForObject("SELECT status::text FROM image WHERE id = ?", String::class.java, old))
            assertEquals("user$owner", store.find(owner)?.login)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `image assignment rolls back when profile update fails`() {
        val id = register.execute("image-rollback@example.test", "secret", 20)
        TransactionTemplate(transactionManager).executeWithoutResult {
            store.save(store.find(id)!!.copy(status = ParticipantStatus.ACTIVE))
        }
        val old = jdbc.queryForObject(
            "INSERT INTO image(tag, status, entity_id) VALUES ('PARTICIPANT', 'ACTIVE', ?) RETURNING id",
            Long::class.java, id,
        )!!
        val imageId = jdbc.queryForObject(
            "INSERT INTO image(tag, status, uploaded_by) VALUES ('PARTICIPANT', 'ACTIVE', ?) RETURNING id",
            Long::class.java, id,
        )!!
        assertThrows(Exception::class.java) {
            update.execute(id, "x".repeat(300), null, null, null, null, imageId)
        }
        assertNull(jdbc.queryForObject("SELECT entity_id FROM image WHERE id = ?", Long::class.java, imageId))
        assertEquals("ACTIVE", jdbc.queryForObject("SELECT status::text FROM image WHERE id = ?", String::class.java, imageId))
        assertEquals("ACTIVE", jdbc.queryForObject("SELECT status::text FROM image WHERE id = ?", String::class.java, old))
        assertEquals("user$id", store.find(id)?.login)
    }

    @Test
    fun `verification commands persist status and password in isolated database`() {
        val id = register.execute("verification-${UUID.randomUUID()}@example.test", "original", 20)
        verificationCommands.activate(id)
        assertEquals(ParticipantStatus.ACTIVE, store.find(id)?.status)
        assertThrows(ParticipantVerificationFailure.AlreadyActive::class.java) { verificationCommands.activate(id) }
        verificationCommands.resetPassword(id, "replacement")
        assertEquals(ParticipantStatus.ACTIVE, store.find(id)?.status)
        assertTrue(BCryptPasswordEncoder().matches("replacement", store.find(id)?.passwordHash))

        val waitingId = register.execute("verification-${UUID.randomUUID()}@example.test", "original", 20)
        verificationCommands.resetPassword(waitingId, "temporary")
        assertEquals(ParticipantStatus.ACTIVE, store.find(waitingId)?.status)
        assertTrue(BCryptPasswordEncoder().matches("temporary", store.find(waitingId)?.passwordHash))

        for (status in listOf(ParticipantStatus.BLOCKED, ParticipantStatus.DELETED)) {
            val rejectedId = register.execute("verification-${UUID.randomUUID()}@example.test", "original", 20)
            TransactionTemplate(transactionManager).executeWithoutResult {
                store.save(store.find(rejectedId)!!.copy(status = status))
            }
            val failure = if (status == ParticipantStatus.BLOCKED)
                ParticipantVerificationFailure.Blocked::class.java else ParticipantVerificationFailure.Deleted::class.java
            assertThrows(failure) { verificationCommands.activate(rejectedId) }
            assertThrows(failure) { verificationCommands.resetPassword(rejectedId, "replacement") }
            assertEquals(status, store.find(rejectedId)?.status)
            assertTrue(BCryptPasswordEncoder().matches("original", store.find(rejectedId)?.passwordHash))
        }
        assertThrows(ParticipantVerificationFailure.NotFound::class.java) { verificationCommands.activate(-1) }
        assertThrows(ParticipantVerificationFailure.NotFound::class.java) { verificationCommands.resetPassword(-1, "replacement") }
    }

    @Test
    fun `reset waits for concurrent participant edit and preserves both changes`() {
        val id = register.execute("verification-${UUID.randomUUID()}@example.test", "original", 20)
        val locked = CountDownLatch(1)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val edit = executor.submit {
                TransactionTemplate(transactionManager).executeWithoutResult {
                    val participant = store.findForUpdate(id)!!
                    locked.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                    store.save(participant.copy(fullName = "Concurrent edit"))
                }
            }
            assertTrue(locked.await(10, TimeUnit.SECONDS))
            val reset = executor.submit {
                started.countDown()
                verificationCommands.resetPassword(id, "replacement")
            }
            assertTrue(started.await(10, TimeUnit.SECONDS))
            assertThrows(java.util.concurrent.TimeoutException::class.java) { reset.get(200, TimeUnit.MILLISECONDS) }
            release.countDown()
            edit.get(10, TimeUnit.SECONDS)
            reset.get(10, TimeUnit.SECONDS)
            val saved = store.find(id)!!
            assertEquals("Concurrent edit", saved.fullName)
            assertEquals(ParticipantStatus.ACTIVE, saved.status)
            assertTrue(BCryptPasswordEncoder().matches("replacement", saved.passwordHash))
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `activation observes concurrent block after row lock is released`() {
        val id = register.execute("verification-${UUID.randomUUID()}@example.test", "original", 20)
        val locked = CountDownLatch(1)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val block = executor.submit {
                TransactionTemplate(transactionManager).executeWithoutResult {
                    val participant = store.findForUpdate(id)!!
                    locked.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                    store.save(participant.copy(status = ParticipantStatus.BLOCKED))
                }
            }
            assertTrue(locked.await(10, TimeUnit.SECONDS))
            val activation = executor.submit {
                started.countDown()
                verificationCommands.activate(id)
            }
            assertTrue(started.await(10, TimeUnit.SECONDS))
            assertThrows(java.util.concurrent.TimeoutException::class.java) { activation.get(200, TimeUnit.MILLISECONDS) }
            release.countDown()
            block.get(10, TimeUnit.SECONDS)
            val cause = assertThrows(java.util.concurrent.ExecutionException::class.java) { activation.get(10, TimeUnit.SECONDS) }
            assertTrue(cause.cause is ParticipantVerificationFailure.Blocked)
            assertEquals(ParticipantStatus.BLOCKED, store.find(id)?.status)
            assertTrue(BCryptPasswordEncoder().matches("original", store.find(id)?.passwordHash))
        } finally {
            release.countDown()
            executor.shutdownNow()
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
