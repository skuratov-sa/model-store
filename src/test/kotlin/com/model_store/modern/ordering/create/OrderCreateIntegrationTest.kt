package com.model_store.modern.ordering.create

import com.amazonaws.services.s3.AmazonS3
import com.fasterxml.jackson.databind.ObjectMapper
import com.model_store.exception.constant.ErrorCode
import com.model_store.modern.catalog.basket.application.BasketUseCases
import com.model_store.modern.catalog.basket.application.BasketFailure
import com.model_store.modern.catalog.product.command.application.ProductCommands
import com.model_store.modern.catalog.product.command.domain.ProductChanges
import com.model_store.modern.ordering.create.application.CreateOrder
import com.model_store.modern.ordering.create.domain.OrderLine
import com.model_store.modern.ordering.create.domain.OrderFailure
import com.model_store.modern.ordering.create.domain.OrderProblem
import com.model_store.modern.shared.domain.Actor
import com.model_store.modern.seller.address.application.AddressUseCases
import com.model_store.modern.seller.transfer.application.TransferUseCases
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootTest(properties = [
    "app.public-key-path=keys/test_public_key.pem",
    "app.private-key-path=keys/test_private_key.pem",
])
@ActiveProfiles("modern")
class OrderCreateIntegrationTest {
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var context: WebApplicationContext
    @Autowired lateinit var json: ObjectMapper
    @Autowired lateinit var orders: CreateOrder
    @Autowired lateinit var basket: BasketUseCases
    @Autowired lateinit var productCommands: ProductCommands
    @Autowired lateinit var addressCommands: AddressUseCases
    @Autowired lateinit var transferCommands: TransferUseCases
    @MockitoBean lateinit var s3: AmazonS3
    @MockitoBean lateinit var mail: JavaMailSender

    @Test
    fun `HTTP preparation booking snapshots history and basket parity`() {
        val buyer = participant("order24buyer")
        val seller = participant("order24seller")
        val address = address(buyer)
        val transfer = transfer(seller)
        val product = product(seller, 4, price = 23.5f, name = "Order24 product")
        basket.add(buyer, product, 2)
        val mvc = mvc()
        val header = "Bearer ${token(buyer)}"
        val prepare = mvc.perform(get("/order").header("Authorization", header).param("productId", "$product"))
            .andReturn().response
        assertEquals(200, prepare.status, prepare.contentAsString)
        val prepared = json.readTree(prepare.contentAsString)
        assertEquals(setOf("addresses", "sellerTransfers"), prepared.fieldNames().asSequence().toSet())
        assertEquals(address, prepared["addresses"][0]["id"].asLong())
        assertEquals("ACTIVE", prepared["addresses"][0]["status"].asText())
        assertEquals(transfer, prepared["sellerTransfers"][0]["id"].asLong())
        assertEquals(setOf("id", "country", "city", "street", "houseNumber", "apartmentNumber",
            "index", "status", "fullAddress"), prepared["addresses"][0].fieldNames().asSequence().toSet())
        assertTrue(prepared["addresses"][0]["street"].isNull)
        assertEquals(setOf("id", "sending", "price", "currency", "participantId", "status"),
            prepared["sellerTransfers"][0].fieldNames().asSequence().toSet())

        val booked = mvc.perform(post("/order/BOOKED").header("Authorization", header)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""[{"productId":$product,"count":2,"addressId":$address,"transferId":$transfer,
                "comment":"new","sellerId":$buyer,"productUnitPrice":0,"status":"COMPLETED"}]"""))
            .andReturn().response
        assertEquals(200, booked.status, booked.contentAsString)
        val id = json.readTree(booked.contentAsString)[0].asLong()
        assertEquals(2, stock(product))
        assertEquals(0, count("SELECT count(*) FROM product_basket WHERE participant_id=$buyer AND product_id=$product"))
        assertEquals(1, count("SELECT count(*) FROM order_status_history WHERE order_id=$id AND status='BOOKED'"))
        val row = jdbc.queryForMap("""SELECT seller_id, customer_id, count, status::text AS status,
            product_name, product_unit_price, product_currency::text AS currency,
            product_availability::text AS availability, total_price, prepayment_amount,
            comment, created_at FROM "order" WHERE id=?""", id)
        assertEquals(seller, (row["seller_id"] as Number).toLong())
        assertEquals(buyer, (row["customer_id"] as Number).toLong())
        assertEquals("BOOKED", row["status"])
        assertEquals("Order24 product", row["product_name"])
        assertEquals(23.5f, (row["product_unit_price"] as Number).toFloat())
        assertEquals("RUB", row["currency"])
        assertEquals("PURCHASABLE", row["availability"])
        assertEquals(47f, (row["total_price"] as Number).toFloat())
        assertEquals(0f, (row["prepayment_amount"] as Number).toFloat())
        assertNotNull(row["created_at"])
        jdbc.update("UPDATE product SET price=99,name='changed' WHERE id=?", product)
        assertEquals(23.5f, jdbc.queryForObject("SELECT product_unit_price FROM \"order\" WHERE id=?", Float::class.java, id))
    }

    @Test
    fun `invalid second line rolls back reservation orders history and basket`() {
        val buyer = participant("order24rollbackBuyer")
        val seller = participant("order24rollbackSeller")
        val address = address(buyer)
        val transfer = transfer(seller)
        val first = product(seller, 2)
        val second = product(seller, 1)
        basket.add(buyer, first, 1)
        val before = count("SELECT count(*) FROM \"order\"")
        val error = assertThrows(OrderFailure::class.java) {
            orders.book(Actor(buyer, "buyer", "USER"), listOf(
                OrderLine(first, 1, address, transfer, null),
                OrderLine(second, 2, address, transfer, null),
            ))
        }
        assertEquals(OrderProblem.OUT_OF_STOCK, error.problem)
        assertEquals(before, count("SELECT count(*) FROM \"order\""))
        assertEquals(2, stock(first))
        assertEquals(1, stock(second))
        assertEquals(1, count("SELECT count(*) FROM product_basket WHERE participant_id=$buyer AND product_id=$first"))
    }

    @Test
    fun `later database failure rolls back earlier insert trigger basket removal and stock`() {
        val buyer = participant("order24lateBuyer")
        val seller = participant("order24lateSeller")
        val address = address(buyer)
        val transfer = transfer(seller)
        val first = product(seller, 2)
        val second = product(seller, 2)
        basket.add(buyer, first, 1)
        val beforeOrders = count("SELECT count(*) FROM \"order\"")
        val beforeHistory = count("SELECT count(*) FROM order_status_history")
        assertThrows(Exception::class.java) {
            orders.book(Actor(buyer, "buyer", "USER"), listOf(
                OrderLine(first, 1, address, transfer, "ok"),
                OrderLine(second, 1, address, transfer, "x".repeat(501)),
            ))
        }
        assertEquals(beforeOrders, count("SELECT count(*) FROM \"order\""))
        assertEquals(beforeHistory, count("SELECT count(*) FROM order_status_history"))
        assertEquals(2, stock(first))
        assertEquals(2, stock(second))
        assertEquals(1, count("SELECT count(*) FROM product_basket WHERE participant_id=$buyer AND product_id=$first"))
    }

    @Test
    fun `duplicates overflow own and agent goods are rejected`() {
        val buyer = participant("order24validationBuyer")
        val seller = participant("order24validationSeller")
        val agent = participant("order24agent", agent = true)
        val address = address(buyer)
        val transfer = transfer(seller)
        val item = product(seller, null)
        val agentItem = product(agent, 2)
        val own = product(buyer, 2)
        val ownTransfer = transfer(buyer)
        val agentTransfer = transfer(agent)
        val actor = Actor(buyer, "buyer", "USER")
        // Legacy preparation only requires a visible product; booking enforces ownership rules.
        assertEquals(address, orders.prepare(actor, own).addresses.single().id)
        assertEquals(ownTransfer, orders.prepare(actor, own).sellerTransfers.single().id)
        assertEquals(agentTransfer, orders.prepare(actor, agentItem).sellerTransfers.single().id)
        assertEquals(OrderProblem.COUNT_INVALID, assertThrows(OrderFailure::class.java) {
            orders.book(actor, listOf(OrderLine(item, -1, address, transfer, null)))
        }.problem)
        assertEquals(OrderProblem.COUNT_INVALID, assertThrows(OrderFailure::class.java) {
            orders.book(actor, listOf(OrderLine(item, Int.MAX_VALUE, address, transfer, null),
                OrderLine(item, 1, address, transfer, null)))
        }.problem)
        // Legacy merges duplicate product lines and keeps fields from the first line.
        val ids = orders.book(actor, listOf(OrderLine(item, 1, address, transfer, "first"),
            OrderLine(item, 2, address, transfer, "second")))
        assertEquals(1, ids.size)
        assertEquals(3, jdbc.queryForObject("SELECT count FROM \"order\" WHERE id=?", Int::class.java, ids.single()))
        assertNull(stock(item))
        assertEquals("first", jdbc.queryForObject("SELECT comment FROM \"order\" WHERE id=?", String::class.java, ids.single()))
        assertEquals(OrderProblem.OWN_PRODUCT_ORDER_FORBIDDEN, assertThrows(OrderFailure::class.java) {
            orders.book(actor, listOf(OrderLine(own, 1, address, transfer, null)))
        }.problem)
        val ownHttp = mvc().perform(post("/order/BOOKED")
            .header("Authorization", "Bearer ${token(buyer)}")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""[{"productId":$own,"count":1,"addressId":$address,"transferId":$transfer}]"""))
            .andReturn().response
        assertEquals(400, ownHttp.status)
        assertEquals("Нельзя оформить заказ на собственный товар",
            json.readTree(ownHttp.contentAsString)["message"].asText())
        assertEquals(OrderProblem.AGENT_PRODUCT, assertThrows(OrderFailure::class.java) {
            orders.book(actor, listOf(OrderLine(agentItem, 1, address, transfer, null)))
        }.problem)
    }

    @Test
    fun `real RSA authorization and reference checks`() {
        val buyer = participant("order24authBuyer")
        val seller = participant("order24authSeller")
        val address = address(buyer)
        val transfer = transfer(seller)
        val item = product(seller, 3)
        val mvc = mvc()
        val body = """[{"productId":$item,"count":1,"addressId":$address,"transferId":$transfer}]"""
        fun request(token: String?) = post("/order/BOOKED").contentType(MediaType.APPLICATION_JSON).content(body)
            .apply { if (token != null) header("Authorization", "Bearer $token") }
        assertEquals(401, mvc.perform(request(null)).andReturn().response.status)
        assertEquals(401, mvc.perform(request(token(buyer, type = "refresh"))).andReturn().response.status)
        assertEquals(403, mvc.perform(request(token(buyer, type = "agent_access"))).andReturn().response.status)
        assertEquals(200, mvc.perform(request(token(buyer, role = "ADMIN"))).andReturn().response.status)
        val foreign = address(participant("order24foreign"))
        val invalid = mvc.perform(post("/order/BOOKED").header("Authorization", "Bearer ${token(buyer)}")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""[{"productId":$item,"count":1,"addressId":$foreign,"transferId":$transfer}]"""))
            .andReturn().response
        assertEquals(400, invalid.status)
        assertEquals("INVALID_ADDRESS", json.readTree(invalid.contentAsString)["code"].asText())
        val deletedTransfer = transfer(seller)
        jdbc.update("UPDATE transfer SET status='DELETED' WHERE id=?", deletedTransfer)
        val rejected = mvc.perform(post("/order/BOOKED").header("Authorization", "Bearer ${token(buyer)}")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""[{"productId":$item,"count":1,"addressId":$address,"transferId":$deletedTransfer}]"""))
            .andReturn().response
        assertEquals(400, rejected.status, rejected.contentAsString)
        assertEquals("INVALID_TRANSFER", json.readTree(rejected.contentAsString)["code"].asText())
    }

    @Test
    fun `preparation and booking distinguish no active transfer from wrong transfer`() {
        val buyer = participant("order24emptyBuyer")
        val seller = participant("order24emptySeller")
        val item = product(seller, 2)
        val mvc = mvc()
        val header = "Bearer ${token(buyer)}"
        val withoutTransfer = mvc.perform(get("/order").header("Authorization", header)
            .param("productId", "$item")).andReturn().response
        assertEquals(404, withoutTransfer.status, withoutTransfer.contentAsString)
        assertEquals("TRANSFER_NOT_FOUND", json.readTree(withoutTransfer.contentAsString)["code"].asText())
        val transfer = transfer(seller)
        val noAddress = mvc.perform(get("/order").header("Authorization", header)
            .param("productId", "$item")).andReturn().response
        assertEquals(200, noAddress.status, noAddress.contentAsString)
        assertEquals(0, json.readTree(noAddress.contentAsString)["addresses"].size())
        jdbc.update("UPDATE transfer SET status='DELETED' WHERE id=?", transfer)
        val address = address(buyer)
        val body = """[{"productId":$item,"count":1,"addressId":$address,"transferId":$transfer}]"""
        val booked = mvc.perform(post("/order/BOOKED").header("Authorization", header)
            .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().response
        assertEquals(404, booked.status, booked.contentAsString)
        assertEquals("TRANSFER_NOT_FOUND", json.readTree(booked.contentAsString)["code"].asText())
    }

    @Test
    fun `signed blocked and deleted buyers keep legacy BOOKED access`() {
        val seller = participant("order24statusSeller")
        val blocked = participant("order24blockedBuyer")
        val deleted = participant("order24deletedBuyer")
        val addressBlocked = address(blocked)
        val addressDeleted = address(deleted)
        val transfer = transfer(seller)
        val item = product(seller, 2)
        jdbc.update("UPDATE participant SET status='BLOCKED' WHERE id=?", blocked)
        jdbc.update("UPDATE participant SET status='DELETED' WHERE id=?", deleted)
        val mvc = mvc()
        for ((buyer, address) in listOf(blocked to addressBlocked, deleted to addressDeleted)) {
            val response = mvc.perform(post("/order/BOOKED").header("Authorization", "Bearer ${token(buyer)}")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""[{"productId":$item,"count":1,"addressId":$address,"transferId":$transfer}]"""))
                .andReturn().response
            assertEquals(200, response.status, response.contentAsString)
        }
        assertEquals(0, stock(item))
    }

    @Test
    fun `two buyers cannot book the same last item`() {
        val buyerA = participant("order24raceA")
        val buyerB = participant("order24raceB")
        val seller = participant("order24raceSeller")
        val transfer = transfer(seller)
        val addressA = address(buyerA)
        val addressB = address(buyerB)
        val item = product(seller, 1)
        val ready = CountDownLatch(2)
        val go = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val jobs = listOf(buyerA to addressA, buyerB to addressB).map { (buyer, address) ->
                pool.submit<String> {
                    ready.countDown()
                    assertTrue(go.await(5, TimeUnit.SECONDS))
                    try {
                        orders.book(Actor(buyer, "buyer", "USER"),
                            listOf(OrderLine(item, 1, address, transfer, null)))
                        "BOOKED"
                    } catch (failure: OrderFailure) { failure.problem.name }
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            go.countDown()
            assertEquals(setOf("BOOKED", "OUT_OF_STOCK"), jobs.map { it.get(15, TimeUnit.SECONDS) }.toSet())
            assertEquals(0, stock(item))
            assertEquals(1, count("SELECT count(*) FROM \"order\" WHERE product_id=$item"))
        } finally { go.countDown(); pool.shutdownNow() }
    }

    @Test
    fun `reciprocal buyers lock participants in the same order`() {
        val first = participant("order24reciprocalA")
        val second = participant("order24reciprocalB")
        val firstAddress = address(first)
        val secondAddress = address(second)
        val firstTransfer = transfer(first)
        val secondTransfer = transfer(second)
        val firstProduct = product(first, 1)
        val secondProduct = product(second, 1)
        val ready = CountDownLatch(2)
        val go = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val jobs = listOf(
                Triple(first, firstAddress, secondProduct to secondTransfer),
                Triple(second, secondAddress, firstProduct to firstTransfer),
            ).map { (buyer, address, offer) -> pool.submit<List<Long>> {
                ready.countDown()
                assertTrue(go.await(5, TimeUnit.SECONDS))
                orders.book(Actor(buyer, "buyer", "USER"),
                    listOf(OrderLine(offer.first, 1, address, offer.second, null)))
            } }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            go.countDown()
            jobs.forEach { assertEquals(1, it.get(15, TimeUnit.SECONDS).size) }
            assertEquals(0, stock(firstProduct))
            assertEquals(0, stock(secondProduct))
        } finally { go.countDown(); pool.shutdownNow() }
    }

    @Test
    fun `address and transfer removal serialize with booking`() {
        val buyer = participant("order24removeBuyer")
        val seller = participant("order24removeSeller")
        val firstAddress = address(buyer)
        val transfer = transfer(seller)
        val firstProduct = product(seller, 1)
        val ready = CountDownLatch(2)
        val go = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val booking = pool.submit<String> {
                ready.countDown()
                assertTrue(go.await(5, TimeUnit.SECONDS))
                try {
                    orders.book(Actor(buyer, "buyer", "USER"),
                        listOf(OrderLine(firstProduct, 1, firstAddress, transfer, null)))
                    "BOOKED"
                } catch (failure: OrderFailure) { failure.problem.name }
            }
            val deletion = pool.submit {
                ready.countDown()
                assertTrue(go.await(5, TimeUnit.SECONDS))
                addressCommands.delete(buyer, firstAddress)
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            go.countDown()
            val outcome = booking.get(15, TimeUnit.SECONDS)
            deletion.get(15, TimeUnit.SECONDS)
            assertTrue(outcome == "BOOKED" || outcome == "INVALID_ADDRESS")
            assertEquals(if (outcome == "BOOKED") 0 else 1, stock(firstProduct))
        } finally { go.countDown(); pool.shutdownNow() }

        val nextAddress = address(buyer)
        val nextProduct = product(seller, 1)
        val nextReady = CountDownLatch(2)
        val nextGo = CountDownLatch(1)
        val nextPool = Executors.newFixedThreadPool(2)
        try {
            val booking = nextPool.submit<String> {
                nextReady.countDown()
                assertTrue(nextGo.await(5, TimeUnit.SECONDS))
                try {
                    orders.book(Actor(buyer, "buyer", "USER"),
                        listOf(OrderLine(nextProduct, 1, nextAddress, transfer, null)))
                    "BOOKED"
                } catch (failure: OrderFailure) { failure.problem.name }
            }
            val deletion = nextPool.submit {
                nextReady.countDown()
                assertTrue(nextGo.await(5, TimeUnit.SECONDS))
                transferCommands.delete(seller, transfer)
            }
            assertTrue(nextReady.await(5, TimeUnit.SECONDS))
            nextGo.countDown()
            val outcome = booking.get(15, TimeUnit.SECONDS)
            deletion.get(15, TimeUnit.SECONDS)
            assertTrue(outcome == "BOOKED" || outcome == "TRANSFER_NOT_FOUND")
            assertEquals(if (outcome == "BOOKED") 0 else 1, stock(nextProduct))
        } finally { nextGo.countDown(); nextPool.shutdownNow() }
    }

    @Test
    fun `multiple positions and preorder use captured price and prepayment`() {
        val buyer = participant("order24multiBuyer")
        val seller = participant("order24multiSeller")
        val address = address(buyer)
        val transfer = transfer(seller)
        val ordinary = product(seller, 3, price = 12.5f)
        val preorder = product(seller, 2, price = 50f, availability = "PREORDER", prepayment = 10f)
        val ids = orders.book(Actor(buyer, "buyer", "USER"), listOf(
            OrderLine(preorder, 2, address, transfer, null),
            OrderLine(ordinary, 1, address, transfer, null),
        ))
        assertEquals(2, ids.size)
        assertEquals(2, stock(ordinary))
        assertEquals(2, stock(preorder))
        val rows = jdbc.query("""SELECT product_id,total_price,prepayment_amount,
            product_availability::text AS availability FROM "order" WHERE id IN (?,?)""",
            { rs, _ -> Triple(rs.getLong("product_id"),
                (rs.getObject("total_price") as Number).toFloat(),
                (rs.getObject("prepayment_amount") as Number).toFloat()) to rs.getString("availability") },
            ids[0], ids[1]).toMap()
        assertEquals("PURCHASABLE", rows[Triple(ordinary, 12.5f, 0f)])
        assertEquals("PREORDER", rows[Triple(preorder, 80f, 20f)])
        assertEquals(2, count("SELECT count(*) FROM order_status_history WHERE order_id IN (${ids.joinToString()}) AND status='BOOKED'"))
        assertEquals(OrderProblem.OUT_OF_STOCK, assertThrows(OrderFailure::class.java) {
            orders.book(Actor(buyer, "buyer", "USER"), listOf(OrderLine(preorder, 3, address, transfer, null)))
        }.problem)
    }

    @Test
    fun `booking serializes with basket add update and product command`() {
        val buyer = participant("order24contentionBuyer")
        val seller = participant("order24contentionSeller")
        val address = address(buyer)
        val transfer = transfer(seller)
        val first = product(seller, 1)
        val second = product(seller, 1)
        val third = product(seller, 1)
        basket.add(buyer, second, 1)

        fun race(book: () -> Unit, other: () -> Unit) {
            val ready = CountDownLatch(2)
            val go = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(2)
            try {
                val jobs = listOf(book, other).map { action -> pool.submit {
                    ready.countDown()
                    assertTrue(go.await(5, TimeUnit.SECONDS))
                    action()
                } }
                assertTrue(ready.await(5, TimeUnit.SECONDS))
                go.countDown()
                jobs.forEach { it.get(15, TimeUnit.SECONDS) }
            } finally { go.countDown(); pool.shutdownNow() }
        }
        fun book(id: Long) { orders.book(Actor(buyer, "buyer", "USER"),
            listOf(OrderLine(id, 1, address, transfer, null))) }
        race({ book(first) }, { try { basket.add(buyer, first, 1) } catch (_: BasketFailure) {} })
        race({ book(second) }, { try { basket.update(buyer, second, 1) } catch (_: BasketFailure) {} })
        race({ book(third) }, { productCommands.update(third, seller, "USER", ProductChanges(price = 11f)) })
        assertEquals(0, stock(first))
        assertEquals(0, stock(second))
        assertEquals(0, stock(third))
        assertEquals(0, count("SELECT count(*) FROM product_basket WHERE participant_id=$buyer AND product_id IN ($first,$second)"))
        val snapshotPrice = jdbc.queryForObject("SELECT product_unit_price FROM \"order\" WHERE product_id=?", Float::class.java, third)!!
        assertTrue(snapshotPrice == 10f || snapshotPrice == 11f)
        assertEquals(11f, jdbc.queryForObject("SELECT price FROM product WHERE id=?", Float::class.java, third))
    }

    private fun mvc() = MockMvcBuilders.webAppContextSetup(context).apply {
        addFilters<DefaultMockMvcBuilder>(context.getBean("springSecurityFilterChain") as Filter)
    }.build()

    private fun participant(login: String, agent: Boolean = false): Long = jdbc.queryForObject(
        """INSERT INTO participant(login,mail,password,status,role,is_agent) VALUES (?,?,'hash','ACTIVE','USER',?) RETURNING id""",
        Long::class.java, login, "$login@example.test", agent,
    )!!

    private fun address(owner: Long): Long {
        val id = jdbc.queryForObject("INSERT INTO address(country,city) VALUES ('RU','Moscow') RETURNING id", Long::class.java)!!
        jdbc.update("INSERT INTO participant_address(participant_id,address_id) VALUES (?,?)", owner, id)
        return id
    }

    private fun transfer(owner: Long) = jdbc.queryForObject(
        """INSERT INTO transfer(sending,price,currency,participant_id) VALUES ('PRODUCT_PICKUP',0,'RUB',?) RETURNING id""",
        Long::class.java, owner,
    )!!

    private fun product(owner: Long, stock: Int?, price: Float = 10f,
                        name: String = "Order24 product ${productSequence.incrementAndGet()}",
                        availability: String = "PURCHASABLE", prepayment: Float? = null) = jdbc.queryForObject(
        """INSERT INTO product(name,price,currency,participant_id,status,availability,count,prepayment_amount,used)
           VALUES (?,?,'RUB',?,'ACTIVE',CAST(? AS product_availability),?,?,false) RETURNING id""",
        Long::class.java, name, price, owner, availability, stock, prepayment,
    )!!

    private fun stock(product: Long): Int? = jdbc.queryForObject("SELECT count FROM product WHERE id=?", Int::class.java, product)
    private fun count(sql: String) = jdbc.queryForObject(sql, Int::class.java)!!

    companion object {
        private val postgres = EmbeddedPostgres.start()
        private val productSequence = AtomicLong()
        private val privateKey: PrivateKey by lazy {
            val pem = ClassPathResource("keys/test_private_key.pem").inputStream.bufferedReader().use { it.readText() }
                .replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "")
                .replace(Regex("\\s+"), "")
            KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)))
        }
        private fun token(id: Long, role: String = "USER", type: String = "access") = Jwts.builder()
            .claim("id", id).claim("login", "order24").claim("role", role).claim("type", type)
            .apply { if (type == "agent_access") claim("issuedBy", "admin") }
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
