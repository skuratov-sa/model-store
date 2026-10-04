package com.model_store.modern.catalog.product.search

import com.amazonaws.services.s3.AmazonS3
import com.fasterxml.jackson.databind.ObjectMapper
import com.model_store.model.FindProductRequest
import com.model_store.model.dto.ProductDto
import io.jsonwebtoken.Jwts
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.core.io.ClassPathResource
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.web.context.WebApplicationContext
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import jakarta.servlet.Filter
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant
import java.util.Base64
import java.util.Date

@SpringBootTest(properties = ["app.public-key-path=keys/test_public_key.pem"])
@ActiveProfiles("modern")
class ProductSearchHttpTest {
    @Autowired lateinit var context: WebApplicationContext
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var json: ObjectMapper
    @MockitoBean lateinit var s3: AmazonS3

    @Test
    fun `MVC request and response retain search JSON and invalid enum is 400`() {
        val sellerId = jdbc.queryForObject(
            """INSERT INTO participant(login,mail,password,status,role,deadline_sending,deadline_payment)
               VALUES ('search14seller','search14@example.test','hash','ACTIVE','USER',3,7) RETURNING id""",
            Long::class.java,
        )!!
        val productId = jdbc.queryForObject(
            """INSERT INTO product(name,price,currency,participant_id,status,availability,count,used)
               VALUES ('Search14Product',100,'RUB',?,'ACTIVE','PURCHASABLE',3,false) RETURNING id""",
            Long::class.java, sellerId,
        )!!
        val mvc = MockMvcBuilders.webAppContextSetup(context).build()
        val response = mvc.perform(post("/products/find")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"name":"Search14Product","catalogFlags":["NON_PREORDER"],"includeAdult":true,"pageable":{"size":1,"sortBy":"DATE_DESC","lastId":0}}"""))
            .andExpect(status().isOk).andReturn().response.contentAsString
        val rows = json.readTree(response)
        assertEquals(1, rows.size())
        assertEquals(productId, rows[0]["id"].asLong())
        assertEquals("search14seller", rows[0]["sellerLogin"].asText())
        assertEquals("ACTIVE", rows[0]["status"].asText())
        assertTrue(rows[0]["categories"].isArray)
        assertTrue(rows[0].has("externalUrl"))
        val legacyFields = json.readTree(json.writeValueAsString(ProductDto.builder().id(productId).build()))
            .fieldNames().asSequence().toSet()
        assertEquals(legacyFields, rows[0].fieldNames().asSequence().toSet())
        mvc.perform(post("/products/find").contentType(MediaType.APPLICATION_JSON)
            .content("""{"catalogFlags":["INVALID"]}"""))
            .andExpect(status().isBadRequest)
        for (body in listOf(
            """{"name":"Search14Product"}""",
            """{"name":"Search14Product","pageable":{}}""",
            """{"name":"Search14Product","pageable":{"size":null}}""",
            """{"name":"Search14Product","priceRange":{"minPrice":null,"maxPrice":null}}""",
        )) {
            val legacyStatus = if (runCatching { json.readValue(body, FindProductRequest::class.java) }.isSuccess) 200 else 400
            val actualStatus = mvc.perform(post("/products/find")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().response.status
            assertEquals(legacyStatus, actualStatus, body)
        }
        val names = mvc.perform(post("/products/names/find").param("name", "Search14Product"))
            .andExpect(status().isOk).andReturn().response.contentAsString
        assertTrue(json.readTree(names).any { it.asText() == "Search14Product" })
    }

    @Test
    fun `optional security provides adult access only for valid access and agent tokens`() {
        val sellerId = jdbc.queryForObject(
            """INSERT INTO participant(login,mail,password,status,role,age,deadline_sending,deadline_payment)
               VALUES ('search14adult','search14adult@example.test','hash','ACTIVE','USER',18,3,7) RETURNING id""",
            Long::class.java,
        )!!
        val productId = jdbc.queryForObject(
            """INSERT INTO product(name,price,currency,participant_id,status,availability,count)
               VALUES ('Search14AdultProduct',100,'RUB',?,'ACTIVE','PURCHASABLE',3) RETURNING id""",
            Long::class.java, sellerId,
        )!!
        val adultCategory = jdbc.queryForObject("SELECT id FROM category WHERE slug='nsfw_adult'", Long::class.java)!!
        jdbc.update("INSERT INTO product_category(product_id,category_id) VALUES (?,?)", productId, adultCategory)
        val filter = context.getBean("springSecurityFilterChain") as Filter
        val builder = MockMvcBuilders.webAppContextSetup(context)
        builder.addFilters<DefaultMockMvcBuilder>(filter)
        val mvc = builder.build()
        val body = """{"name":"Search14AdultProduct"}"""
        fun search(bearer: String?): String {
            val request = post("/products/find").contentType(MediaType.APPLICATION_JSON).content(body)
            if (bearer != null) request.header("Authorization", "Bearer $bearer")
            return mvc.perform(request).andExpect(status().isOk).andReturn().response.contentAsString
        }
        assertEquals(0, json.readTree(search(null)).size())
        for (type in listOf("access", "agent_access")) {
            val result = search(signedToken(type, sellerId, issuedBy = if (type == "agent_access") "admin" else null))
            assertEquals(listOf(productId), json.readTree(result).map { it["id"].asLong() })
        }
        val minorId = jdbc.queryForObject(
            """INSERT INTO participant(login,mail,password,status,role,age,deadline_sending,deadline_payment)
               VALUES ('search14minor','search14minor@example.test','hash','ACTIVE','USER',17,3,7) RETURNING id""",
            Long::class.java,
        )!!
        assertEquals(0, json.readTree(search(signedToken("access", minorId))).size())
        for (unusable in listOf("not-a-jwt", signedToken("verify", sellerId),
            signedToken("refresh", sellerId), signedToken("agent_access", sellerId),
            signedToken("access", sellerId, expiresAt = Instant.now().minusSeconds(60)))) {
            assertEquals(0, json.readTree(search(unusable)).size())
        }
        val names = mvc.perform(post("/products/names/find").param("name", "Search14AdultProduct"))
            .andExpect(status().isOk).andReturn().response.contentAsString
        assertTrue(json.readTree(names).any { it.asText() == "Search14AdultProduct" })
        mvc.perform(post("/products/names/find").header("Authorization", "Bearer not-a-jwt")
            .param("name", "Search14AdultProduct")).andExpect(status().isUnauthorized)
    }

    companion object {
        private val postgres = EmbeddedPostgres.start()
        private val privateKey: PrivateKey by lazy {
            val pem = ClassPathResource("keys/test_private_key.pem").inputStream.bufferedReader().use { it.readText() }
                .replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "")
                .replace(Regex("\\s+"), "")
            KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)))
        }

        private fun signedToken(
            type: String, id: Long, issuedBy: String? = null,
            expiresAt: Instant = Instant.now().plusSeconds(3600),
        ): String {
            val builder = Jwts.builder().claim("id", id).claim("login", "search14")
                .claim("role", "USER").claim("type", type).expiration(Date.from(expiresAt))
            if (issuedBy != null) builder.claim("issuedBy", issuedBy)
            return builder.signWith(privateKey).compact()
        }

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

        @JvmStatic
        @AfterAll
        fun close() = postgres.close()
    }
}
