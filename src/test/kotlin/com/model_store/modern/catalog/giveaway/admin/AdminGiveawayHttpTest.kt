package com.model_store.modern.catalog.giveaway.admin

import com.amazonaws.services.s3.AmazonS3
import com.fasterxml.jackson.databind.ObjectMapper
import com.model_store.modern.catalog.giveaway.admin.application.CreateGiveaway
import com.model_store.modern.catalog.giveaway.admin.application.AdminGiveaways
import com.model_store.modern.catalog.giveaway.admin.application.GiveawayFailure
import com.model_store.modern.catalog.giveaway.admin.application.UpdateGiveawayProduct
import com.model_store.modern.catalog.giveaway.admin.domain.GiveawayCreate
import com.model_store.modern.catalog.giveaway.admin.domain.GiveawayCaller
import com.model_store.modern.catalog.giveaway.admin.domain.GiveawaySettings
import com.model_store.modern.catalog.product.command.application.ProductCommands
import com.model_store.modern.catalog.product.command.domain.ProductState
import com.model_store.modern.catalog.product.command.domain.ProductAvailability
import com.model_store.modern.catalog.product.reservation.application.ProductReservation
import com.model_store.modern.catalog.product.reservation.application.ProductQuantity
import com.model_store.modern.catalog.product.reservation.application.ProductReservationFailure
import io.jsonwebtoken.Jwts
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import jakarta.servlet.Filter
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
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.context.WebApplicationContext
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootTest(properties = ["app.public-key-path=keys/test_public_key.pem",
    "app.private-key-path=keys/test_private_key.pem"])
@ActiveProfiles("modern")
class AdminGiveawayHttpTest {
    @Autowired lateinit var context: WebApplicationContext
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var json: ObjectMapper
    @Autowired lateinit var create: CreateGiveaway
    @Autowired lateinit var adminGiveaways: AdminGiveaways
    @Autowired lateinit var updateProduct: UpdateGiveawayProduct
    @Autowired lateinit var productCommands: ProductCommands
    @Autowired lateinit var reservation: ProductReservation
    @Autowired lateinit var transactionManager: PlatformTransactionManager
    @MockitoBean lateinit var s3: AmazonS3
    @MockitoBean lateinit var mail: JavaMailSender

    @Test
    fun `admin history details update preserve shape and security`() {
        val admin = participant("giveaway22admin", "ADMIN")
        val user = participant("giveaway22user", "USER")
        val id = giveaway(admin, "Giveaway22 A", "AWAITING_GIVEAWAY", true, 100000, 200000)
        val mvc = mvc()
        val path = "/admin/actions/giveaways"
        assertEquals(401, mvc.perform(get("$path/history")).andReturn().response.status)
        assertEquals(403, mvc.perform(get("$path/history").header("Authorization", bearer(user, "USER")))
            .andReturn().response.status)
        for (type in listOf("refresh", "verify", "agent_access"))
            assertEquals(401, mvc.perform(get("$path/history")
                .header("Authorization", bearer(admin, "ADMIN", type))).andReturn().response.status)
        assertEquals(403, mvc.perform(get("$path/history")
            .header("Authorization", bearer(admin, "ADMIN", "agent_access", issuedBy = "admin")))
            .andReturn().response.status)
        assertEquals(401, mvc.perform(get("$path/history")
            .header("Authorization", bearer(admin, "ADMIN", expiry = Instant.now().minusSeconds(60))))
            .andReturn().response.status)
        val forged = java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().private
        assertEquals(401, mvc.perform(get("$path/history")
            .header("Authorization", bearer(admin, "ADMIN", signer = forged))).andReturn().response.status)
        val history = mvc.perform(get("$path/history").header("Authorization", bearer(admin, "ADMIN")))
            .andReturn().response
        assertEquals(200, history.status, history.contentAsString)
        val row = json.readTree(history.contentAsString).first { it["id"].asLong() == id }
        assertEquals("GIVEAWAY", row["availability"].asText())
        assertTrue(row.has("giveawayRules"))
        assertTrue(row.has("prepaymentAmount"))
        val details = mvc.perform(get("$path/$id").header("Authorization", bearer(admin, "ADMIN")))
            .andReturn().response
        assertEquals(200, details.status, details.contentAsString)
        val body = json.readTree(details.contentAsString)
        assertEquals(id, body["product"]["id"].asLong())
        assertTrue(body["imageIds"].isArray)
        assertTrue(body["categories"].isArray)
        val update = """{"telegramUrl":" https://telegram.me/newchannel ","winnersCount":3}"""
        val updated = mvc.perform(put("$path/$id").header("Authorization", bearer(admin, "ADMIN"))
            .contentType(MediaType.APPLICATION_JSON).content(update)).andReturn().response
        assertEquals(200, updated.status, updated.contentAsString)
        assertEquals("https://telegram.me/newchannel", jdbc.queryForObject(
            "SELECT giveaway_telegram_url FROM product WHERE id=?", String::class.java, id))
        assertEquals(3, jdbc.queryForObject("SELECT giveaway_winners_count FROM product WHERE id=?",
            Int::class.java, id))
        assertEquals(404, mvc.perform(get("$path/${Long.MAX_VALUE}")
            .header("Authorization", bearer(admin, "ADMIN"))).andReturn().response.status)
    }

    @Test
    fun `history includes agent owned giveaways but hides other private owners`() {
        val admin = participant("giveaway22visibilityadmin", "ADMIN")
        val agent = participant("giveaway22visibilityagent", "USER")
        val stranger = participant("giveaway22visibilityother", "USER")
        jdbc.update("UPDATE participant SET is_agent=true WHERE id=?", agent)
        val agentGiveaway = giveaway(agent, "Giveaway22 agent", "TIME_EXPIRED", false, 1200000, 1203600)
        val privateGiveaway = giveaway(stranger, "Giveaway22 private", "TIME_EXPIRED", false, 1300000, 1303600)
        val mvc = mvc()
        val token = bearer(admin, "ADMIN")
        val history = mvc.perform(get("/admin/actions/giveaways/history")
            .header("Authorization", token)).andReturn().response
        assertEquals(200, history.status, history.contentAsString)
        val ids = json.readTree(history.contentAsString).map { it["id"].asLong() }
        assertTrue(agentGiveaway in ids)
        assertFalse(privateGiveaway in ids)
        assertEquals(200, mvc.perform(get("/admin/actions/giveaways/$agentGiveaway")
            .header("Authorization", token)).andReturn().response.status)
        assertEquals(404, mvc.perform(get("/admin/actions/giveaways/$privateGiveaway")
            .header("Authorization", token)).andReturn().response.status)
    }

    @Test
    fun `application rejects direct user and agent access calls`() {
        val admin = participant("giveaway22directadmin", "ADMIN")
        val id = giveaway(admin, "Giveaway22 direct", "TIME_EXPIRED", false, 1500000, 1503600)
        val user = GiveawayCaller(admin, "USER", false)
        val agentToken = GiveawayCaller(admin, "ADMIN", true)
        for (caller in listOf(user, agentToken)) {
            assertEquals("ACCESS_DENIED", assertThrows(GiveawayFailure::class.java) {
                adminGiveaways.history(caller, 0, 50)
            }.code)
            assertEquals("ACCESS_DENIED", assertThrows(GiveawayFailure::class.java) {
                adminGiveaways.details(id, caller)
            }.code)
            assertEquals("ACCESS_DENIED", assertThrows(GiveawayFailure::class.java) {
                adminGiveaways.update(id, caller, GiveawaySettings(enabled = true))
            }.code)
            assertEquals("ACCESS_DENIED", assertThrows(GiveawayFailure::class.java) {
                create.create(caller, GiveawayCreate("Forbidden22", null, null, null, null,
                    null, null, null, null, null, null, null))
            }.code)
            assertEquals("ACCESS_DENIED", assertThrows(GiveawayFailure::class.java) {
                updateProduct.update(id, caller, null, GiveawayCreate("Forbidden edit", null,
                    null, null, null, null, null, null, null, null, null, null))
            }.code)
        }
        assertEquals(false, jdbc.queryForObject("SELECT giveaway_enabled FROM product WHERE id=?",
            Boolean::class.java, id))
    }

    @Test
    fun `invalid update and overlapping intervals roll back`() {
        val admin = participant("giveaway22overlap", "ADMIN")
        val first = giveaway(admin, "Giveaway22 B", "ACTIVE", true, -3600, 86400)
        val second = giveaway(admin, "Giveaway22 C", "TIME_EXPIRED", false, 1000, 80000)
        val mvc = mvc()
        val path = "/admin/actions/giveaways/$second"
        val before = jdbc.queryForObject("SELECT giveaway_telegram_url FROM product WHERE id=?", String::class.java, second)
        val response = mvc.perform(put(path).header("Authorization", bearer(admin, "ADMIN"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"enabled":true,"telegramUrl":"https://t.me/changed"}""")).andReturn().response
        assertEquals(400, response.status, response.contentAsString)
        assertEquals("GIVEAWAY_WINDOW_OVERLAP", json.readTree(response.contentAsString)["code"].asText())
        assertEquals(before, jdbc.queryForObject("SELECT giveaway_telegram_url FROM product WHERE id=?", String::class.java, second))
        assertEquals(false, jdbc.queryForObject("SELECT giveaway_enabled FROM product WHERE id=?", Boolean::class.java, second))
        assertEquals(200, mvc.perform(get("/giveaways/products/$first")).andReturn().response.status)
        assertEquals(404, mvc.perform(get("/giveaways/products/$second")).andReturn().response.status)
        val invalid = mvc.perform(put("/admin/actions/giveaways/$first")
            .header("Authorization", bearer(admin, "ADMIN")).contentType(MediaType.APPLICATION_JSON)
            .content("""{"telegramUrl":"https://evil.test/hello"}""")).andReturn().response
        assertEquals(400, invalid.status)
        assertEquals("INVALID_REQUEST", json.readTree(invalid.contentAsString)["code"].asText())
        val tooLong = mvc.perform(put("/admin/actions/giveaways/$first")
            .header("Authorization", bearer(admin, "ADMIN")).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(mapOf("telegramUrl" to "https://t.me/${"a".repeat(1000)}"))))
            .andReturn().response
        assertEquals(409, tooLong.status, tooLong.contentAsString)
        assertTrue(json.readTree(tooLong.contentAsString)["details"].isNull)
        assertEquals("https://t.me/prize", jdbc.queryForObject(
            "SELECT giveaway_telegram_url FROM product WHERE id=?", String::class.java, first))
    }

    @Test
    fun `creation rolls back product settings categories and image claim together`() {
        val admin = participant("giveaway22create", "ADMIN")
        val start = Instant.now().plusSeconds(300000)
        val end = start.plusSeconds(3600)
        val settings = GiveawaySettings(telegramUrl = "https://t.me/created", startAt = start,
            endAt = end, winnersCount = 2, rules = "Rules", homeText = "Home")
        val image = jdbc.queryForObject("""INSERT INTO image(filename,tag,status,uploaded_by)
            VALUES ('giveaway22.jpg','PRODUCT','TEMPORARY',?) RETURNING id""", Long::class.java, admin)!!
        val bad = GiveawayCreate("Giveaway22 rollback", null, null, null, null, null,
            null, null, null, listOf(Long.MAX_VALUE), listOf(image), settings)
        assertThrows(Exception::class.java) { create.create(GiveawayCaller(admin, "ADMIN", false), bad) }
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM product WHERE name='Giveaway22 rollback'", Int::class.java))
        assertEquals("TEMPORARY", jdbc.queryForObject("SELECT status::text FROM image WHERE id=?", String::class.java, image))
        assertNull(jdbc.queryForObject("SELECT entity_id FROM image WHERE id=?", Long::class.java, image))
        val good = bad.copy(name = "Giveaway22 created", categoryIds = emptyList())
        val id = create.create(GiveawayCaller(admin, "ADMIN", false), good)
        assertEquals("GIVEAWAY", jdbc.queryForObject("SELECT availability::text FROM product WHERE id=?", String::class.java, id))
        assertEquals("AWAITING_GIVEAWAY", jdbc.queryForObject("SELECT status::text FROM product WHERE id=?", String::class.java, id))
        assertEquals(true, jdbc.queryForObject("SELECT giveaway_enabled FROM product WHERE id=?", Boolean::class.java, id))
        assertEquals(0f, jdbc.queryForObject("SELECT price FROM product WHERE id=?", Float::class.java, id))
        assertEquals("RUB", jdbc.queryForObject("SELECT currency::text FROM product WHERE id=?", String::class.java, id))
        assertEquals(id, jdbc.queryForObject("SELECT entity_id FROM image WHERE id=?", Long::class.java, image))
    }

    @Test
    fun `product route creates only administrator giveaways`() {
        val admin = participant("giveaway22routeadmin", "ADMIN")
        val user = participant("giveaway22routeuser", "USER")
        val start = Instant.now().plusSeconds(450000)
        val end = start.plusSeconds(3600)
        val body = json.writeValueAsString(mapOf("name" to "Giveaway22 routed",
            "availability" to "GIVEAWAY", "giveaway" to mapOf("telegramUrl" to "https://t.me/routed",
                "startAt" to start.toString(), "endAt" to end.toString(), "winnersCount" to 2,
                "rules" to "Rules", "homeText" to "Home")))
        val mvc = mvc()
        assertEquals(401, mvc.perform(post("/products").contentType(MediaType.APPLICATION_JSON)
            .content(body)).andReturn().response.status)
        val userResponse = mvc.perform(post("/products").header("Authorization", bearer(user, "USER"))
            .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().response
        assertEquals(403, userResponse.status, userResponse.contentAsString)
        assertEquals("ACCESS_DENIED", json.readTree(userResponse.contentAsString)["code"].asText())
        val created = mvc.perform(post("/products").header("Authorization", bearer(admin, "ADMIN"))
            .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().response
        assertEquals(200, created.status, created.contentAsString)
        val id = created.contentAsString.toLong()
        assertEquals("GIVEAWAY", jdbc.queryForObject("SELECT availability::text FROM product WHERE id=?", String::class.java, id))
        assertEquals(200, mvc.perform(get("/giveaways/products/$id")).andReturn().response.status)
        assertEquals(200, mvc.perform(get("/admin/actions/giveaways/$id")
            .header("Authorization", bearer(admin, "ADMIN"))).andReturn().response.status)
    }

    @Test
    fun `administrator product route edits and converts an owned giveaway`() {
        val admin = participant("giveaway22editowner", "ADMIN")
        val other = participant("giveaway22editother", "ADMIN")
        val id = giveaway(admin, "Giveaway22 before edit", "AWAITING_GIVEAWAY", true, 1700000, 1703600)
        val mvc = mvc()
        val path = "/product/$id"
        val edit = """{"name":"Giveaway22 edited","giveaway":{"winnersCount":4}}"""
        assertEquals(404, mvc.perform(put(path).header("Authorization", bearer(other, "ADMIN"))
            .contentType(MediaType.APPLICATION_JSON).content(edit)).andReturn().response.status)
        assertEquals(404, mvc.perform(put(path).header("Authorization", bearer(admin, "USER"))
            .contentType(MediaType.APPLICATION_JSON).content(edit)).andReturn().response.status)
        val image = jdbc.queryForObject("""INSERT INTO image(filename,tag,status,uploaded_by)
            VALUES ('giveaway22edit.jpg','PRODUCT','TEMPORARY',?) RETURNING id""", Long::class.java, admin)!!
        val rollback = mvc.perform(put(path).header("Authorization", bearer(admin, "ADMIN"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"name":"Should roll back","giveaway":{"winnersCount":5},
                "imageIds":[$image],"categoryIds":[${Long.MAX_VALUE}]}""")).andReturn().response
        assertEquals(400, rollback.status, rollback.contentAsString)
        assertEquals("Giveaway22 before edit", jdbc.queryForObject("SELECT name FROM product WHERE id=?", String::class.java, id))
        assertEquals(2, jdbc.queryForObject("SELECT giveaway_winners_count FROM product WHERE id=?", Int::class.java, id))
        assertEquals("TEMPORARY", jdbc.queryForObject("SELECT status::text FROM image WHERE id=?", String::class.java, image))
        assertNull(jdbc.queryForObject("SELECT entity_id FROM image WHERE id=?", Long::class.java, image))
        val updated = mvc.perform(put(path).header("Authorization", bearer(admin, "ADMIN"))
            .contentType(MediaType.APPLICATION_JSON).content(edit)).andReturn().response
        assertEquals(200, updated.status, updated.contentAsString)
        assertEquals("Giveaway22 edited", jdbc.queryForObject("SELECT name FROM product WHERE id=?", String::class.java, id))
        assertEquals(4, jdbc.queryForObject("SELECT giveaway_winners_count FROM product WHERE id=?", Int::class.java, id))
        val invalidExit = mvc.perform(put(path).header("Authorization", bearer(admin, "ADMIN"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"availability":"PURCHASABLE"}""")).andReturn().response
        assertEquals(400, invalidExit.status, invalidExit.contentAsString)
        assertEquals("GIVEAWAY", jdbc.queryForObject("SELECT availability::text FROM product WHERE id=?", String::class.java, id))
        val exit = mvc.perform(put(path).header("Authorization", bearer(admin, "ADMIN"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"availability":"PURCHASABLE","price":10}""")).andReturn().response
        assertEquals(200, exit.status, exit.contentAsString)
        assertEquals("PURCHASABLE", jdbc.queryForObject("SELECT availability::text FROM product WHERE id=?", String::class.java, id))
        assertEquals(false, jdbc.queryForObject("SELECT giveaway_enabled FROM product WHERE id=?", Boolean::class.java, id))
        val returnToGiveaway = mvc.perform(put(path).header("Authorization", bearer(admin, "ADMIN"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"availability":"GIVEAWAY","giveaway":{}}""")).andReturn().response
        assertEquals(200, returnToGiveaway.status, returnToGiveaway.contentAsString)
        assertEquals("GIVEAWAY", jdbc.queryForObject("SELECT availability::text FROM product WHERE id=?", String::class.java, id))
        assertEquals(true, jdbc.queryForObject("SELECT giveaway_enabled FROM product WHERE id=?", Boolean::class.java, id))
    }

    @Test
    fun `administrator can convert an ordinary product only with valid giveaway settings`() {
        val admin = participant("giveaway22freshconvert", "ADMIN")
        val id = jdbc.queryForObject("""INSERT INTO product(name,price,currency,participant_id,
            status,availability,count) VALUES ('Ordinary22',15,'RUB',?,'ACTIVE','PURCHASABLE',2)
            RETURNING id""", Long::class.java, admin)!!
        val mvc = mvc()
        val path = "/product/$id"
        val missing = mvc.perform(put(path).header("Authorization", bearer(admin, "ADMIN"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"availability":"GIVEAWAY"}""")).andReturn().response
        assertEquals(400, missing.status, missing.contentAsString)
        assertEquals("PURCHASABLE", jdbc.queryForObject("SELECT availability::text FROM product WHERE id=?",
            String::class.java, id))
        val start = Instant.now().plusSeconds(2100000)
        val end = start.plusSeconds(3600)
        val body = json.writeValueAsString(mapOf("availability" to "GIVEAWAY",
            "giveaway" to mapOf("telegramUrl" to "https://t.me/fresh", "startAt" to start.toString(),
                "endAt" to end.toString(), "winnersCount" to 2, "rules" to "Rules", "homeText" to "Home")))
        val converted = mvc.perform(put(path).header("Authorization", bearer(admin, "ADMIN"))
            .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().response
        assertEquals(200, converted.status, converted.contentAsString)
        assertEquals("GIVEAWAY", jdbc.queryForObject("SELECT availability::text FROM product WHERE id=?",
            String::class.java, id))
        assertNull(jdbc.queryForObject("SELECT count FROM product WHERE id=?", Int::class.java, id))
        assertEquals(true, jdbc.queryForObject("SELECT giveaway_enabled FROM product WHERE id=?",
            Boolean::class.java, id))
    }

    @Test
    fun `ordinary product with an unfinished order cannot become a giveaway`() {
        val admin = participant("giveaway22orderedowner", "ADMIN")
        val buyer = participant("giveaway22orderedbuyer", "USER")
        val id = jdbc.queryForObject("""INSERT INTO product(name,price,currency,participant_id,
            status,availability,count) VALUES ('Ordered22',15,'RUB',?,'ACTIVE','PURCHASABLE',2)
            RETURNING id""", Long::class.java, admin)!!
        val address = jdbc.queryForObject("INSERT INTO address(city) VALUES ('Test') RETURNING id",
            Long::class.java)!!
        val transfer = jdbc.queryForObject("""INSERT INTO transfer(sending,price,currency,participant_id)
            VALUES ('PRODUCT_PICKUP',0,'RUB',?) RETURNING id""", Long::class.java, admin)!!
        jdbc.update("""INSERT INTO "order"(seller_id,customer_id,count,status,product_id,address_id,
            transfer_id,product_name,product_unit_price,product_currency,product_availability)
            VALUES (?,?,1,'BOOKED',?,?,?,'Ordered22',15,'RUB','PURCHASABLE')""",
            admin, buyer, id, address, transfer)
        val start = Instant.now().plusSeconds(2500000)
        val body = json.writeValueAsString(mapOf("availability" to "GIVEAWAY",
            "giveaway" to mapOf("telegramUrl" to "https://t.me/ordered", "startAt" to start.toString(),
                "endAt" to start.plusSeconds(3600).toString(), "winnersCount" to 1,
                "rules" to "Rules", "homeText" to "Home")))
        val result = mvc().perform(put("/product/$id")
            .header("Authorization", bearer(admin, "ADMIN"))
            .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().response
        assertEquals(400, result.status, result.contentAsString)
        assertEquals("Товар с незавершёнными заказами нельзя перевести в розыгрыш",
            json.readTree(result.contentAsString)["message"].asText())
        assertEquals("PURCHASABLE", jdbc.queryForObject("SELECT availability::text FROM product WHERE id=?",
            String::class.java, id))
        assertEquals(false, jdbc.queryForObject("SELECT giveaway_enabled FROM product WHERE id=?",
            Boolean::class.java, id))
    }

    @Test
    fun `concurrent overlapping creations admit one complete giveaway`() {
        val admin = participant("giveaway22race", "ADMIN")
        val start = Instant.now().plusSeconds(700000)
        val settings = GiveawaySettings(telegramUrl = "https://t.me/race", startAt = start,
            endAt = start.plusSeconds(3600), winnersCount = 1, rules = "Rules", homeText = "Home")
        val gate = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val results = (1..2).map { number -> pool.submit<Boolean> {
                gate.await()
                try {
                    create.create(GiveawayCaller(admin, "ADMIN", false), GiveawayCreate("Giveaway22 race $number", null,
                        null, null, null, null, null, null, null, null, null, settings))
                    true
                } catch (_: com.model_store.modern.catalog.giveaway.admin.application.GiveawayFailure) {
                    false
                }
            } }
            gate.countDown()
            assertEquals(1, results.count { it.get(15, TimeUnit.SECONDS) })
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM product WHERE name LIKE 'Giveaway22 race %'",
                Int::class.java))
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `concurrent schedule updates serialize and preserve one enabled period`() {
        val admin = participant("giveaway22updates", "ADMIN")
        val first = giveaway(admin, "Giveaway22 update A", "TIME_EXPIRED", false, 900000, 903600)
        val second = giveaway(admin, "Giveaway22 update B", "TIME_EXPIRED", false, 900000, 903600)
        val gate = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val results = listOf(first, second).map { id -> pool.submit<Boolean> {
                gate.await()
                try {
                    adminGiveaways.update(id, GiveawayCaller(admin, "ADMIN", false), GiveawaySettings(enabled = true))
                    true
                } catch (_: com.model_store.modern.catalog.giveaway.admin.application.GiveawayFailure) {
                    false
                }
            } }
            gate.countDown()
            assertEquals(1, results.count { it.get(15, TimeUnit.SECONDS) })
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM product WHERE id IN (?,?) AND giveaway_enabled",
                Int::class.java, first, second))
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `status change and schedule update leave a blocked giveaway disabled`() {
        val admin = participant("giveaway22statusrace", "ADMIN")
        val id = giveaway(admin, "Giveaway22 status race", "TIME_EXPIRED", false, 1900000, 1903600)
        val gate = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val enable = pool.submit {
                gate.await()
                try {
                    adminGiveaways.update(id, GiveawayCaller(admin, "ADMIN", false),
                        GiveawaySettings(enabled = true))
                } catch (_: GiveawayFailure) { }
            }
            val block = pool.submit {
                gate.await()
                productCommands.changeStatus(id, ProductState.BLOCKED)
            }
            gate.countDown()
            enable.get(15, TimeUnit.SECONDS)
            block.get(15, TimeUnit.SECONDS)
            assertEquals("BLOCKED", jdbc.queryForObject("SELECT status::text FROM product WHERE id=?",
                String::class.java, id))
            assertEquals(false, jdbc.queryForObject("SELECT giveaway_enabled FROM product WHERE id=?",
                Boolean::class.java, id))
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `conversion and stock reservation serialize on the same product row`() {
        val admin = participant("giveaway22reservation", "ADMIN")
        val id = jdbc.queryForObject("""INSERT INTO product(name,price,currency,participant_id,
            status,availability,count) VALUES ('Reservation22',10,'RUB',?,'ACTIVE','PURCHASABLE',1)
            RETURNING id""", Long::class.java, admin)!!
        val start = Instant.now().plusSeconds(2300000)
        val settings = GiveawaySettings(telegramUrl = "https://t.me/reservation", startAt = start,
            endAt = start.plusSeconds(3600), winnersCount = 1, rules = "Rules", homeText = "Home")
        val gate = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val convert = pool.submit {
                gate.await()
                updateProduct.update(id, GiveawayCaller(admin, "ADMIN", false), ProductAvailability.GIVEAWAY,
                    GiveawayCreate(null, null, null, null, null, null, null, null, null,
                        null, null, settings))
            }
            val reserve = pool.submit<Boolean> {
                gate.await()
                try {
                    TransactionTemplate(transactionManager).executeWithoutResult {
                        reservation.reserve(listOf(ProductQuantity(id, 1)))
                    }
                    true
                } catch (_: ProductReservationFailure) { false }
            }
            gate.countDown()
            convert.get(15, TimeUnit.SECONDS)
            reserve.get(15, TimeUnit.SECONDS)
            assertEquals("GIVEAWAY", jdbc.queryForObject("SELECT availability::text FROM product WHERE id=?",
                String::class.java, id))
            assertNull(jdbc.queryForObject("SELECT count FROM product WHERE id=?", Int::class.java, id))
            assertEquals(true, jdbc.queryForObject("SELECT giveaway_enabled FROM product WHERE id=?",
                Boolean::class.java, id))
        } finally {
            pool.shutdownNow()
        }
    }

    private fun mvc() = MockMvcBuilders.webAppContextSetup(context)
        .addFilters<DefaultMockMvcBuilder>(context.getBean("springSecurityFilterChain") as Filter).build()

    private fun participant(login: String, role: String): Long = jdbc.queryForObject(
        """INSERT INTO participant(login,mail,password,status,role,deadline_sending,deadline_payment)
            VALUES (?,?, 'hash','ACTIVE',CAST(? AS participant_role),3,7) RETURNING id""",
        Long::class.java, login, "$login@example.test", role)!!

    private fun giveaway(owner: Long, name: String, status: String, enabled: Boolean,
                         startOffset: Long, endOffset: Long): Long = jdbc.queryForObject(
        """INSERT INTO product(name,description,price,currency,participant_id,status,availability,
            giveaway_enabled,giveaway_telegram_url,giveaway_start_at,giveaway_end_at,
            giveaway_winners_count,giveaway_rules,giveaway_home_text)
            VALUES (?, 'Prize',0,'RUB',?,CAST(? AS product_status),'GIVEAWAY',?,
            'https://t.me/prize',?, ?,2,'Rules','Home') RETURNING id""",
        Long::class.java, name, owner, status, enabled,
        java.sql.Timestamp.from(Instant.now().plusSeconds(startOffset)),
        java.sql.Timestamp.from(Instant.now().plusSeconds(endOffset)))!!

    private fun bearer(id: Long, role: String, type: String = "access", issuedBy: String? = null,
                       expiry: Instant = Instant.now().plusSeconds(3600),
                       signer: PrivateKey = privateKey): String {
        val builder = Jwts.builder().claim("id", id).claim("login", "giveaway22")
            .claim("role", role).claim("type", type)
        if (issuedBy != null) builder.claim("issuedBy", issuedBy)
        return "Bearer ${builder.expiration(Date.from(expiry)).signWith(signer).compact()}"
    }

    companion object {
        private val postgres = EmbeddedPostgres.start()
        private val privateKey: PrivateKey by lazy {
            val pem = ClassPathResource("keys/test_private_key.pem").inputStream.bufferedReader().use { it.readText() }
                .replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "")
                .replace(Regex("\\s+"), "")
            KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)))
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
