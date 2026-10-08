package com.model_store.modern.catalog.basket

import com.amazonaws.services.s3.AmazonS3
import com.fasterxml.jackson.databind.ObjectMapper
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import jakarta.servlet.Filter
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext

@SpringBootTest
@ActiveProfiles("modern")
class BasketHttpTest {
    @field:MockitoBean lateinit var s3: AmazonS3
    @field:MockitoBean lateinit var jwtDecoder: JwtDecoder
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var webContext: WebApplicationContext
    @Autowired lateinit var json: ObjectMapper

    @Test
    fun `HTTP routes use verified owner and legacy DTO and errors`() {
        val owner = participant("basketHttpOwner", 17)
        val other = participant("basketHttpOther", 25)
        val product = jdbc.queryForObject(
            """INSERT INTO product(name,price,currency,participant_id,status,availability,count,used)
               VALUES ('BasketHttpProduct',100,'RUB',?,'ACTIVE','PURCHASABLE',2,false) RETURNING id""",
            Long::class.java, other,
        )!!
        val adult = jdbc.queryForObject("SELECT id FROM category WHERE slug='nsfw_adult'", Long::class.java)!!
        jdbc.update("INSERT INTO product_category(product_id,category_id) VALUES (?,?)", product, adult)
        val ownerToken = token(owner, "access")
        val otherToken = token(other, "agent_access")
        val builder = MockMvcBuilders.webAppContextSetup(webContext)
        builder.addFilters<DefaultMockMvcBuilder>(webContext.getBean("springSecurityFilterChain") as Filter)
        val mvc = builder.build()
        assertEquals(401, mvc.perform(post("/basket").param("productId", "$product").param("count", "1")).andReturn().response.status)
        val added = mvc.perform(post("/basket").header("Authorization", ownerToken)
            .param("productId", "$product").param("count", "1")).andReturn().response
        assertEquals(200, added.status)
        assertEquals("", added.contentAsString)
        val duplicate = mvc.perform(post("/basket").header("Authorization", ownerToken)
            .param("productId", "$product").param("count", "1")).andReturn().response
        assertEquals(409, duplicate.status)
        assertEquals("PRODUCT_ALREADY_IN_BASKET", json.readTree(duplicate.contentAsString)["code"].asText())
        val invalid = mvc.perform(put("/basket").header("Authorization", ownerToken)
            .param("productId", "$product").param("count", "-1")).andReturn().response
        assertEquals(400, invalid.status)
        assertEquals("COUNT_INVALID", json.readTree(invalid.contentAsString)["code"].asText())
        assertEquals(400, mvc.perform(put("/basket").header("Authorization", ownerToken)
            .param("productId", "$product").param("count", "2147483648")).andReturn().response.status)
        val minor = mvc.perform(post("/basket/find").header("Authorization", ownerToken)
            .contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn().response
        assertEquals(200, minor.status)
        assertEquals(0, json.readTree(minor.contentAsString).size())
        val adultView = mvc.perform(post("/basket/find").header("Authorization", otherToken)
            .contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn().response
        assertEquals(200, adultView.status)
        assertEquals(0, json.readTree(adultView.contentAsString).size())
        jdbc.update("UPDATE participant SET age = 18 WHERE id = ?", owner)
        val view = mvc.perform(post("/basket/find").header("Authorization", ownerToken)
            .contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn().response
        assertEquals(200, view.status)
        val row = json.readTree(view.contentAsString).single()
        assertEquals(product, row["product"]["id"].asLong())
        assertEquals(1, row["count"].asInt())
        assertEquals(2, row["availableCount"].asInt())
        assertTrue(row["enoughStock"].asBoolean())
        assertEquals(setOf("product", "count", "availableCount", "enoughStock"), row.fieldNames().asSequence().toSet())
        val foreign = mvc.perform(put("/basket").header("Authorization", otherToken)
            .param("productId", "$product").param("count", "2")).andReturn().response
        assertEquals(404, foreign.status)
        assertEquals("BASKET_ITEM_NOT_FOUND", json.readTree(foreign.contentAsString)["code"].asText())
        assertEquals(200, mvc.perform(delete("/basket").header("Authorization", otherToken)
            .param("productId", "$product")).andReturn().response.status)
        assertEquals(1, jdbc.queryForObject("SELECT count FROM product_basket WHERE participant_id=? AND product_id=?", Int::class.java, owner, product))
        assertEquals(200, mvc.perform(delete("/basket").header("Authorization", ownerToken)
            .param("productId", "$product")).andReturn().response.status)
    }

    private fun participant(login: String, age: Int): Long = jdbc.queryForObject(
        """INSERT INTO participant(login,mail,password,status,role,age,deadline_sending,deadline_payment)
           VALUES (?,?, 'hash','ACTIVE','USER',?,3,7) RETURNING id""",
        Long::class.java, login, "$login@example.test", age,
    )!!

    private fun token(id: Long, type: String): String {
        val value = "basket-$type-$id"
        Mockito.`when`(jwtDecoder.decode(value)).thenReturn(
            Jwt.withTokenValue(value).header("alg", "RS256")
                .claim("id", id).claim("role", "USER").claim("login", "basket")
                .claim("type", type).build(),
        )
        return "Bearer $value"
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
