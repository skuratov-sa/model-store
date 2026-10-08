package com.model_store.modern.catalog.product.detail

import com.amazonaws.services.s3.AmazonS3
import com.fasterxml.jackson.databind.ObjectMapper
import com.model_store.model.dto.GetProductResponse
import com.model_store.model.dto.ProductDto
import io.jsonwebtoken.Jwts
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import jakarta.servlet.Filter
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.io.ClassPathResource
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant
import java.util.Base64
import java.util.Date

@SpringBootTest(properties = ["app.public-key-path=keys/test_public_key.pem"])
@ActiveProfiles("modern")
class ProductDetailHttpTest {
    @Autowired lateinit var context: WebApplicationContext
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var json: ObjectMapper
    @Autowired lateinit var flyway: Flyway
    @MockitoBean lateinit var s3: AmazonS3
    @MockitoBean lateinit var mail: JavaMailSender

    @Test
    fun `real MVC security and Flyway preserve public detail and owner scoped my products`() {
        assertTrue(flyway.info().applied().isNotEmpty())
        val owner = participant("detail15owner", "USER")
        val other = participant("detail15other", "USER")
        val mine = product("Detail15Mine", owner)
        val foreign = product("Detail15Foreign", other)
        val blocked = product("Detail15Blocked", owner, status = "BLOCKED")
        val giveaway = product("Detail15Giveaway", owner, availability = "GIVEAWAY")
        val category = jdbc.queryForObject("INSERT INTO category(name) VALUES ('Detail15Category') RETURNING id", Long::class.java)!!
        jdbc.update("INSERT INTO product_category(product_id,category_id) VALUES (?,?)", mine, category)
        val image = jdbc.queryForObject(
            "INSERT INTO image(tag,status,entity_id) VALUES ('PRODUCT','ACTIVE',?) RETURNING id", Long::class.java, mine,
        )!!
        jdbc.update("INSERT INTO seller_rating(seller_id,average_rating,total_reviews) VALUES (?,4.5,2)", owner)
        val mvc = MockMvcBuilders.webAppContextSetup(context)
            .addFilters<DefaultMockMvcBuilder>(context.getBean("springSecurityFilterChain") as Filter).build()

        val card = mvc.perform(get("/product/$mine")).andReturn().response
        assertEquals(200, card.status)
        val cardJson = json.readTree(card.contentAsString)
        assertEquals(mine, cardJson["id"].asLong())
        assertEquals("detail15owner", cardJson["sellerLogin"].asText())
        assertEquals(4.5, cardJson["sellerRating"].asDouble(), 0.01)
        assertEquals(category, cardJson["categories"][0]["id"].asLong())
        assertEquals(image, cardJson["imageIds"][0].asLong())
        assertTrue(cardJson["reviews"].isArray)
        assertTrue(cardJson["externalUrl"].isNull)
        assertEquals(setOf("id", "name"), cardJson["categories"][0].fieldNames().asSequence().toSet())
        assertEquals(fields(GetProductResponse.builder().build()), cardJson.fieldNames().asSequence().toSet())
        assertEquals(404, mvc.perform(get("/product/${Long.MAX_VALUE}")).andReturn().response.status)
        assertEquals(404, mvc.perform(get("/product/$blocked")).andReturn().response.status)
        assertEquals(404, mvc.perform(get("/product/$giveaway")).andReturn().response.status)
        assertEquals(400, mvc.perform(get("/product/not-a-number")).andReturn().response.status)

        // The public matcher covers one GET segment only, never neighboring operations or paths.
        assertEquals(401, mvc.perform(get("/product/$mine/extra")).andReturn().response.status)
        assertEquals(401, mvc.perform(put("/product/$mine").contentType(MediaType.APPLICATION_JSON)
            .content("{}")).andReturn().response.status)
        assertEquals(401, mvc.perform(delete("/product/$mine")).andReturn().response.status)
        // A client-supplied owner ID is ignored; only the verified Actor controls ownership.
        val body = """{"participantId":$other,"pageable":{"size":20}}"""
        assertEquals(401, mvc.perform(post("/products/my").contentType(MediaType.APPLICATION_JSON)
            .content(body)).andReturn().response.status)
        assertEquals(401, mvc.perform(post("/products/my").header("Authorization", "Bearer ${token(owner, "refresh")}")
            .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().response.status)
        for (invalid in listOf(token(owner, "verify"), token(owner, "agent_access"))) {
            assertEquals(401, mvc.perform(post("/products/my").header("Authorization", "Bearer $invalid")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().response.status)
        }
        val own = mvc.perform(post("/products/my").header("Authorization", "Bearer ${token(owner)}")
            .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().response
        assertEquals(200, own.status)
        val rows = json.readTree(own.contentAsString)
        assertEquals(setOf(mine, blocked), rows.map { it["id"].asLong() }.toSet())
        assertTrue(rows.none { it["id"].asLong() == foreign || it["id"].asLong() == giveaway })
        assertEquals(fields(ProductDto.builder().build()), rows[0].fieldNames().asSequence().toSet())
        assertTrue(rows[0]["externalUrl"].isNull)
        val otherResponse = mvc.perform(post("/products/my")
            .header("Authorization", "Bearer ${token(other)}")
            .contentType(MediaType.APPLICATION_JSON).content(body))
            .andReturn().response
        assertEquals(200, otherResponse.status)
        val otherRows = json.readTree(otherResponse.contentAsString)
        assertEquals(listOf(foreign), otherRows.map { it["id"].asLong() })
        assertEquals(other, otherRows.single()["sellerId"].asLong())
    }

    private fun fields(value: Any): Set<String> = json.readTree(json.writeValueAsString(value))
        .fieldNames().asSequence().toSet()

    private fun participant(login: String, role: String): Long = jdbc.queryForObject(
        """INSERT INTO participant(login,mail,password,status,role,deadline_sending,deadline_payment)
           VALUES (?,?,'hash','ACTIVE',?::participant_role,3,7) RETURNING id""",
        Long::class.java, login, "$login@example.test", role,
    )!!

    private fun product(name: String, owner: Long, status: String = "ACTIVE", availability: String = "PURCHASABLE"): Long =
        jdbc.queryForObject(
            """INSERT INTO product(name,description,price,currency,participant_id,status,availability,count,used)
               VALUES (?,'Description',100,'RUB',?,?::product_status,?::product_availability,3,false) RETURNING id""",
            Long::class.java, name, owner, status, availability,
        )!!

    companion object {
        private val postgres = EmbeddedPostgres.start()
        private val privateKey: PrivateKey by lazy {
            val pem = ClassPathResource("keys/test_private_key.pem").inputStream.bufferedReader().use { it.readText() }
                .replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "")
                .replace(Regex("\\s+"), "")
            KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)))
        }

        private fun token(id: Long, type: String = "access"): String = Jwts.builder()
            .claim("id", id).claim("login", "detail15").claim("role", "USER").claim("type", type)
            .expiration(Date.from(Instant.now().plusSeconds(3600)))
            .signWith(privateKey).compact()

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
