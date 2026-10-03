package com.model_store.modern.seller.account

import com.amazonaws.services.s3.AmazonS3
import com.model_store.modern.identity.participant.application.RegisterParticipant
import com.model_store.modern.identity.participant.application.ParticipantStore
import com.model_store.modern.seller.account.application.AccountUseCases
import com.model_store.modern.seller.account.domain.AccountAlreadyExists
import com.model_store.modern.seller.account.domain.AccountDetails
import com.model_store.modern.seller.account.domain.TransferMoney
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import jakarta.servlet.Filter
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.context.WebApplicationContext
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootTest
@ActiveProfiles("modern")
class AccountJpaHttpTest {
    @field:MockitoBean lateinit var s3: AmazonS3
    @field:MockitoBean lateinit var jwtDecoder: JwtDecoder
    @Autowired lateinit var register: RegisterParticipant
    @Autowired lateinit var accounts: AccountUseCases
    @Autowired lateinit var participants: ParticipantStore
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var context: WebApplicationContext

    @Test
    @Transactional
    fun `HTTP CRUD uses existing enum table and rejects foreign accounts`() {
        val ownerId = register.execute("account-owner@example.test", "secret", 30)
        val otherId = register.execute("account-other@example.test", "secret", 30)
        val agentId = register.execute("account-agent@example.test", "secret", 30)
        participants.save(participants.find(agentId)!!.copy(isAgent = true))
        val ownerToken = "account-owner-token"
        val otherToken = "account-other-token"
        val agentToken = "account-agent-token"
        Mockito.`when`(jwtDecoder.decode(ownerToken)).thenReturn(jwt(ownerToken, ownerId))
        Mockito.`when`(jwtDecoder.decode(otherToken)).thenReturn(jwt(otherToken, otherId))
        Mockito.`when`(jwtDecoder.decode(agentToken)).thenReturn(jwt(agentToken, agentId))
        val builder = MockMvcBuilders.webAppContextSetup(context)
        builder.addFilters<DefaultMockMvcBuilder>(context.getBean("springSecurityFilterChain") as Filter)
        val mvc = builder.build()
        fun body(token: String) = post("/accounts").header("Authorization", "Bearer $token")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"transferMoney":"BANK_CARD","username":"Owner","entityValue":"1234","comment":"note"}""")
        val create = mvc.perform(body(ownerToken)).andReturn().response
        assertEquals(200, create.status)
        assertTrue(create.contentAsString.isEmpty())
        val accountId = jdbc.queryForObject("SELECT id FROM account WHERE participant_id = ?", Long::class.java, ownerId)!!
        assertEquals("BANK_CARD", jdbc.queryForObject(
            "SELECT transfer_money::text FROM account WHERE id = ?", String::class.java, accountId))

        val list = mvc.perform(get("/accounts").header("Authorization", "Bearer $ownerToken"))
            .andReturn().response
        assertEquals(200, list.status)
        assertTrue(list.contentAsString.contains("\"participantId\":$ownerId"))
        assertTrue(list.contentAsString.contains("\"entityValue\":\"1234\""))
        val duplicate = mvc.perform(body(ownerToken)).andReturn().response
        assertEquals(409, duplicate.status)
        assertTrue(duplicate.contentAsString.contains("\"code\":\"ACCOUNT_ALREADY_EXISTS\""))
        assertEquals(200, mvc.perform(get("/accounts/participant/$ownerId")
            .header("Authorization", "Bearer $ownerToken")).andReturn().response.status)
        val foreignRead = mvc.perform(get("/accounts/participant/$ownerId")
            .header("Authorization", "Bearer $otherToken")).andReturn().response
        assertEquals(403, foreignRead.status)
        assertTrue(foreignRead.contentAsString.contains("\"code\":\"ACCESS_DENIED\""))
        assertEquals(404, mvc.perform(put("/accounts/$accountId")
            .header("Authorization", "Bearer $otherToken").contentType(MediaType.APPLICATION_JSON)
            .content("""{"transferMoney":"CASH"}""")).andReturn().response.status)
        assertEquals(404, mvc.perform(delete("/accounts/$accountId")
            .header("Authorization", "Bearer $otherToken")).andReturn().response.status)
        assertEquals(404, mvc.perform(get("/accounts")
            .header("Authorization", "Bearer $otherToken")).andReturn().response.status)
        val agentRead = mvc.perform(get("/accounts")
            .header("Authorization", "Bearer $agentToken")).andReturn().response
        assertEquals(403, agentRead.status)
        assertTrue(agentRead.contentAsString.isEmpty())
        assertEquals(403, mvc.perform(body(agentToken)).andReturn().response.status)

        val update = mvc.perform(put("/accounts/$accountId").header("Authorization", "Bearer $ownerToken")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"transferMoney":"BANK_SBP","entityValue":"987"}""")).andReturn().response
        assertEquals(200, update.status)
        assertTrue(update.contentAsString.contains("\"transferMoney\":\"BANK_SBP\""))
        assertEquals(200, mvc.perform(delete("/accounts/$accountId")
            .header("Authorization", "Bearer $ownerToken")).andReturn().response.status)
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM account WHERE id = ?", Int::class.java, accountId))
    }

    @Test
    fun `parallel creates for one owner cannot pass duplicate check together`() {
        val id = register.execute("account-race-${UUID.randomUUID()}@example.test", "secret", 30)
        val pool = Executors.newFixedThreadPool(2)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        try {
            val results = (1..2).map {
                pool.submit<Boolean> {
                    ready.countDown()
                    start.await()
                    try {
                        accounts.create(id, AccountDetails(TransferMoney.BANK_CARD, null, "same", null))
                        true
                    } catch (_: AccountAlreadyExists) {
                        false
                    }
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            assertEquals(setOf(true, false), results.map { it.get(10, TimeUnit.SECONDS) }.toSet())
            assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM account WHERE participant_id = ?", Int::class.java, id))
        } finally {
            pool.shutdownNow()
        }
    }

    private fun jwt(token: String, id: Long) = Jwt.withTokenValue(token).header("alg", "RS256")
        .claim("id", id).claim("login", "user$id").claim("role", "USER").build()

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

        @JvmStatic @AfterAll fun close() = postgres.close()
    }
}
