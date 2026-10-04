package com.model_store.modern.seller

import com.amazonaws.services.s3.AmazonS3
import com.model_store.modern.seller.social.application.SocialNetworkUseCases
import com.model_store.modern.seller.social.domain.*
import com.model_store.modern.seller.transfer.application.TransferUseCases
import com.model_store.modern.seller.transfer.domain.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import jakarta.persistence.EntityManager
import jakarta.servlet.Filter
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
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
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.context.WebApplicationContext
import org.mockito.Mockito
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootTest
@ActiveProfiles("modern")
class TransferSocialIntegrationTest {
    @field:MockitoBean lateinit var s3: AmazonS3
    @field:MockitoBean lateinit var jwtDecoder: JwtDecoder
    @Autowired lateinit var transfers: TransferUseCases
    @Autowired lateinit var socials: SocialNetworkUseCases
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var em: EntityManager
    @Autowired lateinit var webContext: WebApplicationContext

    @Test
    @Transactional
    fun `transfer CRUD preserves status types and ownership`() {
        val owner = participant()
        val other = participant()
        val fields = TransferFields(ShippingMethod.RUSSIAN_POST, 150, TransferCurrency.RUB)
        transfers.create(owner, fields)
        val created = transfers.owned(owner).single()
        assertEquals(TransferStatus.ACTIVE, created.status)
        assertTrue(transfers.owned(owner).single().currency == TransferCurrency.RUB)
        assertThrows(TransferAlreadyExists::class.java) { transfers.create(owner, fields) }
        assertThrows(TransferNotFound::class.java) { transfers.owned(other) }
        assertThrows(TransferNotFound::class.java) { transfers.update(other, created.id, fields) }
        assertThrows(TransferNotFound::class.java) { transfers.delete(other, created.id) }
        assertEquals(150, transfers.owned(owner).single().price)
        val changed = transfers.update(owner, created.id, fields.copy(price = 200))
        assertEquals(200, changed.price)
        transfers.delete(owner, created.id)
        em.flush()
        assertEquals("DELETED", jdbc.queryForObject("SELECT status::text FROM transfer WHERE id = ?", String::class.java, created.id))
        assertThrows(TransferNotFound::class.java) { transfers.owned(owner) }
        assertThrows(TransferNotFound::class.java) { transfers.update(owner, created.id, fields) }
        transfers.delete(owner, created.id)
        transfers.create(owner, fields)
        assertNotEquals(created.id, transfers.owned(owner).single().id)
    }

    @Test
    @Transactional
    fun `social CRUD rejects duplicates and foreign access`() {
        val owner = participant()
        val other = participant()
        val fields = SocialNetworkFields(SocialNetworkType.TELEGRAM, "seller")
        socials.create(owner, fields)
        val created = socials.owned(owner).single()
        assertEquals("seller", created.login)
        assertThrows(SocialNetworkAlreadyExists::class.java) { socials.create(owner, fields) }
        assertThrows(SocialNetworkNotFound::class.java) { socials.owned(other) }
        assertThrows(SocialNetworkNotFound::class.java) { socials.update(other, created.id, fields) }
        assertThrows(SocialNetworkNotFound::class.java) { socials.delete(other, created.id) }
        assertEquals("changed", socials.update(owner, created.id, fields.copy(login = "changed")).login)
        socials.delete(owner, created.id)
        em.flush()
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM social_network WHERE id = ?", Long::class.java, created.id))
        assertThrows(SocialNetworkNotFound::class.java) { socials.owned(owner) }
    }

    @Test
    fun `concurrent duplicate creations leave one row per type`() {
        val owner = participant()
        val pool = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        try {
            val transferAttempts = (1..2).map {
                pool.submit<Result<Unit>> {
                    start.await()
                    runCatching { transfers.create(owner, TransferFields(ShippingMethod.FREE_POST, 0, TransferCurrency.RUB)) }
                }
            }
            val socialAttempts = (1..2).map {
                pool.submit<Result<Unit>> {
                    start.await()
                    runCatching { socials.create(owner, SocialNetworkFields(SocialNetworkType.VK, "seller")) }
                }
            }
            start.countDown()
            val transferResults = transferAttempts.map { it.get(20, TimeUnit.SECONDS) }
            val socialResults = socialAttempts.map { it.get(20, TimeUnit.SECONDS) }
            assertEquals(1, transferResults.count { it.isSuccess })
            assertEquals(1, socialResults.count { it.isSuccess })
            assertTrue(transferResults.any { it.exceptionOrNull() is TransferAlreadyExists })
            assertTrue(socialResults.any { it.exceptionOrNull() is SocialNetworkAlreadyExists })
            assertEquals(1, transfers.owned(owner).size)
            assertEquals(1, socials.owned(owner).size)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    @Transactional
    fun `HTTP routes preserve JSON status and hide foreign rows`() {
        val owner = participant()
        val other = participant()
        val agent = participant(isAgent = true)
        fun token(id: Long, role: String = "USER"): String {
            val value = "seller-method-$id"
            Mockito.`when`(jwtDecoder.decode(value)).thenReturn(
                Jwt.withTokenValue(value).header("alg", "RS256")
                    .claim("id", id).claim("role", role).claim("login", "user$id").build(),
            )
            return "Bearer $value"
        }
        val ownerToken = token(owner)
        val otherToken = token(other)
        val agentToken = token(agent)
        val builder = MockMvcBuilders.webAppContextSetup(webContext)
        builder.addFilters<DefaultMockMvcBuilder>(webContext.getBean("springSecurityFilterChain") as Filter)
        val mvc = builder.build()
        assertEquals(401, mvc.perform(get("/transfer")).andReturn().response.status)
        assertEquals(401, mvc.perform(get("/social-networks")).andReturn().response.status)
        assertEquals(404, mvc.perform(get("/transfer").header("Authorization", ownerToken)).andReturn().response.status)
        assertEquals(404, mvc.perform(get("/social-networks").header("Authorization", ownerToken)).andReturn().response.status)

        assertEquals(200, mvc.perform(post("/transfer").header("Authorization", ownerToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"sending":"RUSSIAN_POST","price":150,"currency":"RUB"}""")).andReturn().response.status)
        assertEquals(200, mvc.perform(post("/social-networks").header("Authorization", ownerToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"type":"TELEGRAM","login":"seller"}""")).andReturn().response.status)
        val transfer = transfers.owned(owner).single()
        val social = socials.owned(owner).single()
        val transferJson = mvc.perform(get("/transfer").header("Authorization", ownerToken)).andReturn().response.contentAsString
        assertTrue(transferJson.contains("\"status\":\"ACTIVE\""))
        assertTrue(transferJson.contains("\"sending\":\"RUSSIAN_POST\""))
        assertTrue(transferJson.contains("\"participantId\":$owner"))
        val socialJson = mvc.perform(get("/social-networks").header("Authorization", ownerToken)).andReturn().response.contentAsString
        assertTrue(socialJson.contains("\"type\":\"TELEGRAM\""))
        assertTrue(socialJson.contains("\"login\":\"seller\""))
        assertEquals(404, mvc.perform(get("/transfer").header("Authorization", otherToken)).andReturn().response.status)
        assertEquals(404, mvc.perform(get("/social-networks").header("Authorization", otherToken)).andReturn().response.status)
        assertEquals(404, mvc.perform(get("/transfer").header("Authorization", agentToken)).andReturn().response.status)
        assertEquals(404, mvc.perform(get("/social-networks").header("Authorization", agentToken)).andReturn().response.status)
        assertEquals(404, mvc.perform(put("/transfer/${transfer.id}").header("Authorization", otherToken)
            .contentType(MediaType.APPLICATION_JSON).content("""{"sending":"FREE_POST","price":0,"currency":"RUB"}"""))
            .andReturn().response.status)
        assertEquals(404, mvc.perform(delete("/transfer/${transfer.id}").header("Authorization", otherToken))
            .andReturn().response.status)
        assertEquals(404, mvc.perform(put("/social-networks/${social.id}").header("Authorization", otherToken)
            .contentType(MediaType.APPLICATION_JSON).content("""{"type":"VK","login":"stolen"}"""))
            .andReturn().response.status)
        assertEquals(404, mvc.perform(delete("/social-networks/${social.id}").header("Authorization", otherToken))
            .andReturn().response.status)
        assertEquals(409, mvc.perform(post("/transfer").header("Authorization", ownerToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"sending":"RUSSIAN_POST","price":150,"currency":"RUB"}""")).andReturn().response.status)
        assertEquals(409, mvc.perform(post("/social-networks").header("Authorization", ownerToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"type":"TELEGRAM","login":"other"}""")).andReturn().response.status)
        val updatedTransfer = mvc.perform(put("/transfer/${transfer.id}").header("Authorization", ownerToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"sending":"FREE_POST","price":0,"currency":"RUB"}""")).andReturn().response
        assertEquals(200, updatedTransfer.status)
        assertTrue(updatedTransfer.contentAsString.contains("\"sending\":\"FREE_POST\""))
        val updatedSocial = mvc.perform(put("/social-networks/${social.id}").header("Authorization", ownerToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"type":"VK","login":"new"}""")).andReturn().response
        assertEquals(200, updatedSocial.status)
        assertTrue(updatedSocial.contentAsString.contains("\"login\":\"new\""))
        assertEquals(200, mvc.perform(delete("/transfer/${transfer.id}").header("Authorization", ownerToken))
            .andReturn().response.status)
        assertEquals(200, mvc.perform(delete("/social-networks/${social.id}").header("Authorization", ownerToken))
            .andReturn().response.status)
        assertEquals(404, mvc.perform(get("/transfer").header("Authorization", ownerToken)).andReturn().response.status)
        assertEquals(404, mvc.perform(get("/social-networks").header("Authorization", ownerToken)).andReturn().response.status)
        assertEquals(400, mvc.perform(post("/transfer").header("Authorization", ownerToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"sending":"FREE_POST","price":0,"currency":"CREATE"}""")).andReturn().response.status)
    }

    private fun participant(isAgent: Boolean = false): Long = jdbc.queryForObject(
        "INSERT INTO participant(password, status, is_agent) VALUES ('test', 'ACTIVE', ?) RETURNING id",
        Long::class.java, isAgent,
    )!!

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
