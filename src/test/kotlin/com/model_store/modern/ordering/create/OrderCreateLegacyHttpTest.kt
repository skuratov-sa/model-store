package com.model_store.modern.ordering.create

import com.amazonaws.services.s3.AmazonS3
import com.fasterxml.jackson.databind.ObjectMapper
import io.jsonwebtoken.Jwts
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.Date

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["app.public-key-path=keys/test_public_key.pem", "app.private-key-path=keys/test_private_key.pem"])
@ActiveProfiles("legacy")
class OrderCreateLegacyHttpTest {
    @Value("\${local.server.port}") private var port: Int = 0
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var json: ObjectMapper
    @MockitoBean lateinit var s3: AmazonS3
    @MockitoBean lateinit var mail: JavaMailSender
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()

    @Test
    fun `legacy preparation and BOOKED baseline`() {
        val buyer = participant("legacy24buyer")
        val seller = participant("legacy24seller")
        val address = jdbc.queryForObject("INSERT INTO address(country,city) VALUES ('RU','Moscow') RETURNING id", Long::class.java)!!
        jdbc.update("INSERT INTO participant_address(participant_id,address_id) VALUES (?,?)", buyer, address)
        val transfer = jdbc.queryForObject(
            "INSERT INTO transfer(sending,price,currency,participant_id) VALUES ('PRODUCT_PICKUP',0,'RUB',?) RETURNING id",
            Long::class.java, seller)!!
        val product = jdbc.queryForObject(
            """INSERT INTO product(name,price,currency,participant_id,status,availability,count,used)
               VALUES ('Legacy24 product',23.5,'RUB',?,'ACTIVE','PURCHASABLE',2,false) RETURNING id""",
            Long::class.java, seller)!!
        val prepared = request("GET", "/order?productId=$product", null, buyer)
        assertEquals(200, prepared.statusCode(), prepared.body())
        val data = json.readTree(prepared.body())
        assertEquals(setOf("addresses", "sellerTransfers"), data.fieldNames().asSequence().toSet())
        assertEquals(address, data["addresses"][0]["id"].asLong())
        assertEquals(transfer, data["sellerTransfers"][0]["id"].asLong())
        assertEquals(setOf("id", "country", "city", "street", "houseNumber", "apartmentNumber",
            "index", "status", "fullAddress"), data["addresses"][0].fieldNames().asSequence().toSet())
        assertTrue(data["addresses"][0]["street"].isNull)
        assertEquals(setOf("id", "sending", "price", "currency", "participantId", "status"),
            data["sellerTransfers"][0].fieldNames().asSequence().toSet())
        val body = """[{"productId":$product,"count":1,"addressId":$address,"transferId":$transfer,"comment":"new"}]"""
        val booked = request("POST", "/order/BOOKED", body, buyer)
        assertEquals(200, booked.statusCode(), booked.body())
        val id = json.readTree(booked.body())[0].asLong()
        assertEquals("BOOKED", jdbc.queryForObject("SELECT status::text FROM \"order\" WHERE id=?", String::class.java, id))
        assertEquals(23.5f, jdbc.queryForObject("SELECT product_unit_price FROM \"order\" WHERE id=?", Float::class.java, id))
    }

    @Test
    fun `legacy HTTP permits signed blocked and deleted buyers and agent seller`() {
        val seller = participant("legacy24statusSeller")
        val blocked = participant("legacy24blockedBuyer")
        val deleted = participant("legacy24deletedBuyer")
        val agent = participant("legacy24agentSeller")
        jdbc.update("UPDATE participant SET status='BLOCKED' WHERE id=?", blocked)
        jdbc.update("UPDATE participant SET status='DELETED' WHERE id=?", deleted)
        jdbc.update("UPDATE participant SET is_agent=true WHERE id=?", agent)
        val transfer = jdbc.queryForObject(
            "INSERT INTO transfer(sending,price,currency,participant_id) VALUES ('PRODUCT_PICKUP',0,'RUB',?) RETURNING id",
            Long::class.java, seller)!!
        val agentTransfer = jdbc.queryForObject(
            "INSERT INTO transfer(sending,price,currency,participant_id) VALUES ('PRODUCT_PICKUP',0,'RUB',?) RETURNING id",
            Long::class.java, agent)!!
        val ordinary = product("Legacy24 status product", seller, 2)
        val agentProduct = product("Legacy24 agent product", agent, 1)
        for (buyer in listOf(blocked, deleted)) {
            val address = address(buyer)
            val body = """[{"productId":$ordinary,"count":1,"addressId":$address,"transferId":$transfer}]"""
            val response = request("POST", "/order/BOOKED", body, buyer)
            assertEquals(200, response.statusCode(), response.body())
        }
        val address = address(blocked)
        val prepared = request("GET", "/order?productId=$agentProduct", null, blocked)
        assertEquals(200, prepared.statusCode(), prepared.body())
        assertEquals(agentTransfer, json.readTree(prepared.body())["sellerTransfers"][0]["id"].asLong())
        val agentBody = """[{"productId":$agentProduct,"count":1,"addressId":$address,"transferId":$agentTransfer}]"""
        val agentBooked = request("POST", "/order/BOOKED", agentBody, blocked)
        assertEquals(200, agentBooked.statusCode(), agentBooked.body())
    }

    @Test
    fun `legacy own product error message and preparation`() {
        val owner = participant("legacy24own")
        val address = address(owner)
        val transfer = jdbc.queryForObject(
            "INSERT INTO transfer(sending,price,currency,participant_id) VALUES ('PRODUCT_PICKUP',0,'RUB',?) RETURNING id",
            Long::class.java, owner)!!
        val own = product("Legacy24 own product", owner, 1)
        assertEquals(200, request("GET", "/order?productId=$own", null, owner).statusCode())
        val body = """[{"productId":$own,"count":1,"addressId":$address,"transferId":$transfer}]"""
        val error = request("POST", "/order/BOOKED", body, owner)
        assertEquals(400, error.statusCode(), error.body())
        assertEquals("OWN_PRODUCT_ORDER_FORBIDDEN", json.readTree(error.body())["code"].asText())
        assertEquals("Нельзя оформить заказ на собственный товар", json.readTree(error.body())["message"].asText())
    }

    @Test
    fun `legacy preparation requires active seller transfer but permits no buyer addresses`() {
        val buyer = participant("legacy24emptyBuyer")
        val seller = participant("legacy24emptySeller")
        val item = product("Legacy24 empty product", seller, 2)
        val withoutTransfer = request("GET", "/order?productId=$item", null, buyer)
        assertEquals(404, withoutTransfer.statusCode(), withoutTransfer.body())
        assertEquals("TRANSFER_NOT_FOUND", json.readTree(withoutTransfer.body())["code"].asText())
        val transfer = jdbc.queryForObject(
            "INSERT INTO transfer(sending,price,currency,participant_id) VALUES ('PRODUCT_PICKUP',0,'RUB',?) RETURNING id",
            Long::class.java, seller)!!
        val withoutAddress = request("GET", "/order?productId=$item", null, buyer)
        assertEquals(200, withoutAddress.statusCode(), withoutAddress.body())
        assertEquals(0, json.readTree(withoutAddress.body())["addresses"].size())
        jdbc.update("UPDATE transfer SET status='DELETED' WHERE id=?", transfer)
        val deleted = request("GET", "/order?productId=$item", null, buyer)
        assertEquals(404, deleted.statusCode(), deleted.body())
        val address = address(buyer)
        val body = """[{"productId":$item,"count":1,"addressId":$address,"transferId":$transfer}]"""
        val noActive = request("POST", "/order/BOOKED", body, buyer)
        assertEquals(404, noActive.statusCode(), noActive.body())
        assertEquals("TRANSFER_NOT_FOUND", json.readTree(noActive.body())["code"].asText())
        jdbc.update("INSERT INTO transfer(sending,price,currency,participant_id) VALUES ('RUSSIAN_POST',0,'RUB',?)", seller)
        val invalid = request("POST", "/order/BOOKED", body, buyer)
        assertEquals(400, invalid.statusCode(), invalid.body())
        assertEquals("INVALID_TRANSFER", json.readTree(invalid.body())["code"].asText())
    }

    private fun request(method: String, path: String, body: String?, buyer: Long): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI("http://localhost:$port$path"))
            .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer ${token(buyer)}")
        if (body != null) builder.header("Content-Type", "application/json")
        return client.send(builder.method(method,
            body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.ofString())
    }

    private fun participant(login: String) = jdbc.queryForObject(
        "INSERT INTO participant(login,mail,password,status,role) VALUES (?,?,'hash','ACTIVE','USER') RETURNING id",
        Long::class.java, login, "$login@example.test")!!

    private fun address(owner: Long): Long {
        val id = jdbc.queryForObject("INSERT INTO address(country,city) VALUES ('RU','Moscow') RETURNING id", Long::class.java)!!
        jdbc.update("INSERT INTO participant_address(participant_id,address_id) VALUES (?,?)", owner, id)
        return id
    }

    private fun product(name: String, owner: Long, count: Int) = jdbc.queryForObject(
        """INSERT INTO product(name,price,currency,participant_id,status,availability,count,used)
           VALUES (?,10,'RUB',?,'ACTIVE','PURCHASABLE',?,false) RETURNING id""",
        Long::class.java, name, owner, count)!!

    companion object {
        private val postgres = EmbeddedPostgres.start()
        private val privateKey: PrivateKey by lazy {
            val pem = ClassPathResource("keys/test_private_key.pem").inputStream.bufferedReader().use { it.readText() }
                .replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "")
                .replace(Regex("\\s+"), "")
            KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)))
        }
        private fun token(id: Long) = Jwts.builder().claim("id", id).claim("login", "legacy24")
            .claim("role", "USER").claim("type", "access")
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
            registry.add("spring.r2dbc.url") { "r2dbc:postgresql://localhost:${postgres.port}/postgres" }
            registry.add("spring.r2dbc.username") { "postgres" }
            registry.add("spring.r2dbc.password") { "" }
        }

        @JvmStatic @AfterAll fun close() = postgres.close()
    }
}
