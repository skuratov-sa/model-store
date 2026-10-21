package com.model_store.modern.media.image.command

import com.amazonaws.services.s3.AmazonS3
import com.fasterxml.jackson.databind.ObjectMapper
import io.jsonwebtoken.Jwts
import com.model_store.modern.identity.participant.application.AgentAccessLock
import com.model_store.modern.identity.participant.application.ParticipantStore
import com.model_store.modern.media.image.command.application.ImageCommandRows
import com.model_store.modern.media.image.command.application.ProductImageAttachment
import com.model_store.modern.catalog.product.reservation.application.ProductReservation
import com.model_store.modern.catalog.product.reservation.application.ProductQuantity
import com.model_store.modern.media.image.command.domain.ImageToInsert
import com.model_store.modern.media.image.command.domain.ImageCommandDenied
import com.model_store.modern.media.image.command.domain.ImageCommandNotFound
import com.model_store.modern.media.image.command.domain.UploadTarget
import com.model_store.modern.media.storage.domain.StorageLocation
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import jakarta.servlet.Filter
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mock.web.MockMultipartFile
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
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.sql.SQLException
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.UUID
import java.util.concurrent.*
import javax.imageio.ImageIO

@SpringBootTest(properties = ["app.public-key-path=keys/test_public_key.pem", "app.private-key-path=keys/test_private_key.pem"])
@ActiveProfiles("modern")
class MediaLockOrderIntegrationTest {
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var rows: ImageCommandRows
    @Autowired lateinit var productImages: ProductImageAttachment
    @Autowired lateinit var reservation: ProductReservation
    @Autowired lateinit var access: AgentAccessLock
    @Autowired lateinit var store: ParticipantStore
    @Autowired lateinit var manager: PlatformTransactionManager
    @Autowired lateinit var context: WebApplicationContext
    @Autowired lateinit var json: ObjectMapper
    @MockitoBean lateinit var s3: AmazonS3
    @MockitoBean lateinit var mail: JavaMailSender
    private val tx get() = TransactionTemplate(manager)

    @Test fun `PRODUCT upload and accepted admin agent access complete without participant cycle`() = accessRace(false)
    @Test fun `PRODUCT delete and accepted admin agent access complete without participant cycle`() = accessRace(true)

    @Test fun `batch delete discovers every owner before locking the highest actor`() {
        val low = person(agent = true)
        val high = person(agent = true)
        val admin = person("ADMIN")
        val first = product(high)
        val second = product(low)
        val images = listOf(image(first, high), image(second, low))
        whileParticipantLocked(low, { rows.markDeleted(images, StorageLocation.PRODUCT, admin) }) {
            access.lockActiveAdminAndAgent(admin, low)
        }.also { assertNull(it) }
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM image WHERE id IN (?,?) AND status='DELETE'",
            Int::class.java, images[0], images[1]))
    }

    @Test fun `owner identity change while upload waits cannot add a new lower participant lock`() {
        val replacement = person(agent = true)
        val owner = person(agent = true)
        val admin = person("ADMIN")
        val product = product(owner)
        jdbc.dataSource!!.connection.use { replacementHolder ->
            replacementHolder.autoCommit = false
            try {
                // Compatible with the fixture's FK check, but a new FOR UPDATE participant
                // acquisition would block here. Denial must finish while this lock is held.
                replacementHolder.prepareStatement("SELECT id FROM participant WHERE id=? FOR NO KEY UPDATE").use { statement ->
                    statement.setLong(1, replacement)
                    statement.executeQuery().use { assertTrue(it.next()) }
                }
                val failure = whileParticipantLocked(owner, { upload(product, admin) }) {
                    jdbc.update("UPDATE product SET participant_id=? WHERE id=?", replacement, product)
                }
                assertInstanceOf(ImageCommandDenied::class.java, failure)
            } finally { replacementHolder.rollback() }
        }
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM image WHERE entity_id=? AND tag='PRODUCT'",
            Int::class.java, product))
    }

    @Test fun `delete refuses image reassociation after discovery and rolls back whole batch`() {
        val owner = person(agent = true)
        val admin = person("ADMIN")
        val first = product(owner)
        val second = product(owner)
        val stable = image(first, owner)
        val moved = image(first, owner)
        val failure = whileParticipantLocked(owner,
            { rows.markDeleted(listOf(moved, stable), StorageLocation.PRODUCT, admin) }) {
            jdbc.update("UPDATE image SET entity_id=? WHERE id=?", second, moved)
        }
        assertInstanceOf(ImageCommandDenied::class.java, failure)
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM image WHERE id IN (?,?) AND status='ACTIVE'",
            Int::class.java, stable, moved))
        assertEquals(second, jdbc.queryForObject("SELECT entity_id FROM image WHERE id=?", Long::class.java, moved))
    }

    @Test fun `actor revocation after preflight and stale JPA load is rechecked under lock`() {
        for (change in listOf("role='USER'", "status='BLOCKED'", "status='DELETED'")) {
            val owner = person(agent = true)
            val admin = person("ADMIN")
            val product = product(owner)
            rows.requireUploadAllowed(UploadTarget(StorageLocation.PRODUCT, product, admin))
            val loaded = CountDownLatch(1)
            val continueUpload = CountDownLatch(1)
            val executor = Executors.newSingleThreadExecutor()
            val workerPid = ArrayBlockingQueue<Int>(1)
            try {
                val worker = executor.submit<Throwable?> { outcome { tx.executeWithoutResult {
                    bounded(); workerPid.put(pid())
                    store.find(admin) // Deliberately populate the JPA persistence context before revocation.
                    loaded.countDown()
                    check(continueUpload.await(10, TimeUnit.SECONDS))
                    upload(product, admin)
                } } }
                assertTrue(loaded.await(10, TimeUnit.SECONDS))
                tx.executeWithoutResult {
                    bounded(); val holder = pid()
                    store.findForUpdate(admin)
                    continueUpload.countDown()
                    awaitBlocked(workerPid.poll(10, TimeUnit.SECONDS)!!, holder)
                    jdbc.update("UPDATE participant SET $change WHERE id=?", admin)
                }
                assertInstanceOf(ImageCommandDenied::class.java, worker.get(15, TimeUnit.SECONDS))
                assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM image WHERE entity_id=? AND tag='PRODUCT'",
                    Int::class.java, product))
            } finally {
                continueUpload.countDown(); executor.shutdownNow()
                assertTrue(executor.awaitTermination(20, TimeUnit.SECONDS))
            }
        }
    }

    @Test fun `fresh owner agent flag product status and image status preserve authorization`() {
        for (change in listOf("agent", "product", "image")) {
            val owner = person(agent = true)
            val admin = person("ADMIN")
            val product = product(owner)
            val image = image(product, owner)
            val failure = whileParticipantLocked(owner,
                { rows.markDeleted(listOf(image), StorageLocation.PRODUCT, admin) }) {
                when (change) {
                    "agent" -> jdbc.update("UPDATE participant SET is_agent=false WHERE id=?", owner)
                    "product" -> jdbc.update("UPDATE product SET status='DELETED' WHERE id=?", product)
                    else -> jdbc.update("UPDATE image SET status='TEMPORARY' WHERE id=?", image)
                }
            }
            if (change == "image") assertInstanceOf(ImageCommandNotFound::class.java, failure)
            else assertInstanceOf(ImageCommandDenied::class.java, failure)
            assertNotEquals("DELETE", jdbc.queryForObject("SELECT status::text FROM image WHERE id=?",
                String::class.java, image))
        }
    }

    @Test fun `blocked agent owner remains eligible and actor agent flag keeps the accepted media contract`() {
        val owner = person(agent = true)
        val admin = person("ADMIN", agent = true)
        val product = product(owner)
        jdbc.update("UPDATE participant SET status='BLOCKED' WHERE id=?", owner)
        upload(product, admin) // Task 12 checks actor status, ADMIN role and owner.is_agent only.
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM image WHERE entity_id=? AND tag='PRODUCT'",
            Int::class.java, product))
    }

    @Test fun `caller JPA rollback covers insert and batch deletion even after a caught media error`() {
        val owner = person(agent = true)
        val admin = person("ADMIN")
        val product = product(owner)
        val image = image(product, owner)
        assertThrows(IllegalStateException::class.java) { tx.executeWithoutResult {
            upload(product, admin)
            rows.markDeleted(listOf(image), StorageLocation.PRODUCT, admin)
            error("later caller failure")
        } }
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM image WHERE entity_id=? AND tag='PRODUCT'",
            Int::class.java, product))
        assertEquals("ACTIVE", jdbc.queryForObject("SELECT status::text FROM image WHERE id=?", String::class.java, image))
        assertThrows(org.springframework.transaction.UnexpectedRollbackException::class.java) { tx.executeWithoutResult {
            upload(product, admin)
            assertThrows(ImageCommandNotFound::class.java) {
                rows.markDeleted(listOf(image, Long.MAX_VALUE), StorageLocation.PRODUCT, admin)
            }
        } }
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM image WHERE entity_id=? AND tag='PRODUCT'",
            Int::class.java, product))
    }

    @Test fun `reservation and product image deletion finish before waiting media acquires images`() {
        val owner = person()
        val product = product(owner)
        val image = image(product, owner)
        val executor = Executors.newSingleThreadExecutor()
        val workerPid = ArrayBlockingQueue<Int>(1)
        lateinit var worker: Future<Throwable?>
        try {
            tx.executeWithoutResult {
                bounded(); val holder = pid()
                reservation.reserve(listOf(ProductQuantity(product, 1)))
                worker = executor.submit<Throwable?> { outcome { tx.executeWithoutResult {
                    bounded(); workerPid.put(pid())
                    rows.markDeleted(listOf(image), StorageLocation.PRODUCT, owner)
                } } }
                awaitBlocked(workerPid.poll(10, TimeUnit.SECONDS)!!, holder)
                // The accepted owner port uses NOWAIT. Media must not already hold the image
                // while waiting for this caller's product lock.
                productImages.markProductImagesDeleted(product)
            }
            assertInstanceOf(ImageCommandNotFound::class.java, worker.get(15, TimeUnit.SECONDS))
            assertEquals("DELETE", jdbc.queryForObject("SELECT status::text FROM image WHERE id=?", String::class.java, image))
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(20, TimeUnit.SECONDS))
        }
    }

    @Test fun `batch image locks are ascending even when request IDs are reversed`() {
        val owner = person()
        val product = product(owner)
        val low = image(product, owner)
        val high = image(product, owner)
        val executor = Executors.newSingleThreadExecutor()
        val workerPid = ArrayBlockingQueue<Int>(1)
        lateinit var worker: Future<Throwable?>
        try {
            tx.executeWithoutResult {
                bounded(); val holder = pid()
                jdbc.queryForObject("SELECT id FROM image WHERE id=? FOR UPDATE", Long::class.java, low)
                worker = executor.submit<Throwable?> { outcome { tx.executeWithoutResult {
                    bounded(); workerPid.put(pid())
                    rows.markDeleted(listOf(high, low), StorageLocation.PRODUCT, owner)
                } } }
                awaitBlocked(workerPid.poll(10, TimeUnit.SECONDS)!!, holder)
                assertEquals(high, jdbc.queryForObject("SELECT id FROM image WHERE id=? FOR UPDATE NOWAIT",
                    Long::class.java, high))
            }
            assertNull(worker.get(15, TimeUnit.SECONDS))
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(20, TimeUnit.SECONDS))
        }
    }

    @Test fun `ORDER association and terminal status are checked after waiting for actor`() {
        for (change in listOf("customer", "status")) {
            val buyer = person()
            val seller = person()
            val other = person()
            val product = product(seller)
            val address = jdbc.queryForObject("INSERT INTO address(country,city) VALUES ('RU','Moscow') RETURNING id",
                Long::class.java)!!
            val transfer = jdbc.queryForObject("INSERT INTO transfer(sending,participant_id) VALUES ('PRODUCT_PICKUP',?) RETURNING id",
                Long::class.java, seller)!!
            val order = jdbc.queryForObject("""INSERT INTO "order"(seller_id,customer_id,count,status,product_id,
                address_id,transfer_id,product_name,product_unit_price,product_currency,product_availability)
                VALUES (?,?,1,'AWAITING_PAYMENT',?,?,?,'snapshot',1,'RUB','PURCHASABLE') RETURNING id""",
                Long::class.java, seller, buyer, product, address, transfer)!!
            val target = UploadTarget(StorageLocation.ORDER, order, buyer)
            rows.requireUploadAllowed(target)
            val failure = whileParticipantLocked(buyer,
                { rows.insertBatch(target, listOf(ImageToInsert("order-${UUID.randomUUID()}.jpg", 10, 10))) }) {
                if (change == "customer") jdbc.update("UPDATE \"order\" SET customer_id=? WHERE id=?", other, order)
                else jdbc.update("UPDATE \"order\" SET status='COMPLETED' WHERE id=?", order)
            }
            assertInstanceOf(ImageCommandDenied::class.java, failure)
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM image WHERE tag='ORDER' AND entity_id=?",
                Int::class.java, order))
        }
    }

    @Test fun `read-only JPA and foreign caller transactions cannot partially commit media writes`() {
        val owner = person()
        val product = product(owner)
        val image = image(product, owner)
        val readOnly = TransactionTemplate(manager).apply { isReadOnly = true }
        for (deleting in listOf(false, true)) {
            val failure = assertThrows(org.springframework.dao.DataAccessException::class.java) {
                readOnly.executeWithoutResult {
                    if (deleting) rows.markDeleted(listOf(image), StorageLocation.PRODUCT, owner)
                    else upload(product, owner)
                }
            }
            assertEquals("25006", sqlState(failure)) // PostgreSQL refuses FOR UPDATE in a read-only transaction.
        }
        val foreign = TransactionTemplate(DataSourceTransactionManager(jdbc.dataSource!!))
        assertThrows(org.springframework.transaction.CannotCreateTransactionException::class.java) {
            foreign.executeWithoutResult { upload(product, owner) }
        }
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM image WHERE tag='PRODUCT' AND entity_id=?",
            Int::class.java, product))
        assertEquals("ACTIVE", jdbc.queryForObject("SELECT status::text FROM image WHERE id=?", String::class.java, image))
    }

    @Test fun `unassigned targets and SYSTEM permissions retain task 12 rules without new agent powers`() {
        val user = person()
        val admin = person("ADMIN")
        for (tag in listOf(StorageLocation.PRODUCT, StorageLocation.ORDER)) {
            val id = rows.insertBatch(UploadTarget(tag, null, user),
                listOf(ImageToInsert("unassigned-${UUID.randomUUID()}.jpg", 10, 10))).single()
            assertNull(jdbc.queryForObject("SELECT entity_id FROM image WHERE id=?", Long::class.java, id))
            assertEquals("TEMPORARY", jdbc.queryForObject("SELECT status::text FROM image WHERE id=?", String::class.java, id))
            assertEquals(user, jdbc.queryForObject("SELECT uploaded_by FROM image WHERE id=?", Long::class.java, id))
        }
        val system = UploadTarget(StorageLocation.SYSTEM, null, admin)
        rows.insertBatch(system, listOf(ImageToInsert("system-${UUID.randomUUID()}.jpg", 10, 10)))
        for (actor in listOf(user, Long.MAX_VALUE)) {
            assertThrows(ImageCommandDenied::class.java) {
                rows.insertBatch(system.copy(actorId = actor), listOf(ImageToInsert("denied.jpg", 10, 10)))
            }
        }
        assertThrows(ImageCommandDenied::class.java) {
            rows.insertBatch(UploadTarget(StorageLocation.PARTICIPANT, null, user), listOf(ImageToInsert("unbound.jpg", 10, 10)))
        } // The application, not rows, normalizes a null participant target to the actor.
        for (tag in listOf(StorageLocation.ORDER, StorageLocation.SYSTEM)) {
            assertThrows(ImageCommandDenied::class.java) { rows.markDeleted(listOf(Long.MAX_VALUE), tag, admin) }
        }
    }

    @Test fun `real RSA HTTP upload delete and metadata enforce actor ownership and current ADMIN role`() {
        val owner = person()
        val other = person(agent = true)
        val admin = person("ADMIN")
        val foreignProduct = product(other)
        val mvc = MockMvcBuilders.webAppContextSetup(context)
            .addFilters<DefaultMockMvcBuilder>(context.getBean("springSecurityFilterChain") as Filter).build()
        val png = ByteArrayOutputStream().also {
            ImageIO.write(BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB), "png", it)
        }.toByteArray()
        fun avatar() = multipart("/images").file(MockMultipartFile("files", "avatar.png", "image/png", png))
            .param("tag", "PARTICIPANT")
        fun expectCode(response: org.springframework.mock.web.MockHttpServletResponse, status: Int, code: String) {
            assertEquals(status, response.status, response.contentAsString)
            assertEquals(code, json.readTree(response.contentAsString).path("code").asText())
        }
        assertEquals(401, mvc.perform(avatar()).andReturn().response.status)
        assertEquals(401, mvc.perform(avatar().header("Authorization", "Bearer ${token(owner, type = "refresh")}"))
            .andReturn().response.status)
        // Media has always accepted a signed agent token as that actor, with the same DB checks.
        // Product commands have a separate agent-token restriction; do not import it into media.
        val agentUpload = mvc.perform(avatar().header("Authorization", "Bearer ${token(other, type = "agent_access")}"))
            .andReturn().response
        assertEquals(200, agentUpload.status, agentUpload.contentAsString)
        val agentImage = json.readTree(agentUpload.contentAsString).single().asLong()
        assertEquals(other, jdbc.queryForObject("SELECT entity_id FROM image WHERE id=?", Long::class.java, agentImage))
        assertEquals(other, jdbc.queryForObject("SELECT uploaded_by FROM image WHERE id=?", Long::class.java, agentImage))
        val uploaded = mvc.perform(avatar().header("Authorization", "Bearer ${token(owner)}")).andReturn().response
        assertEquals(200, uploaded.status, uploaded.contentAsString)
        val image = json.readTree(uploaded.contentAsString).single().asLong()
        assertEquals(owner, jdbc.queryForObject("SELECT entity_id FROM image WHERE id=?", Long::class.java, image))
        assertEquals(owner, jdbc.queryForObject("SELECT uploaded_by FROM image WHERE id=?", Long::class.java, image))
        val metadata = mvc.perform(get("/images/metadata").param("ids", image.toString())
            .header("Authorization", "Bearer ${token(owner)}")).andReturn().response
        assertEquals(200, metadata.status, metadata.contentAsString)
        assertEquals(image, json.readTree(metadata.contentAsString).single().path("id").asLong())
        expectCode(mvc.perform(delete("/images").param("tag", "PARTICIPANT").param("ids", image.toString())
            .header("Authorization", "Bearer ${token(other)}")).andReturn().response, 403, "ACCESS_DENIED")
        val ownerDelete = delete("/images").param("tag", "PARTICIPANT").param("ids", image.toString())
            .header("Authorization", "Bearer ${token(owner)}")
        assertEquals(200, mvc.perform(ownerDelete).andReturn().response.status)
        expectCode(mvc.perform(ownerDelete).andReturn().response, 404, "IMAGE_NOT_FOUND")
        rows.requireUploadAllowed(UploadTarget(StorageLocation.PRODUCT, foreignProduct, admin))
        jdbc.update("UPDATE participant SET role='USER' WHERE id=?", admin)
        expectCode(mvc.perform(multipart("/images").file(MockMultipartFile("files", "a.png", "image/png", png))
            .param("tag", "PRODUCT").param("entityId", foreignProduct.toString())
            .header("Authorization", "Bearer ${token(admin, "ADMIN")}")).andReturn().response, 403, "ACCESS_DENIED")
        for (tag in listOf("ORDER", "SYSTEM")) {
            expectCode(mvc.perform(delete("/images").param("tag", tag).param("ids", image.toString())
                .header("Authorization", "Bearer ${token(owner)}")).andReturn().response, 403, "ACCESS_DENIED")
        }
    }

    private fun upload(product: Long, actor: Long) = rows.insertBatch(
        UploadTarget(StorageLocation.PRODUCT, product, actor),
        listOf(ImageToInsert("race-${UUID.randomUUID()}.jpg", 10, 10)))

    /** Observe the actual blocked backend before mutating; no timing guesses establish the race. */
    private fun whileParticipantLocked(id: Long, action: () -> Unit, change: () -> Unit): Throwable? {
        val executor = Executors.newSingleThreadExecutor()
        val workerPid = ArrayBlockingQueue<Int>(1)
        lateinit var worker: Future<Throwable?>
        try {
            tx.executeWithoutResult {
                bounded(); val holder = pid()
                store.findForUpdate(id)
                worker = executor.submit<Throwable?> { outcome { tx.executeWithoutResult {
                    bounded(); workerPid.put(pid()); action()
                } } }
                awaitBlocked(workerPid.poll(10, TimeUnit.SECONDS)!!, holder)
                change()
            }
            return worker.get(15, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(20, TimeUnit.SECONDS))
        }
    }

    private fun accessRace(deleting: Boolean) {
        val owner = person(agent = true)
        val admin = person("ADMIN")
        assertTrue(owner < admin)
        val product = product(owner)
        val image = image(product, owner)
        val executor = Executors.newFixedThreadPool(2)
        val locked = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val ownerPid = ArrayBlockingQueue<Int>(1)
        val mediaPid = ArrayBlockingQueue<Int>(1)
        try {
            val guard = executor.submit<Throwable?> { outcome { tx.executeWithoutResult {
                bounded(); ownerPid.put(pid())
                store.findForUpdate(owner)
                locked.countDown()
                check(proceed.await(10, TimeUnit.SECONDS))
                access.lockActiveAdminAndAgent(admin, owner)
            } } }
            assertTrue(locked.await(10, TimeUnit.SECONDS))
            val media = executor.submit<Throwable?> { outcome { tx.executeWithoutResult {
                bounded(); mediaPid.put(pid())
                if (deleting) rows.markDeleted(listOf(image), StorageLocation.PRODUCT, admin)
                else rows.insertBatch(UploadTarget(StorageLocation.PRODUCT, product, admin),
                    listOf(ImageToInsert("race-${UUID.randomUUID()}.jpg", 10, 10)))
            } } }
            awaitBlocked(mediaPid.poll(10, TimeUnit.SECONDS)!!, ownerPid.poll(10, TimeUnit.SECONDS)!!)
            proceed.countDown()
            val outcomes = listOf(guard.get(15, TimeUnit.SECONDS), media.get(15, TimeUnit.SECONDS))
            println("MEDIA_LOCK_RACE deleting=$deleting sqlStates=${outcomes.map(::sqlState)} outcomes=$outcomes")
            assertTrue(outcomes.all { it == null }, outcomes.toString())
            assertEquals(if (deleting) "DELETE" else "ACTIVE", jdbc.queryForObject(
                "SELECT status::text FROM image WHERE id=?", String::class.java, image))
        } finally {
            proceed.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(20, TimeUnit.SECONDS))
        }
    }

    private fun outcome(action: () -> Unit): Throwable? = try { action(); null } catch (failure: Throwable) { failure }
    private fun sqlState(error: Throwable?): String? = generateSequence(error) { it.cause }
        .filterIsInstance<SQLException>().firstOrNull()?.sqlState
    private fun pid() = jdbc.queryForObject("SELECT pg_backend_pid()", Int::class.java)!!
    private fun bounded() {
        jdbc.execute("SET LOCAL lock_timeout='8s'")
        jdbc.execute("SET LOCAL statement_timeout='12s'")
        jdbc.execute("SET LOCAL deadlock_timeout='100ms'")
    }
    private fun awaitBlocked(waiter: Int, blocker: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (System.nanoTime() < deadline) {
            if (jdbc.queryForObject("SELECT ?=ANY(pg_blocking_pids(?))", Boolean::class.java, blocker, waiter) == true) return
            Thread.yield()
        }
        fail<Unit>("Backend $waiter did not block behind $blocker")
    }
    private fun person(role: String = "USER", agent: Boolean = false): Long = jdbc.queryForObject(
        """INSERT INTO participant(login,password,role,status,is_agent)
           VALUES (?,'test-hash',?::participant_role,'ACTIVE',?) RETURNING id""",
        Long::class.java, UUID.randomUUID().toString(), role, agent)!!
    private fun product(owner: Long): Long = jdbc.queryForObject(
        "INSERT INTO product(name,price,currency,participant_id) VALUES (?,1,'RUB',?) RETURNING id",
        Long::class.java, "lock-${UUID.randomUUID()}", owner)!!
    private fun image(product: Long, owner: Long): Long = jdbc.queryForObject(
        """INSERT INTO image(filename,tag,status,entity_id,uploaded_by)
           VALUES (?,'PRODUCT','ACTIVE',?,?) RETURNING id""", Long::class.java,
        "race-${UUID.randomUUID()}.jpg", product, owner)!!

    companion object {
        private val postgres = EmbeddedPostgres.start()
        private val privateKey: PrivateKey by lazy {
            val pem = ClassPathResource("keys/test_private_key.pem").inputStream.bufferedReader().use { it.readText() }
                .replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "")
                .replace(Regex("\\s+"), "")
            KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)))
        }
        private fun token(id: Long, role: String = "USER", type: String = "access"): String = Jwts.builder()
            .claim("id", id).claim("login", "media12-3").claim("role", role).claim("type", type)
            .apply { if (type == "agent_access") claim("issuedBy", "admin") }
            .expiration(Date.from(Instant.now().plusSeconds(3600))).signWith(privateKey).compact()
        @JvmStatic @DynamicPropertySource fun database(registry: DynamicPropertyRegistry) {
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
