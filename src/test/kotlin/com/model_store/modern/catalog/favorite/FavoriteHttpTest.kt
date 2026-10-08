package com.model_store.modern.catalog.favorite

import com.amazonaws.services.s3.AmazonS3
import com.fasterxml.jackson.databind.ObjectMapper
import com.model_store.modern.catalog.favorite.application.FavoriteUseCases
import com.model_store.model.dto.ProductDto
import io.jsonwebtoken.Jwts
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import jakarta.servlet.Filter
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.io.ClassPathResource
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.web.context.WebApplicationContext
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.concurrent.CompletableFuture

@SpringBootTest(properties = ["app.public-key-path=keys/test_public_key.pem"])
@ActiveProfiles("modern")
class FavoriteHttpTest {
    @Autowired lateinit var context: WebApplicationContext
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var json: ObjectMapper
    @Autowired lateinit var favorites: FavoriteUseCases
    @MockitoBean lateinit var s3: AmazonS3

    @Test
    fun `routes use verified owner and preserve empty body and JSON`() {
        val owner = participant("favorite17owner", 18)
        val other = participant("favorite17other", 17)
        val product = product("Favorite17Visible", owner)
        val mvc = MockMvcBuilders.webAppContextSetup(context)
            .addFilters<DefaultMockMvcBuilder>(context.getBean("springSecurityFilterChain") as Filter).build()
        val token = token(owner)
        mvc.perform(post("/favorites").param("productId", product.toString()))
            .andExpect(status().isUnauthorized)
        repeat(2) {
            val response = mvc.perform(post("/favorites").header("Authorization", "Bearer $token")
                .param("productId", product.toString())).andExpect(status().isOk).andReturn().response
            assertEquals("", response.contentAsString)
        }
        assertEquals(1, count(owner, product))
        val found = mvc.perform(post("/favorites/find").header("Authorization", "Bearer $token")
            .contentType(MediaType.APPLICATION_JSON).content("""{"name":"Favorite17Visible"}"""))
            .andExpect(status().isOk).andReturn().response.contentAsString
        val row = json.readTree(found).single()
        assertEquals(product, row["id"].asLong())
        assertEquals("favorite17owner", row["sellerLogin"].asText())
        assertTrue(row["categories"].isArray)
        assertTrue(row.has("externalUrl"))
        val legacyFields = json.readTree(json.writeValueAsString(ProductDto.builder().id(product).build()))
            .fieldNames().asSequence().toSet()
        assertEquals(legacyFields, row.fieldNames().asSequence().toSet())
        assertEquals(0, json.readTree(mvc.perform(post("/favorites/find")
            .header("Authorization", "Bearer ${token(other)}")
            .contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn().response.contentAsString).size())
        mvc.perform(delete("/favorites").header("Authorization", "Bearer ${token(other)}")
            .param("productId", product.toString())).andExpect(status().isOk)
        assertEquals(1, count(owner, product))
        repeat(2) {
            mvc.perform(delete("/favorites").header("Authorization", "Bearer $token")
                .param("productId", product.toString())).andExpect(status().isOk)
        }
        assertEquals(0, count(owner, product))
    }

    @Test
    fun `blocked owner inactive product giveaway and adult visibility`() {
        val owner = participant("favorite17adult", 17)
        val blocked = participant("favorite17blocked", 18)
        val seller = participant("favorite17seller", 18)
        val visible = product("Favorite17Search", seller)
        val giveaway = product("Favorite17Giveaway", seller, "GIVEAWAY")
        val inactive = product("Favorite17Inactive", seller, status = "BLOCKED")
        val deleted = product("Favorite17Deleted", seller, status = "DELETED")
        val adultCategory = jdbc.queryForObject("SELECT id FROM category WHERE slug = 'nsfw_adult'", Long::class.java)!!
        jdbc.update("INSERT INTO product_category(product_id,category_id) VALUES (?,?)", visible, adultCategory)
        val mvc = MockMvcBuilders.webAppContextSetup(context)
            .addFilters<DefaultMockMvcBuilder>(context.getBean("springSecurityFilterChain") as Filter).build()
        val ownerToken = token(owner)
        for (id in listOf(giveaway, inactive, deleted, Long.MAX_VALUE)) {
            val error = mvc.perform(post("/favorites").header("Authorization", "Bearer $ownerToken")
                .param("productId", id.toString())).andExpect(status().isNotFound)
                .andReturn().response.contentAsString
            assertEquals("NOT_FOUND", json.readTree(error)["code"].asText())
            assertEquals("Product or participant not found", json.readTree(error)["message"].asText())
        }
        mvc.perform(post("/favorites").header("Authorization", "Bearer $ownerToken")
            .param("productId", visible.toString())).andExpect(status().isOk)
        val search = post("/favorites/find").header("Authorization", "Bearer $ownerToken")
            .contentType(MediaType.APPLICATION_JSON).content("""{"name":"Favorite17Search"}""")
        assertEquals(0, json.readTree(mvc.perform(search).andReturn().response.contentAsString).size())
        jdbc.update("UPDATE participant SET age = 18 WHERE id = ?", owner)
        val adultSearch = post("/favorites/find").header("Authorization", "Bearer $ownerToken")
            .contentType(MediaType.APPLICATION_JSON).content("""{"name":"Favorite17Search"}""")
        assertEquals(1, json.readTree(mvc.perform(adultSearch).andReturn().response.contentAsString).size())
        val agentSearch = post("/favorites/find").header("Authorization", "Bearer ${token(owner, "agent_access", "admin")}")
            .contentType(MediaType.APPLICATION_JSON).content("""{"name":"Favorite17Search"}""")
        assertEquals(1, json.readTree(mvc.perform(agentSearch).andExpect(status().isOk)
            .andReturn().response.contentAsString).size())
        mvc.perform(post("/favorites/find")
            .header("Authorization", "Bearer ${token(owner, "agent_access")}")
            .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isUnauthorized)
        jdbc.update("UPDATE participant SET status = 'BLOCKED' WHERE id = ?", blocked)
        mvc.perform(post("/favorites").header("Authorization", "Bearer ${token(blocked)}")
            .param("productId", visible.toString())).andExpect(status().isNotFound)
    }

    @Test
    fun `concurrent adds leave one favorite without schema uniqueness`() {
        val owner = participant("favorite17race", 18)
        val seller = participant("favorite17raceSeller", 18)
        val product = product("Favorite17Race", seller)
        val start = java.util.concurrent.CountDownLatch(1)
        val tasks = (1..8).map {
            CompletableFuture.runAsync { start.await(); favorites.add(owner, product) }
        }
        start.countDown()
        tasks.forEach { it.join() }
        assertEquals(1, count(owner, product))
        val mixedStart = java.util.concurrent.CountDownLatch(1)
        val mixed = (1..16).map { index ->
            CompletableFuture.runAsync {
                mixedStart.await()
                if (index % 2 == 0) favorites.remove(owner, product) else favorites.add(owner, product)
            }
        }
        mixedStart.countDown()
        mixed.forEach { it.join() }
        assertTrue(count(owner, product)!! <= 1)
        favorites.remove(owner, product)
        assertEquals(0, count(owner, product))

        // Old deployments can already contain duplicates because the schema has no pair constraint.
        jdbc.update("INSERT INTO product_favorite(participant_id,product_id) VALUES (?,?),(?,?)",
            owner, product, owner, product)
        assertEquals(2, count(owner, product))
        favorites.remove(owner, product)
        assertEquals(0, count(owner, product))
    }

    private fun participant(login: String, age: Int): Long = jdbc.queryForObject(
        """INSERT INTO participant(login,mail,password,status,role,age,deadline_sending,deadline_payment)
           VALUES (?,?, 'hash','ACTIVE','USER',?,3,7) RETURNING id""",
        Long::class.java, login, "$login@example.test", age,
    )!!

    private fun product(name: String, seller: Long, availability: String = "PURCHASABLE", status: String = "ACTIVE"): Long =
        jdbc.queryForObject(
            """INSERT INTO product(name,price,currency,participant_id,status,availability,count,used)
               VALUES (?,100,'RUB',?,?::product_status,?::product_availability,3,false) RETURNING id""",
            Long::class.java, name, seller, status, availability,
        )!!

    private fun count(owner: Long, product: Long) = jdbc.queryForObject(
        "SELECT count(*) FROM product_favorite WHERE participant_id = ? AND product_id = ?",
        Int::class.java, owner, product,
    )

    companion object {
        private val postgres = EmbeddedPostgres.start()
        private val privateKey: PrivateKey by lazy {
            val pem = ClassPathResource("keys/test_private_key.pem").inputStream.bufferedReader().use { it.readText() }
                .replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "")
                .replace(Regex("\\s+"), "")
            KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)))
        }

        private fun token(id: Long, type: String = "access", issuedBy: String? = null): String {
            val builder = Jwts.builder().claim("id", id).claim("login", "favorite17")
                .claim("role", "USER").claim("type", type)
                .expiration(Date.from(Instant.now().plusSeconds(3600)))
            if (issuedBy != null) builder.claim("issuedBy", issuedBy)
            return builder.signWith(privateKey).compact()
        }

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
