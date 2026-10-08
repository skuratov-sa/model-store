package com.model_store.modern.catalog.product.command

import com.amazonaws.services.s3.AmazonS3
import com.fasterxml.jackson.databind.ObjectMapper
import com.model_store.modern.catalog.product.command.application.ProductCommands
import com.model_store.modern.catalog.product.command.application.ProductCommandFailure
import com.model_store.modern.catalog.product.command.application.FailureKind
import com.model_store.modern.catalog.product.command.domain.ProductAvailability
import com.model_store.modern.catalog.product.command.domain.ProductChanges
import io.jsonwebtoken.Jwts
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import jakarta.servlet.Filter
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootTest(properties = ["app.public-key-path=keys/test_public_key.pem"])
@ActiveProfiles("modern")
class ProductCommandHttpTest {
    @Autowired lateinit var context: WebApplicationContext
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var json: ObjectMapper
    @Autowired lateinit var commands: ProductCommands
    @Autowired lateinit var flyway: Flyway
    @MockitoBean lateinit var s3: AmazonS3
    @MockitoBean lateinit var mail: JavaMailSender

    @Test
    fun `owner lifecycle and administrative status preserve routes and ownership`() {
        assertTrue(flyway.info().applied().isNotEmpty())
        val owner = participant("command16owner", "USER")
        val stranger = participant("command16stranger", "USER")
        val admin = participant("command16admin", "ADMIN")
        ready(owner)
        val category = category("command16category")
        val image = image(owner)
        val mvc = mvc()
        val body = """{"name":"Command16 item","participantId":$stranger,"price":101,"currency":"RUB","availability":"PURCHASABLE",
                       "count":2,"categoryIds":[$category],"imageIds":[$image]}"""

        assertEquals(401, mvc.perform(post("/products").contentType(MediaType.APPLICATION_JSON)
            .content(body)).andReturn().response.status)
        assertEquals(403, mvc.perform(post("/products")
            .header("Authorization", "Bearer ${token(owner, type = "agent_access")}")
            .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().response.status)
        val created = mvc.perform(post("/products").header("Authorization", "Bearer ${token(owner)}")
            .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().response
        assertEquals(200, created.status, created.contentAsString)
        val id = created.contentAsString.toLong()
        assertEquals(owner, jdbc.queryForObject("SELECT participant_id FROM product WHERE id=?", Long::class.java, id))
        assertEquals(category, jdbc.queryForObject("SELECT category_id FROM product_category WHERE product_id=?", Long::class.java, id))
        assertEquals(id, jdbc.queryForObject("SELECT entity_id FROM image WHERE id=?", Long::class.java, image))
        assertEquals("ACTIVE", jdbc.queryForObject("SELECT status::text FROM image WHERE id=?", String::class.java, image))

        val update = """{"name":"Command16 changed","price":103,"categoryIds":[]}"""
        assertEquals(404, mvc.perform(put("/product/$id").header("Authorization", "Bearer ${token(stranger)}")
            .contentType(MediaType.APPLICATION_JSON).content(update)).andReturn().response.status)
        assertEquals(200, mvc.perform(put("/product/$id").header("Authorization", "Bearer ${token(owner)}")
            .contentType(MediaType.APPLICATION_JSON).content(update)).andReturn().response.status)
        assertEquals("Command16 changed", jdbc.queryForObject("SELECT name FROM product WHERE id=?", String::class.java, id))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM product_category WHERE product_id=?", Int::class.java, id))

        assertEquals(403, mvc.perform(put("/admin/actions/product/$id").param("productStatus", "BLOCKED")
            .header("Authorization", "Bearer ${token(owner)}")).andReturn().response.status)
        assertEquals(200, mvc.perform(put("/admin/actions/product/$id").param("productStatus", "BLOCKED")
            .header("Authorization", "Bearer ${token(admin, "ADMIN")}")).andReturn().response.status)
        assertEquals(404, mvc.perform(post("/products/extend/$id")
            .header("Authorization", "Bearer ${token(owner)}")).andReturn().response.status)
        assertEquals(200, mvc.perform(put("/admin/actions/product/$id").param("productStatus", "TIME_EXPIRED")
            .header("Authorization", "Bearer ${token(admin, "ADMIN")}")).andReturn().response.status)
        assertEquals(200, mvc.perform(post("/products/extend/$id")
            .header("Authorization", "Bearer ${token(owner)}")).andReturn().response.status)
        assertEquals("ACTIVE", jdbc.queryForObject("SELECT status::text FROM product WHERE id=?", String::class.java, id))
        assertEquals(200, mvc.perform(delete("/product/$id")
            .header("Authorization", "Bearer ${token(owner)}")).andReturn().response.status)
        assertEquals("DELETED", jdbc.queryForObject("SELECT status::text FROM product WHERE id=?", String::class.java, id))
        assertEquals("DELETE", jdbc.queryForObject("SELECT status::text FROM image WHERE id=?", String::class.java, image))
    }

    @Test
    fun `invalid category and image leave no partial product or claimed image`() {
        val owner = participant("command16rollback", "USER")
        val stranger = participant("command16imageowner", "USER")
        ready(owner)
        val image = image(owner)
        val foreignImage = image(stranger)
        val mvc = mvc()
        val base = """"name":"Command16 rollback","price":100,"currency":"RUB","availability":"PURCHASABLE""""
        val invalidCategory = """{$base,"categoryIds":[9223372036854775807],"imageIds":[$image]}"""
        val categoryFailure = mvc.perform(post("/products").header("Authorization", "Bearer ${token(owner)}")
            .contentType(MediaType.APPLICATION_JSON).content(invalidCategory)).andReturn().response
        assertEquals(400, categoryFailure.status)
        assertEquals("INVALID_REFERENCE", json.readTree(categoryFailure.contentAsString)["code"].asText())
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM product WHERE name='Command16 rollback'", Int::class.java))
        assertEquals("TEMPORARY", jdbc.queryForObject("SELECT status::text FROM image WHERE id=?", String::class.java, image))
        assertNull(jdbc.queryForObject("SELECT entity_id FROM image WHERE id=?", Long::class.java, image))

        val foreign = """{$base,"imageIds":[$foreignImage]}"""
        assertEquals(403, mvc.perform(post("/products").header("Authorization", "Bearer ${token(owner)}")
            .contentType(MediaType.APPLICATION_JSON).content(foreign)).andReturn().response.status)
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM product WHERE name='Command16 rollback'", Int::class.java))
        assertNull(jdbc.queryForObject("SELECT entity_id FROM image WHERE id=?", Long::class.java, foreignImage))

        val valid = """{$base}"""
        val created = mvc.perform(post("/products").header("Authorization", "Bearer ${token(owner)}")
            .contentType(MediaType.APPLICATION_JSON).content(valid)).andReturn().response
        assertEquals(200, created.status)
        val id = created.contentAsString.toLong()
        val update = """{"name":"Must roll back","categoryIds":[9223372036854775807],"imageIds":[$image]}"""
        assertEquals(400, mvc.perform(put("/product/$id").header("Authorization", "Bearer ${token(owner)}")
            .contentType(MediaType.APPLICATION_JSON).content(update)).andReturn().response.status)
        assertEquals("Command16 rollback", jdbc.queryForObject("SELECT name FROM product WHERE id=?", String::class.java, id))
        assertEquals("TEMPORARY", jdbc.queryForObject("SELECT status::text FROM image WHERE id=?", String::class.java, image))
    }

    @Test
    fun `availability rules and image limit reject invalid creation while admin external product succeeds`() {
        val owner = participant("command16rules", "USER")
        val admin = participant("command16rulesadmin", "ADMIN")
        ready(admin)
        val mvc = mvc()
        val ordinary = """{"name":"Command16 prerequisites","price":10,"currency":"RUB","availability":"PURCHASABLE"}"""
        val noTransfer = mvc.perform(post("/products").header("Authorization", "Bearer ${token(owner)}")
            .contentType(MediaType.APPLICATION_JSON).content(ordinary)).andReturn().response
        assertEquals(404, noTransfer.status)
        assertEquals("TRANSFER_NOT_FOUND", json.readTree(noTransfer.contentAsString)["code"].asText())
        jdbc.update("INSERT INTO transfer(sending,price,currency,participant_id) VALUES ('PRODUCT_PICKUP',0,'RUB',?)", owner)
        val noSocial = mvc.perform(post("/products").header("Authorization", "Bearer ${token(owner)}")
            .contentType(MediaType.APPLICATION_JSON).content(ordinary)).andReturn().response
        assertEquals(404, noSocial.status)
        assertEquals("SOCIAL_NETWORK_NOT_FOUND", json.readTree(noSocial.contentAsString)["code"].asText())
        jdbc.update("INSERT INTO social_network(type,login,participant_id) VALUES ('TELEGRAM','command16',?)", owner)
        val external = """{"name":"Command16 external","price":10,"currency":"RUB",
                          "availability":"EXTERNAL_PRODUCT","externalUrl":"https://example.test/item"}"""
        assertEquals(403, mvc.perform(post("/products").header("Authorization", "Bearer ${token(owner)}")
            .contentType(MediaType.APPLICATION_JSON).content(external)).andReturn().response.status)
        val giveawayRequest = """{"name":"Command16 giveaway","price":0,"currency":"RUB",
                                 "availability":"GIVEAWAY"}"""
        assertEquals(403, mvc.perform(post("/products").header("Authorization", "Bearer ${token(owner)}")
            .contentType(MediaType.APPLICATION_JSON).content(giveawayRequest)).andReturn().response.status)
        val existingGiveaway = jdbc.queryForObject(
            """INSERT INTO product(name,price,currency,participant_id,availability)
               VALUES ('Command16 old giveaway',0,'RUB',?,'GIVEAWAY') RETURNING id""",
            Long::class.java, owner)!!
        assertEquals(404, mvc.perform(put("/product/$existingGiveaway")
            .header("Authorization", "Bearer ${token(owner)}")
            .contentType(MediaType.APPLICATION_JSON).content("""{"price":10}""")).andReturn().response.status)
        val created = mvc.perform(post("/products").header("Authorization", "Bearer ${token(admin, "ADMIN")}")
            .contentType(MediaType.APPLICATION_JSON).content(external)).andReturn().response
        assertEquals(200, created.status)
        assertEquals("EXTERNAL_PRODUCT", jdbc.queryForObject("SELECT availability::text FROM product WHERE id=?",
            String::class.java, created.contentAsString.toLong()))
        val adminProductId = created.contentAsString.toLong()
        assertEquals(200, mvc.perform(put("/admin/actions/product/$adminProductId").param("productStatus", "BLOCKED")
            .header("Authorization", "Bearer ${token(admin, "ADMIN")}")).andReturn().response.status)
        assertEquals(200, mvc.perform(put("/product/$adminProductId")
            .header("Authorization", "Bearer ${token(admin, "ADMIN")}")
            .contentType(MediaType.APPLICATION_JSON).content("""{"price":12}""")).andReturn().response.status)
        assertEquals(12f, jdbc.queryForObject("SELECT price FROM product WHERE id=?", Float::class.java, adminProductId))
        assertEquals("BLOCKED", jdbc.queryForObject("SELECT status::text FROM product WHERE id=?", String::class.java, adminProductId))
        val duplicate = mvc.perform(post("/products").header("Authorization", "Bearer ${token(admin, "ADMIN")}")
            .contentType(MediaType.APPLICATION_JSON).content(external)).andReturn().response
        // The unique index covers ACTIVE products; blocked listings may be recreated.
        assertEquals(200, duplicate.status)
        val duplicateActive = mvc.perform(post("/products").header("Authorization", "Bearer ${token(admin, "ADMIN")}")
            .contentType(MediaType.APPLICATION_JSON).content(external)).andReturn().response
        assertEquals(409, duplicateActive.status)
        assertEquals("PRODUCT_ALREADY_EXISTS", json.readTree(duplicateActive.contentAsString)["code"].asText())
        val reactivate = mvc.perform(put("/admin/actions/product/$adminProductId")
            .param("productStatus", "ACTIVE")
            .header("Authorization", "Bearer ${token(admin, "ADMIN")}")).andReturn().response
        assertEquals(409, reactivate.status)
        assertEquals("PRODUCT_ALREADY_EXISTS", json.readTree(reactivate.contentAsString)["code"].asText())
        assertEquals("BLOCKED", jdbc.queryForObject("SELECT status::text FROM product WHERE id=?", String::class.java, adminProductId))

        val preorder = """{"name":"Command16 preorder","price":20,"currency":"RUB","availability":"PREORDER"}"""
        val rejected = mvc.perform(post("/products").header("Authorization", "Bearer ${token(owner)}")
            .contentType(MediaType.APPLICATION_JSON).content(preorder)).andReturn().response
        assertEquals(400, rejected.status)
        assertEquals("INVALID_REQUEST", json.readTree(rejected.contentAsString)["code"].asText())
        val excessPrepayment = """{"name":"Command16 excess prepayment","price":20,"currency":"RUB",
                                   "availability":"PREORDER","prepaymentAmount":21}"""
        assertEquals(400, mvc.perform(post("/products").header("Authorization", "Bearer ${token(owner)}")
            .contentType(MediaType.APPLICATION_JSON).content(excessPrepayment)).andReturn().response.status)
        val badCount = """{"name":"Command16 count","price":20,"currency":"RUB",
                         "availability":"PURCHASABLE","count":0}"""
        assertEquals(400, mvc.perform(post("/products").header("Authorization", "Bearer ${token(owner)}")
            .contentType(MediaType.APPLICATION_JSON).content(badCount)).andReturn().response.status)
        for (missingRequired in listOf(
            """{"name":"Command16 no price","currency":"RUB","availability":"PURCHASABLE"}""",
            """{"name":"Command16 no currency","price":10,"availability":"PURCHASABLE"}""",
        )) {
            val response = mvc.perform(post("/products").header("Authorization", "Bearer ${token(owner)}")
                .contentType(MediaType.APPLICATION_JSON).content(missingRequired)).andReturn().response
            assertEquals(409, response.status)
            assertEquals("DUPLICATE_KEY", json.readTree(response.contentAsString)["code"].asText())
        }
        val imageIds = (1..5).map { image(owner) }
        val overLimit = """{"name":"Command16 many images","price":10,"currency":"RUB",
                           "availability":"PURCHASABLE","imageIds":${json.writeValueAsString(imageIds)}}"""
        assertEquals(400, mvc.perform(post("/products").header("Authorization", "Bearer ${token(owner)}")
            .contentType(MediaType.APPLICATION_JSON).content(overLimit)).andReturn().response.status)
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM product WHERE name='Command16 many images'", Int::class.java))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM image WHERE id IN (${imageIds.joinToString()}) AND status='ACTIVE'", Int::class.java))
        assertEquals(400, mvc.perform(put("/admin/actions/product/${created.contentAsString}")
            .param("productStatus", "AWAITING_GIVEAWAY")
            .header("Authorization", "Bearer ${token(admin, "ADMIN")}")).andReturn().response.status)
    }

    @Test
    fun `concurrent claims serialize and only one product commits`() {
        val owner = participant("command16race", "USER")
        ready(owner)
        val image = image(owner)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val tasks = (1..2).map { index -> pool.submit<String> {
                val changes = ProductChanges(name = "Command16 race $index", price = 10f, currency = "RUB",
                    availability = ProductAvailability.PURCHASABLE, imageIds = listOf(image))
                try { commands.create(owner, "USER", changes); "CREATED" }
                catch (error: ProductCommandFailure) { error.kind.name }
            } }
            assertEquals(setOf("CREATED", "CONFLICT"), tasks.map { it.get(20, TimeUnit.SECONDS) }.toSet())
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM product WHERE name LIKE 'Command16 race%'", Int::class.java))
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM image WHERE id=? AND status='ACTIVE'", Int::class.java, image))
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `image claim checks tag uploader and legacy attached image without partial update`() {
        val owner = participant("command16imagechecks", "USER")
        val other = participant("command16imageother", "USER")
        ready(owner)
        val id = commands.create(owner, "USER", ProductChanges(name = "Command16 checked", price = 10f,
            currency = "RUB", availability = ProductAvailability.PURCHASABLE))
        val otherProduct = commands.create(owner, "USER", ProductChanges(name = "Command16 second", price = 10f,
            currency = "RUB", availability = ProductAvailability.PURCHASABLE))
        val wrongTag = image(owner, "PARTICIPANT", "ACTIVE", id)
        val noUploader = image(null)
        val foreignUploader = image(other)
        val unassignedActive = image(owner, "PRODUCT", "ACTIVE")
        val foreignAttached = image(other, "PRODUCT", "ACTIVE", otherProduct)
        val mvc = mvc()
        for (badId in listOf(wrongTag, noUploader, foreignUploader, unassignedActive, foreignAttached)) {
            val result = mvc.perform(put("/product/$id").header("Authorization", "Bearer ${token(owner)}")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"Must not save","imageIds":[$badId]}""")).andReturn().response
            assertEquals(403, result.status, "image=$badId ${result.contentAsString}")
            assertEquals("Command16 checked", jdbc.queryForObject("SELECT name FROM product WHERE id=?", String::class.java, id))
        }
        assertEquals("PARTICIPANT", jdbc.queryForObject("SELECT tag::text FROM image WHERE id=?", String::class.java, wrongTag))
        assertNull(jdbc.queryForObject("SELECT entity_id FROM image WHERE id=?", Long::class.java, noUploader))
        val oldAttached = image(null, "PRODUCT", "ACTIVE", id)
        assertEquals(200, mvc.perform(put("/product/$id").header("Authorization", "Bearer ${token(owner)}")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"imageIds":[$oldAttached]}""")).andReturn().response.status)
        assertEquals("ACTIVE", jdbc.queryForObject("SELECT status::text FROM image WHERE id=?", String::class.java, oldAttached))
    }

    @Test
    fun `contended image lock returns conflict without deadlock or partial delete`() {
        val owner = participant("command16locked", "USER")
        ready(owner)
        val image = image(owner)
        val id = commands.create(owner, "USER", ProductChanges(name = "Command16 locked", price = 10f,
            currency = "RUB", availability = ProductAvailability.PURCHASABLE, imageIds = listOf(image)))
        val connection = jdbc.dataSource!!.connection
        val pool = Executors.newSingleThreadExecutor()
        try {
            connection.autoCommit = false
            connection.prepareStatement("SELECT id FROM image WHERE id=? FOR UPDATE").use { statement ->
                statement.setLong(1, image)
                statement.executeQuery().use { assertTrue(it.next()) }
            }
            val delete = pool.submit<FailureKind> {
                try { commands.delete(id, owner); throw AssertionError("delete unexpectedly succeeded") }
                catch (error: ProductCommandFailure) { error.kind }
            }
            assertEquals(FailureKind.CONFLICT, delete.get(5, TimeUnit.SECONDS))
            assertEquals("ACTIVE", jdbc.queryForObject("SELECT status::text FROM product WHERE id=?", String::class.java, id))
        } finally {
            connection.rollback()
            connection.close()
            pool.shutdownNow()
        }
        assertEquals("ACTIVE", jdbc.queryForObject("SELECT status::text FROM image WHERE id=?", String::class.java, image))
    }

    @Test
    fun `concurrent administrative activation keeps one active product per owner and name`() {
        val admin = participant("command16statusrace", "ADMIN")
        ready(admin)
        val changes = ProductChanges(name = "Command16 shared", price = 10f, currency = "RUB",
            availability = ProductAvailability.PURCHASABLE)
        val first = commands.create(admin, "ADMIN", changes)
        commands.changeStatus(first, com.model_store.modern.catalog.product.command.domain.ProductState.BLOCKED)
        val second = commands.create(admin, "ADMIN", changes)
        commands.changeStatus(second, com.model_store.modern.catalog.product.command.domain.ProductState.BLOCKED)
        val mvc = mvc()
        val pool = Executors.newFixedThreadPool(2)
        try {
            val responses = listOf(first, second).map { id -> pool.submit<Int> {
                mvc.perform(put("/admin/actions/product/$id").param("productStatus", "ACTIVE")
                    .header("Authorization", "Bearer ${token(admin, "ADMIN")}")).andReturn().response.status
            } }
            assertEquals(setOf(200, 409), responses.map { it.get(20, TimeUnit.SECONDS) }.toSet())
            assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM product WHERE participant_id=? AND name='Command16 shared' AND status='ACTIVE'",
                Int::class.java, admin))
        } finally {
            pool.shutdownNow()
        }
    }

    private fun mvc() = MockMvcBuilders.webAppContextSetup(context)
        .addFilters<DefaultMockMvcBuilder>(context.getBean("springSecurityFilterChain") as Filter).build()

    private fun participant(login: String, role: String): Long = jdbc.queryForObject(
        """INSERT INTO participant(login,mail,password,status,role,deadline_sending,deadline_payment)
           VALUES (?,?,'hash','ACTIVE',?::participant_role,3,7) RETURNING id""",
        Long::class.java, login, "$login@example.test", role,
    )!!

    private fun ready(owner: Long) {
        jdbc.update("INSERT INTO transfer(sending,price,currency,participant_id) VALUES ('PRODUCT_PICKUP',0,'RUB',?)", owner)
        jdbc.update("INSERT INTO social_network(type,login,participant_id) VALUES ('TELEGRAM','command16',?)", owner)
    }

    private fun category(name: String) = jdbc.queryForObject(
        "INSERT INTO category(name) VALUES (?) RETURNING id", Long::class.java, name)!!

    private fun image(owner: Long?, tag: String = "PRODUCT", status: String = "TEMPORARY", entityId: Long? = null) =
        jdbc.queryForObject(
            """INSERT INTO image(filename,tag,status,entity_id,uploaded_by)
               VALUES ('command16.jpg',?::image_tag,?::image_status,?,?) RETURNING id""",
            Long::class.java, tag, status, entityId, owner,
        )!!

    companion object {
        private val postgres = EmbeddedPostgres.start()
        private val privateKey: PrivateKey by lazy {
            val pem = ClassPathResource("keys/test_private_key.pem").inputStream.bufferedReader().use { it.readText() }
                .replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "")
                .replace(Regex("\\s+"), "")
            KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)))
        }

        private fun token(id: Long, role: String = "USER", type: String = "access"): String = Jwts.builder()
            .claim("id", id).claim("login", "command16").claim("role", role).claim("type", type)
            .apply { if (type == "agent_access") claim("issuedBy", "admin") }
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
