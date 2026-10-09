package com.model_store.modern.catalog.giveaway.`public`

import com.amazonaws.services.s3.AmazonS3
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
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant
import java.util.Base64
import java.util.Date

@SpringBootTest(properties = ["app.public-key-path=keys/test_public_key.pem", "app.private-key-path=keys/test_private_key.pem"])
@ActiveProfiles("modern")
class PublicGiveawayHttpTest {
    @Autowired lateinit var context: WebApplicationContext
    @Autowired lateinit var jdbc: JdbcTemplate
    @MockitoBean lateinit var s3: AmazonS3
    @MockitoBean lateinit var mail: JavaMailSender

    @Test
    fun `full security chain serves guest and authenticated giveaway reads`() {
        val owner = jdbc.queryForObject("""INSERT INTO participant(login,mail,password,status,role,deadline_sending,deadline_payment)
            VALUES ('giveaway21owner','giveaway21@example.test','hash','ACTIVE','USER',3,7) RETURNING id""", Long::class.java)!!
        val product = jdbc.queryForObject("""INSERT INTO product(name,description,price,currency,participant_id,
            status,availability,count,used,giveaway_enabled,giveaway_telegram_url,giveaway_start_at,
            giveaway_end_at,giveaway_winners_count,giveaway_rules,giveaway_home_text)
            VALUES ('Giveaway21','Prize',0,'RUB',?,'ACTIVE','GIVEAWAY',1,false,true,
                'https://t.me/prize',now()-interval '1 hour',now()+interval '1 day',2,'Rules','Home') RETURNING id""",
            Long::class.java, owner)!!
        val mvc = MockMvcBuilders.webAppContextSetup(context)
            .addFilters<DefaultMockMvcBuilder>(context.getBean("springSecurityFilterChain") as Filter).build()
        assertEquals(200, mvc.perform(get("/giveaways/active")).andReturn().response.status)
        assertEquals(200, mvc.perform(get("/giveaways/products/$product")).andReturn().response.status)
        val bearer = "Bearer ${token(owner)}"
        val active = mvc.perform(get("/giveaways/active").header("Authorization", bearer)).andReturn().response
        assertEquals(200, active.status)
        assertTrue(active.contentAsString.contains("\"productId\":$product"))
        assertEquals("noindex, nofollow", active.getHeader("X-Robots-Tag"))
        assertEquals(200, mvc.perform(get("/giveaways/products/$product")
            .header("Authorization", bearer)).andReturn().response.status)
        assertEquals(404, mvc.perform(get("/giveaways/products/${Long.MAX_VALUE}")
            .header("Authorization", bearer)).andReturn().response.status)
        assertEquals(404, mvc.perform(get("/giveaways/products/${Long.MIN_VALUE}")
            .header("Authorization", bearer)).andReturn().response.status)
        assertEquals(400, mvc.perform(get("/giveaways/products/not-a-number")
            .header("Authorization", bearer)).andReturn().response.status)
        assertEquals(401, mvc.perform(get("/giveaways/active")
            .header("Authorization", "Bearer ${token(owner, "refresh")}")).andReturn().response.status)
    }

    companion object {
        private val postgres = EmbeddedPostgres.start()
        private val privateKey: PrivateKey by lazy {
            val pem = ClassPathResource("keys/test_private_key.pem").inputStream.bufferedReader().use { it.readText() }
                .replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "")
                .replace(Regex("\\s+"), "")
            KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)))
        }
        private fun token(id: Long, type: String = "access") = Jwts.builder()
            .claim("id", id).claim("login", "giveaway21owner").claim("role", "USER").claim("type", type)
            .expiration(Date.from(Instant.now().plusSeconds(3600))).signWith(privateKey).compact()

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
