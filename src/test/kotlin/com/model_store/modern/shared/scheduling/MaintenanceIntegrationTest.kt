package com.model_store.modern.shared.scheduling

import com.amazonaws.services.s3.AmazonS3
import com.model_store.modern.catalog.product.maintenance.application.*
import com.model_store.modern.catalog.product.maintenance.infrastructure.JdbcProductMaintenance
import com.model_store.modern.catalog.product.maintenance.infrastructure.JdbcDeletedProductForImageCleanup
import com.model_store.modern.catalog.basket.application.BasketStore
import com.model_store.modern.identity.participant.maintenance.application.MaintenanceParticipantLocks
import com.model_store.modern.identity.participant.maintenance.application.MaintenanceParticipantTransactionRequired
import com.model_store.modern.media.image.command.application.ImageCommandRows
import com.model_store.modern.media.image.command.application.ProductImageAttachment
import com.model_store.modern.media.image.command.application.OrderImageEvidence
import com.model_store.modern.media.image.command.application.OrderImageEvidenceFailure
import com.model_store.modern.media.image.command.application.OrderImageEvidenceProblem
import com.model_store.modern.media.image.command.domain.ImageCommandDenied
import com.model_store.modern.media.image.command.domain.ImageToInsert
import com.model_store.modern.media.image.command.domain.UploadTarget
import com.model_store.modern.media.image.maintenance.application.*
import com.model_store.modern.media.image.maintenance.infrastructure.S3ImageCleanupObjects
import com.model_store.modern.media.image.maintenance.infrastructure.JdbcImageMaintenance
import com.model_store.modern.media.image.maintenance.infrastructure.JdbcImageProductReferences
import com.model_store.modern.identity.participant.maintenance.infrastructure.JdbcMaintenanceParticipantLocks
import com.model_store.modern.ordering.maintenance.infrastructure.JdbcProductOrderReferences
import com.model_store.modern.media.storage.domain.StorageLocation
import com.model_store.modern.ordering.maintenance.application.*
import com.model_store.modern.ordering.create.application.CreateOrder
import com.model_store.modern.ordering.create.domain.OrderLine
import com.model_store.modern.ordering.create.domain.OrderFailure
import com.model_store.modern.ordering.create.domain.OrderProblem
import com.model_store.modern.shared.domain.Actor
import com.model_store.modern.shared.scheduling.infrastructure.MaintenanceSchedulers
import com.model_store.modern.catalog.product.reservation.application.ProductReservation
import com.model_store.modern.catalog.product.reservation.application.ProductQuantity
import com.sun.net.httpserver.HttpServer
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Assertions.*
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import jakarta.persistence.EntityManagerFactory
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter
import org.springframework.jdbc.datasource.DriverManagerDataSource
import com.amazonaws.services.s3.model.AmazonS3Exception
import com.amazonaws.AmazonClientException
import java.net.SocketTimeoutException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager as Tx
import org.springframework.transaction.support.TransactionTemplate
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

@SpringBootTest(properties = ["spring.config.import=", "app.public-key-path=keys/test_public_key.pem",
    "app.private-key-path=keys/test_private_key.pem"])
@ActiveProfiles("modern")
class MaintenanceIntegrationTest {
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var manager: PlatformTransactionManager
    @Autowired lateinit var products: ProductMaintenance
    @Autowired lateinit var images: ImageMaintenance
    @MockitoSpyBean lateinit var participants: MaintenanceParticipantLocks
    @MockitoSpyBean lateinit var basketStore: BasketStore
    @Autowired lateinit var emf: EntityManagerFactory
    @Autowired lateinit var productLocks: DeletedProductForImageCleanup
    @Autowired lateinit var imageReferences: ImageProductReferences
    @Autowired lateinit var orderReferences: ProductOrderReferences
    @Autowired lateinit var orderImages: ImageOrderMaintenance
    @Autowired lateinit var imageRows: ImageCommandRows
    @Autowired lateinit var attachment: ProductImageAttachment
    @Autowired lateinit var evidence: OrderImageEvidence
    @Autowired lateinit var reservation: ProductReservation
    @Autowired lateinit var createOrder: CreateOrder
    @Autowired lateinit var schedulers: MaintenanceSchedulers
    @MockitoBean lateinit var s3: AmazonS3
    @MockitoBean lateinit var mail: JavaMailSender
    @MockitoBean lateinit var objects: ImageCleanupObjects
    private val tx get() = TransactionTemplate(manager)
    private val workerCapture = ThreadLocal<BlockingQueue<Int>>()

    @BeforeEach fun resetObjects() {
        reset(objects)
        doAnswer { call -> captureBackend(); call.callRealMethod() }.`when`(participants).lockIds(anyList())
        doAnswer { call -> captureBackend(); call.callRealMethod() }.`when`(basketStore).lockParticipant(anyLong())
    }

    private fun captureBackend() {
        workerCapture.get()?.offer(jdbc.queryForObject("SELECT pg_backend_pid()", Int::class.java)!!)
    }

    private fun <T> observed(queue: BlockingQueue<Int>, action: () -> T): T {
        workerCapture.set(queue)
        try { return action() } finally { workerCapture.remove() }
    }

    @Test fun `expiry uses actual inclusive instant excludes GIVEAWAY null dates and non ACTIVE`() {
        val owner = person()
        val due = product(owner)
        val future = product(owner)
        val deleted = product(owner, "DELETED")
        val giveaway = product(owner, availability = "GIVEAWAY")
        val dueOffset = product(owner)
        jdbc.update("UPDATE product SET expiration_date='2001-01-01T03:00:00+03:00' WHERE id IN (?,?,?,?)",
            due, deleted, giveaway, dueOffset)
        jdbc.update("UPDATE product SET expiration_date='2100-01-01T00:00:00Z' WHERE id=?", future)
        assertTrue(products.expireDueOrdinary() >= 2)
        assertEquals("TIME_EXPIRED", productStatus(due))
        assertEquals("TIME_EXPIRED", productStatus(dueOffset))
        assertEquals("ACTIVE", productStatus(future))
        assertEquals("DELETED", productStatus(deleted))
        assertEquals("ACTIVE", productStatus(giveaway))
        assertEquals(0, products.expireDueOrdinary())
        // Exact equality is deterministic in one PG transaction because CURRENT_TIMESTAMP is stable.
        tx.executeWithoutResult {
            jdbc.execute("SET LOCAL TIME ZONE 'Pacific/Auckland'")
            jdbc.update("UPDATE product SET expiration_date=CURRENT_TIMESTAMP WHERE id=?", future)
            assertEquals(true, jdbc.queryForObject("SELECT expiration_date<=CURRENT_TIMESTAMP FROM product WHERE id=?", Boolean::class.java, future))
        }
        products.expireDueOrdinary()
        assertEquals("TIME_EXPIRED", productStatus(future))
    }

    @Test fun `product deletion retains orders all statuses images giveaway history and active rows`() {
        val owner = person()
        val customer = person()
        val plain = product(owner, "DELETED")
        val active = product(owner)
        val withImage = product(owner, "DELETED")
        image("PRODUCT", "DELETE", withImage, owner, true)
        val giveaway = product(owner, "DELETED", "GIVEAWAY")
        val previousGiveaway = product(owner, "DELETED")
        jdbc.update("UPDATE product SET giveaway_start_at='2000-01-01Z',giveaway_end_at='2000-01-02Z' WHERE id=?", previousGiveaway)
        val ordered = listOf("BOOKED", "CANCELLED", "COMPLETED", "FAILED").map { status ->
            product(owner, "DELETED").also { order(it, owner, customer, status) }
        }
        for (table in listOf("product_favorite", "product_basket", "product_cart")) {
            jdbc.update("INSERT INTO $table(participant_id,product_id) VALUES (?,?),(?,?)", customer, plain, customer, ordered.first())
        }
        val orderIds = jdbc.queryForList("SELECT id FROM \"order\" WHERE product_id IN (${ordered.joinToString()})", Long::class.java)
        val reviewOrder = jdbc.queryForObject("SELECT id FROM \"order\" WHERE product_id=?", Long::class.java, ordered[2])!!
        val review = jdbc.queryForObject("""INSERT INTO review(order_id,product_id,reviewer_id,seller_id,rating)
            VALUES (?,?,?,?,5) RETURNING id""", Long::class.java, reviewOrder, ordered[2], customer, owner)!!
        val batch = products.cleanupDeletedBatch(plain - 1, 200)
        assertTrue(batch.deleted >= 1)
        assertEquals(0, count("product", plain))
        (listOf(active, withImage, giveaway, previousGiveaway) + ordered).forEach { assertEquals(1, count("product", it), "$it") }
        orderIds.filterNotNull().forEach { assertEquals(1, count("\"order\"", it)) }
        assertEquals(ordered[2], jdbc.queryForObject("SELECT product_id FROM review WHERE id=?", Long::class.java, review))
        assertEquals(reviewOrder, jdbc.queryForObject("SELECT order_id FROM review WHERE id=?", Long::class.java, review))
        for (table in listOf("product_favorite", "product_basket", "product_cart")) {
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM $table WHERE product_id=?", Int::class.java, plain))
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM $table WHERE product_id=?", Int::class.java, ordered.first()))
        }
        assertTrue(batch.selected < 200)
    }

    @Test fun `keyset advances over protected products beyond a full batch`() {
        val owner = person()
        repeat(201) { product(owner, "DELETED", "GIVEAWAY") }
        val target = product(owner, "DELETED")
        var cursor = 0L
        var pages = 0
        do {
            val batch = products.cleanupDeletedBatch(cursor, 200)
            batch.lastId?.let { assertTrue(it > cursor); cursor = it }
            pages++
            assertTrue(pages < 10)
        } while (batch.selected == 200)
        assertTrue(pages >= 2)
        assertEquals(0, count("product", target))
    }

    @Test fun `temporary cutoff active images deleted product and young DELETE follow effective rules`() {
        val owner = person()
        val activeProduct = product(owner)
        val deletedProduct = product(owner, "DELETED")
        val oldTemp = image("SYSTEM", "TEMPORARY", null, owner, true)
        val youngTemp = image("SYSTEM", "TEMPORARY", null, owner, false)
        val youngDelete = image("PRODUCT", "DELETE", activeProduct, owner, false)
        val active = image("PRODUCT", "ACTIVE", activeProduct, owner, true)
        val obsolete = image("PRODUCT", "ACTIVE", deletedProduct, owner, false)
        val orderActive = image("ORDER", "ACTIVE", null, owner, true)
        val orderTemp = image("ORDER", "TEMPORARY", null, owner, true)
        for (id in listOf(youngTemp, youngDelete, active, orderActive)) assertEquals(ImageCleanupOutcome.SKIPPED, images.cleanupOne(id))
        for (id in listOf(oldTemp, obsolete, orderTemp)) assertEquals(ImageCleanupOutcome.DELETED, images.cleanupOne(id))
        for (id in listOf(oldTemp, obsolete, orderTemp)) assertEquals(0, count("image", id))
        verify(objects, times(3)).deleteVariants(anyString(), anyString())
    }

    @Test fun `tombstone is committed before S3 and storage failure remains retryable`() {
        val owner = person()
        val id = image("SYSTEM", "TEMPORARY", null, owner, true)
        doAnswer {
            // A separate connection sees committed DELETE while the purge transaction is still open.
            jdbc.dataSource!!.connection.use { c ->
                c.prepareStatement("SELECT status::text FROM image WHERE id=?").use { s ->
                    s.setLong(1, id)
                    s.executeQuery().use { rs -> assertTrue(rs.next()); assertEquals("DELETE", rs.getString(1)) }
                }
            }
            throw IllegalStateException("late S3 failure")
        }.`when`(objects).deleteVariants(anyString(), anyString())
        assertThrows<IllegalStateException> { images.cleanupOne(id) }
        assertEquals("DELETE", imageStatus(id))
        reset(objects)
        assertEquals(ImageCleanupOutcome.DELETED, images.cleanupOne(id))
        assertEquals(ImageCleanupOutcome.SKIPPED, images.cleanupOne(id))
    }

    @Test fun `late DB commit failure after S3 retains committed tombstone for replay`() {
        val id = image("SYSTEM", "TEMPORARY", null, person(), true)
        doAnswer {
            Tx.registerSynchronization(object : TransactionSynchronization {
                override fun beforeCommit(readOnly: Boolean) { throw IllegalStateException("commit refused") }
            })
            null
        }.`when`(objects).deleteVariants(anyString(), anyString())
        assertThrows<IllegalStateException> { images.cleanupOne(id) }
        assertEquals("DELETE", imageStatus(id))
        reset(objects)
        assertEquals(ImageCleanupOutcome.DELETED, images.cleanupOne(id))
    }

    @Test fun `real isolated S3 partial variants fail then replay all keys before row deletion`() {
        val id = image("SYSTEM", "TEMPORARY", null, person(), true)
        val filename = jdbc.queryForObject("SELECT filename FROM image WHERE id=?", String::class.java, id)!!
        val remaining = ConcurrentHashMap.newKeySet<String>().apply {
            addAll(listOf("/system/original/$filename", "/system/medium/$filename", "/system/thumbnail/$filename"))
        }
        val failMedium = AtomicBoolean(true)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            assertEquals("DELETE", exchange.requestMethod)
            if (path.contains("/medium/") && failMedium.get()) {
                val xml = "<Error><Code>InternalError</Code><Message>fixture failure</Message></Error>".toByteArray()
                exchange.sendResponseHeaders(500, xml.size.toLong())
                exchange.responseBody.use { it.write(xml) }
            } else { remaining.remove(path); exchange.sendResponseHeaders(204, -1) }
            exchange.close()
        }
        server.start()
        val storage = S3ImageCleanupObjects("fixture", "fixture", "http://127.0.0.1:${server.address.port}",
            "us-east-1", "participant", "product", "order", "system")
        try {
            doAnswer { call -> storage.deleteVariants(call.getArgument(0), call.getArgument(1)); null }
                .`when`(objects).deleteVariants(anyString(), anyString())
            val failure = assertThrows<AmazonS3Exception> { images.cleanupOne(id) }
            assertEquals(500, failure.statusCode)
            assertEquals("InternalError", failure.errorCode)
            assertEquals("DELETE", imageStatus(id))
            assertFalse(remaining.contains("/system/original/$filename"))
            assertEquals(2, remaining.size)
            failMedium.set(false)
            assertEquals(ImageCleanupOutcome.DELETED, images.cleanupOne(id))
            assertTrue(remaining.isEmpty())
        } finally { storage.close(); server.stop(0) }
    }

    @Test fun `scheduled image run continues after item failure and next invocation retries tombstone`() {
        val owner = person()
        val bad = image("SYSTEM", "TEMPORARY", null, owner, true)
        val good = image("SYSTEM", "TEMPORARY", null, owner, true)
        val filename = jdbc.queryForObject("SELECT filename FROM image WHERE id=?", String::class.java, bad)!!
        doAnswer { call ->
            if (call.getArgument<String>(1) == filename) throw IllegalStateException("fixture item failure")
            null
        }.`when`(objects).deleteVariants(anyString(), anyString())
        schedulers.cleanupImages()
        assertEquals("DELETE", imageStatus(bad)); assertEquals(0, count("image", good))
        reset(objects)
        schedulers.cleanupImages()
        assertEquals(0, count("image", bad))
    }

    @Test fun `referenced order proof is protected even with corrupt TEMPORARY status`() {
        val seller = person()
        val customer = person()
        val order = order(product(seller), seller, customer)
        val id = image("ORDER", "TEMPORARY", order, customer, true)
        jdbc.update("UPDATE \"order\" SET image_payment_proof_id=? WHERE id=?", id, order)
        assertEquals(ImageCleanupOutcome.SKIPPED, images.cleanupOne(id))
        assertEquals("TEMPORARY", imageStatus(id))
        verifyNoInteractions(objects)
    }

    @Test fun `ports reject absent read only and JDBC only transactions`() {
        val owner = person()
        val p = product(owner)
        val calls = listOf<Pair<Class<out RuntimeException>, () -> Any?>>(
            MaintenanceParticipantTransactionRequired::class.java to { participants.lockIds(listOf(owner)) },
            CleanupProductTransactionRequired::class.java to { productLocks.lock(p) },
            OrderMaintenanceTransactionRequired::class.java to { orderReferences.hasOrders(p) },
            ImageReferencesTransactionRequired::class.java to { imageReferences.hasImages(p) },
            OrderMaintenanceTransactionRequired::class.java to { orderImages.lockImageOrder(Long.MAX_VALUE) },
            OrderMaintenanceTransactionRequired::class.java to { orderImages.isImageReferenced(Long.MAX_VALUE) },
        )
        fun denied(type: Class<out RuntimeException>, call: () -> Any?) {
            val failure = org.junit.jupiter.api.Assertions.assertThrows(type) { call() }
            assertEquals(type, failure.javaClass)
        }
        for ((type, call) in calls) {
            denied(type, call)
            TransactionTemplate(manager).apply { isReadOnly = true }.executeWithoutResult {
                denied(type, call)
            }
            TransactionTemplate(DataSourceTransactionManager(jdbc.dataSource!!)).executeWithoutResult {
                denied(type, call)
            }
        }
        tx.executeWithoutResult {
            assertTrue(participants.lockIds(listOf(owner, owner)))
            assertFalse(participants.lockIds(listOf(Long.MAX_VALUE)))
            assertNotNull(productLocks.lock(p))
            assertFalse(orderReferences.hasOrders(p))
            assertFalse(imageReferences.hasImages(p))
        }
    }

    @Test fun `background commands reject ambient transactions before any S3 or mutation`() {
        val id = image("SYSTEM", "TEMPORARY", null, person(), true)
        tx.executeWithoutResult {
            assertThrows<ImageMaintenanceTransactionRequired> { images.cleanupOne(id) }
            assertThrows<ProductMaintenanceTransactionRequired> { products.expireDueOrdinary() }
            assertThrows<ProductMaintenanceTransactionRequired> { products.cleanupDeletedBatch(0, 200) }
            jdbc.update("UPDATE image SET filename=filename WHERE id=?", id)
            it.setRollbackOnly()
        }
        assertEquals("TEMPORARY", imageStatus(id))
        verifyNoInteractions(objects)
    }

    @Test fun `concurrent cleaners serialize S3 and repeated execution is harmless`() {
        val owner = person()
        val id = image("SYSTEM", "DELETE", null, owner, true)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val firstPid = ArrayBlockingQueue<Int>(1)
        doAnswer { firstPid.offer(jdbc.queryForObject("SELECT pg_backend_pid()", Int::class.java)!!); entered.countDown(); assertTrue(release.await(8, TimeUnit.SECONDS)); null }
            .`when`(objects).deleteVariants(anyString(), anyString())
        val pool = Executors.newFixedThreadPool(2)
        try {
            val first = pool.submit<ImageCleanupOutcome> { images.cleanupOne(id) }
            assertTrue(entered.await(8, TimeUnit.SECONDS))
            val secondPid = LinkedBlockingQueue<Int>()
            val second = pool.submit<ImageCleanupOutcome> { observed(secondPid) { images.cleanupOne(id) } }
            awaitBlocked(secondPid.poll(8, TimeUnit.SECONDS) ?: error("Second cleanup did not reach locks"),
                firstPid.poll(1, TimeUnit.SECONDS)!!, "FROM participant")
            release.countDown()
            assertEquals(ImageCleanupOutcome.DELETED, first.get(10, TimeUnit.SECONDS))
            assertTrue(second.get(10, TimeUnit.SECONDS) in setOf(ImageCleanupOutcome.CONFLICT, ImageCleanupOutcome.SKIPPED))
            verify(objects, times(1)).deleteVariants(anyString(), anyString())
        } finally { release.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS)) }
    }

    @Test fun `changed product owner while waiting skips without adding new participant locks`() {
        val replacement = person()
        val owner = person()
        val p = product(owner, "DELETED")
        val id = image("PRODUCT", "ACTIVE", p, owner, true)
        whileParticipantHeld(owner, { images.cleanupOne(id) }) {
            jdbc.update("UPDATE product SET participant_id=? WHERE id=?", replacement, p)
        }.also { assertEquals(ImageCleanupOutcome.CONFLICT, it) }
        assertEquals("ACTIVE", imageStatus(id))
        verifyNoInteractions(objects)
    }

    @Test fun `changed image association uploader tag or activation while waiting is not followed`() {
        for (assignment in listOf("entity_id=NULL", "uploaded_by=NULL", "tag='SYSTEM'", "status='ACTIVE'")) {
            val owner = person()
            val p = product(owner)
            val id = image("PRODUCT", "TEMPORARY", p, owner, true)
            whileParticipantHeld(owner, { images.cleanupOne(id) }) {
                jdbc.update("UPDATE image SET $assignment WHERE id=?", id)
            }.also { assertEquals(ImageCleanupOutcome.CONFLICT, it, assignment) }
            assertEquals(1, count("image", id))
        }
        verifyNoInteractions(objects)
    }

    @Test fun `ORDER party replacement while cleanup waits skips without following new customer`() {
        val replacement = person()
        val customer = person()
        val seller = person()
        val order = order(product(seller), seller, customer)
        val id = image("ORDER", "TEMPORARY", order, customer, true)
        whileParticipantHeld(customer, { images.cleanupOne(id) }) {
            jdbc.update("UPDATE \"order\" SET customer_id=? WHERE id=?", replacement, order)
        }.also { assertEquals(ImageCleanupOutcome.CONFLICT, it) }
        assertEquals("TEMPORARY", imageStatus(id))
    }

    @Test fun `accepted image claim before cleanup lock wins and active image stays intact`() {
        val owner = person()
        val p = product(owner)
        val id = image("PRODUCT", "TEMPORARY", null, owner, true)
        whileParticipantHeld(owner, { images.cleanupOne(id) }) {
            tx.executeWithoutResult {
                // Own thread already holds participant; claim uses same JPA/product/image order as commands16.
                jdbc.queryForList("SELECT id FROM product WHERE id=? FOR UPDATE", p)
                attachment.claim(p, owner, listOf(id))
            }
        }.also { assertEquals(ImageCleanupOutcome.CONFLICT, it) }
        assertEquals("ACTIVE", imageStatus(id))
        verifyNoInteractions(objects)
    }

    @Test fun `both uploader owner ID directions take lower participant first`() {
        for (ownerFirst in listOf(true, false)) {
            val low = person()
            val high = person()
            val owner = if (ownerFirst) low else high
            val uploader = if (ownerFirst) high else low
            val id = image("PRODUCT", "ACTIVE", product(owner, "DELETED"), uploader, true)
            whileParticipantHeld(low, { images.cleanupOne(id) }) {
                // If cleanup had taken high first, this would wait/cycle instead of succeeding.
                jdbc.execute("SET LOCAL lock_timeout='500ms'")
                assertTrue(participants.lockIds(listOf(high, low, high)))
            }.also { assertEquals(ImageCleanupOutcome.DELETED, it) }
        }
    }

    @Test fun `product status recheck protects favorites and basket when deletion loses`() {
        val owner = person()
        val p = product(owner, "DELETED")
        jdbc.update("INSERT INTO product_basket(participant_id,product_id) VALUES (?,?)", owner, p)
        whileParticipantHeld(owner, { products.cleanupDeletedBatch(p - 1, 1) }) {
            jdbc.update("UPDATE product SET status='ACTIVE' WHERE id=?", p)
        }.also { assertEquals(0, it.deleted); assertEquals(0, it.failed) }
        assertEquals("ACTIVE", productStatus(p))
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM product_basket WHERE product_id=?", Int::class.java, p))
    }

    @Test fun `per product DB failure rolls back dependents and advances to next candidate`() {
        val owner = person()
        val bad = product(owner, "DELETED")
        val good = product(owner, "DELETED")
        jdbc.update("INSERT INTO product_favorite(participant_id,product_id) VALUES (?,?)", owner, bad)
        jdbc.execute("""CREATE FUNCTION maintenance30_fail_delete() RETURNS trigger LANGUAGE plpgsql AS
            'BEGIN IF OLD.id=$bad THEN RAISE EXCEPTION USING ERRCODE=''23503'', MESSAGE=''fixture FK failure''; END IF; RETURN OLD; END'""")
        jdbc.execute("CREATE TRIGGER maintenance30_fail_delete BEFORE DELETE ON product FOR EACH ROW EXECUTE FUNCTION maintenance30_fail_delete()")
        try {
            val result = products.cleanupDeletedBatch(bad - 1, 2)
            assertEquals(2, result.selected); assertEquals(1, result.failed); assertEquals(1, result.deleted)
            assertEquals(good, result.lastId)
            assertEquals(1, count("product", bad)); assertEquals(0, count("product", good))
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM product_favorite WHERE product_id=?", Int::class.java, bad))
        } finally {
            jdbc.execute("DROP TRIGGER maintenance30_fail_delete ON product")
            jdbc.execute("DROP FUNCTION maintenance30_fail_delete()")
        }
    }

    @Test fun `accepted modern booking wins before cleanup and actual order prevents physical delete`() {
        val seller = person()
        val customer = person()
        val p = product(seller, "DELETED")
        val line = bookingLine(p, seller, customer)
        var orderId = 0L
        whileParticipantHeld(seller, { products.cleanupDeletedBatch(p - 1, 1) }) {
            jdbc.update("UPDATE product SET status='ACTIVE',count=2 WHERE id=?", p)
            orderId = createOrder.book(Actor(customer, "fixture", "USER"), listOf(line)).single()
            jdbc.update("UPDATE product SET status='DELETED' WHERE id=?", p)
        }.also { assertEquals(0, it.deleted); assertEquals(0, it.failed) }
        assertEquals(1, count("product", p)); assertEquals(1, count("\"order\"", orderId))
        assertEquals(p, jdbc.queryForObject("SELECT product_id FROM \"order\" WHERE id=?", Long::class.java, orderId))
        assertEquals(1, jdbc.queryForObject("SELECT count FROM product WHERE id=?", Int::class.java, p))
    }

    @Test fun `physical deletion wins and waiting accepted modern booking creates no orphan`() {
        val seller = person()
        val customer = person()
        val p = product(seller, "DELETED")
        val line = bookingLine(p, seller, customer)
        val pool = Executors.newFixedThreadPool(2)
        // The fixture trigger pauses the actual deletion after all production eligibility rechecks.
        jdbc.execute("""CREATE FUNCTION maintenance30_pause_delete() RETURNS trigger LANGUAGE plpgsql AS
            'BEGIN IF OLD.id=$p THEN PERFORM pg_advisory_xact_lock(830030); END IF; RETURN OLD; END'""")
        jdbc.execute("CREATE TRIGGER maintenance30_pause_delete BEFORE DELETE ON product FOR EACH ROW EXECUTE FUNCTION maintenance30_pause_delete()")
        try {
            jdbc.dataSource!!.connection.use { holder ->
                holder.createStatement().use { it.execute("SELECT pg_advisory_lock(830030)") }
                val holderPid = holder.createStatement().use { s -> s.executeQuery("SELECT pg_backend_pid()").use { rs -> rs.next(); rs.getInt(1) } }
                val cleaningPid = LinkedBlockingQueue<Int>()
                val cleaning = pool.submit<ProductCleanupBatch> { observed(cleaningPid) { products.cleanupDeletedBatch(p - 1, 1) } }
                val cleanerPid = cleaningPid.poll(8, TimeUnit.SECONDS) ?: error("Cleanup did not start locks")
                awaitBlocked(cleanerPid, holderPid, "DELETE FROM product")
                val bookingPid = LinkedBlockingQueue<Int>()
                val booking = pool.submit<OrderProblem?> {
                    try { observed(bookingPid) { createOrder.book(Actor(customer, "fixture", "USER"), listOf(line)) }; null }
                    catch (error: OrderFailure) { error.problem }
                }
                awaitBlocked(bookingPid.poll(8, TimeUnit.SECONDS) ?: error("Booking did not reach locks"), cleanerPid, "FROM product")
                holder.createStatement().use { it.execute("SELECT pg_advisory_unlock(830030)") }
                assertEquals(1, cleaning.get(12, TimeUnit.SECONDS).deleted)
                assertEquals(OrderProblem.PRODUCT_NOT_FOUND, booking.get(12, TimeUnit.SECONDS))
            }
            assertEquals(0, count("product", p))
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM \"order\" WHERE product_id=?", Int::class.java, p))
        } finally {
            pool.shutdownNow(); assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS))
            jdbc.execute("DROP TRIGGER maintenance30_pause_delete ON product")
            jdbc.execute("DROP FUNCTION maintenance30_pause_delete()")
        }
    }

    private fun bookingLine(product: Long, seller: Long, customer: Long): OrderLine {
        val address = jdbc.queryForObject("INSERT INTO address(country,city) VALUES ('RU','Fixture') RETURNING id", Long::class.java)!!
        jdbc.update("INSERT INTO participant_address(participant_id,address_id) VALUES (?,?)", customer, address)
        val transfer = jdbc.queryForObject("INSERT INTO transfer(sending,price,currency,participant_id) VALUES ('PRODUCT_PICKUP',0,'RUB',?) RETURNING id", Long::class.java, seller)!!
        return OrderLine(product, 1, address, transfer, null)
    }

    @Test fun `invalid and shared storage keys are skipped before tombstone or S3`() {
        val owner = person()
        for (filename in listOf(null, "", "../active.jpg", "a/b.jpg", "a\\b.jpg", "bad\u0001.jpg")) {
            val id = image("SYSTEM", "TEMPORARY", null, owner, true)
            jdbc.update("UPDATE image SET filename=? WHERE id=?", filename, id)
            assertEquals(ImageCleanupOutcome.SKIPPED, images.cleanupOne(id))
            assertEquals("TEMPORARY", imageStatus(id))
        }
        val expired = image("PRODUCT", "TEMPORARY", null, owner, true)
        val active = image("PRODUCT", "ACTIVE", product(owner), owner, true)
        jdbc.update("UPDATE image SET filename=(SELECT filename FROM image WHERE id=?) WHERE id=?", active, expired)
        assertEquals(ImageCleanupOutcome.SKIPPED, images.cleanupOne(expired))
        assertEquals("ACTIVE", imageStatus(active)); assertEquals("TEMPORARY", imageStatus(expired))
        verifyNoInteractions(objects)
    }

    @Test fun `scheduler high water mark defers IDs inserted during a full page run`() {
        val owner = person()
        repeat(201) { image("SYSTEM", "TEMPORARY", null, owner, true) }
        var inserted = 0L
        val once = AtomicBoolean()
        doAnswer {
            if (once.compareAndSet(false, true)) inserted = image("SYSTEM", "TEMPORARY", null, owner, true)
            null
        }.`when`(objects).deleteVariants(anyString(), anyString())
        schedulers.cleanupImages()
        assertTrue(inserted > 0)
        assertEquals("TEMPORARY", imageStatus(inserted))
        reset(objects)
        schedulers.cleanupImages()
        assertEquals(0, count("image", inserted))
        val first = product(owner, "DELETED")
        val ceiling = products.lastCandidateId()
        val later = product(owner, "DELETED")
        assertEquals(1, products.cleanupDeletedBatch(first - 1, 200, ceiling).deleted)
        assertEquals(1, count("product", later))
        assertEquals(1, products.cleanupDeletedBatch(ceiling, 200, products.lastCandidateId()).deleted)
    }

    @Test fun `mutation adapters reject JDBC manager wrong EMF and mismatched JDBC source before writes`() {
        val source = jdbc.dataSource!!
        val foreignJdbc = JdbcTemplate(DriverManagerDataSource("jdbc:postgresql://127.0.0.1:${postgres.port}/postgres", "postgres", ""))
        val badManagers = listOf<PlatformTransactionManager>(DataSourceTransactionManager(source),
            JpaTransactionManager(mock(EntityManagerFactory::class.java)))
        val id = image("SYSTEM", "TEMPORARY", null, person(), true)
        for (bad in badManagers) {
            val catalog = JdbcProductMaintenance(jdbc, bad, emf, source, participants, imageReferences, orderReferences)
            val media = JdbcImageMaintenance(jdbc, bad, emf, source, participants, productLocks, orderImages, objects)
            assertThrows<ProductMaintenanceTransactionRequired> { catalog.expireDueOrdinary() }
            assertThrows<ProductMaintenanceTransactionRequired> { catalog.cleanupDeletedBatch(0, 1, Long.MAX_VALUE) }
            assertThrows<ImageMaintenanceTransactionRequired> { media.cleanupOne(id) }
        }
        val catalog = JdbcProductMaintenance(foreignJdbc, manager, emf, source, participants, imageReferences, orderReferences)
        val media = JdbcImageMaintenance(foreignJdbc, manager, emf, source, participants, productLocks, orderImages, objects)
        assertThrows<ProductMaintenanceTransactionRequired> { catalog.expireDueOrdinary() }
        assertThrows<ImageMaintenanceTransactionRequired> { media.cleanupOne(id) }
        tx.executeWithoutResult {
            assertThrows<MaintenanceParticipantTransactionRequired> { JdbcMaintenanceParticipantLocks(foreignJdbc, emf, source).lockIds(emptyList()) }
            assertThrows<CleanupProductTransactionRequired> { JdbcDeletedProductForImageCleanup(foreignJdbc, emf, source).lock(1) }
            assertThrows<ImageReferencesTransactionRequired> { JdbcImageProductReferences(foreignJdbc, emf, source).hasImages(1) }
            assertThrows<OrderMaintenanceTransactionRequired> { JdbcProductOrderReferences(foreignJdbc, emf, source).hasOrders(1) }
        }
        assertEquals("TEMPORARY", imageStatus(id)); verifyNoInteractions(objects)
    }

    @Test fun `real foreign JPA factory cannot substitute expected caller persistence boundary`() {
        val factory = LocalContainerEntityManagerFactoryBean().apply {
            dataSource = jdbc.dataSource
            jpaVendorAdapter = HibernateJpaVendorAdapter()
            setPackagesToScan("com.model_store.modern.shared.scheduling.audit.empty")
            setJpaPropertyMap(mapOf("hibernate.hbm2ddl.auto" to "none"))
            afterPropertiesSet()
        }
        try {
            TransactionTemplate(JpaTransactionManager(factory.`object`!!)).executeWithoutResult {
                assertThrows<MaintenanceParticipantTransactionRequired> { participants.lockIds(emptyList()) }
                assertThrows<CleanupProductTransactionRequired> { productLocks.lock(1) }
                assertThrows<ImageReferencesTransactionRequired> { imageReferences.hasImages(1) }
                assertThrows<OrderMaintenanceTransactionRequired> { orderReferences.hasOrders(1) }
                assertThrows<OrderMaintenanceTransactionRequired> { orderImages.lockImageOrder(1) }
            }
        } finally { factory.destroy() }
    }

    @Test fun `accepted ORDER evidence activation wins before cleanup and is retained`() {
        val customer = person()
        val seller = person()
        val order = order(product(seller), seller, customer)
        val id = image("ORDER", "TEMPORARY", order, customer, true)
        whileParticipantHeld(customer, { images.cleanupOne(id) }) {
            assertTrue(participants.lockIds(listOf(customer, seller)))
            assertNotNull(orderImages.lockImageOrder(order))
            assertEquals(1, evidence.activate(order, customer, listOf(id)))
            jdbc.update("UPDATE \"order\" SET image_payment_proof_id=? WHERE id=?", id, order)
        }.also { assertEquals(ImageCleanupOutcome.CONFLICT, it) }
        assertEquals("ACTIVE", imageStatus(id)); verifyNoInteractions(objects)
    }

    @Test fun `cleanup wins before waiting ORDER activation and PRODUCT upload without resurrection`() {
        for (tag in listOf("ORDER", "PRODUCT")) {
            val uploader = person()
            val owner = person()
            val entity = if (tag == "ORDER") order(product(owner), owner, uploader) else product(uploader, "DELETED")
            val id = image(tag, if (tag == "ORDER") "TEMPORARY" else "ACTIVE", entity, uploader, true)
            val entered = ArrayBlockingQueue<Int>(1)
            val release = CountDownLatch(1)
            doAnswer {
                entered.offer(jdbc.queryForObject("SELECT pg_backend_pid()", Int::class.java)!!)
                assertTrue(release.await(8, TimeUnit.SECONDS)); null
            }.`when`(objects).deleteVariants(anyString(), anyString())
            val pool = Executors.newFixedThreadPool(2)
            try {
                val cleaner = pool.submit<ImageCleanupOutcome> { images.cleanupOne(id) }
                val cleanerPid = entered.poll(8, TimeUnit.SECONDS) ?: error("Cleanup did not reach S3")
                val workerPid = LinkedBlockingQueue<Int>()
                val writer = pool.submit<Any?> {
                    observed(workerPid) {
                        tx.execute {
                            captureBackend()
                            if (tag == "ORDER") {
                                participants.lockIds(listOf(uploader, owner))
                                orderImages.lockImageOrder(entity)
                                evidence.activate(entity, uploader, listOf(id))
                            } else {
                                imageRows.insertBatch(UploadTarget(StorageLocation.PRODUCT, entity, uploader),
                                    listOf(ImageToInsert("${UUID.randomUUID()}.jpg", 1, 1)))
                            }
                        }
                    }
                }
                awaitBlocked(workerPid.poll(8, TimeUnit.SECONDS) ?: error("Writer did not enter JPA"), cleanerPid, "FROM participant")
                release.countDown()
                assertEquals(ImageCleanupOutcome.DELETED, cleaner.get(12, TimeUnit.SECONDS))
                val failed = assertThrows<ExecutionException> { writer.get(12, TimeUnit.SECONDS) }.cause
                if (tag == "ORDER") {
                    assertEquals(OrderImageEvidenceFailure::class.java, failed?.javaClass)
                    assertEquals(OrderImageEvidenceProblem.IMAGE_NOT_FOUND, (failed as OrderImageEvidenceFailure).problem)
                } else assertEquals(ImageCommandDenied::class.java, failed?.javaClass)
                assertEquals(0, count("image", id))
                assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM image WHERE tag::text=? AND entity_id=?",
                    Int::class.java, tag, entity))
            } finally { release.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS)); reset(objects) }
        }
    }

    @Test fun `bounded S3 socket timeout retains committed tombstone and does not remove row`() {
        val id = image("SYSTEM", "TEMPORARY", null, person(), true)
        val release = CountDownLatch(1)
        val requests = LinkedBlockingQueue<String>()
        val pool = Executors.newFixedThreadPool(2)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = pool
            createContext("/") { exchange ->
                requests.offer(exchange.requestURI.path)
                try { release.await(20, TimeUnit.SECONDS) } finally { exchange.close() }
            }
            start()
        }
        val storage = S3ImageCleanupObjects("fixture", "fixture", "http://127.0.0.1:${server.address.port}",
            "us-east-1", "participant", "product", "order", "system")
        try {
            doAnswer { call -> storage.deleteVariants(call.getArgument(0), call.getArgument(1)); null }
                .`when`(objects).deleteVariants(anyString(), anyString())
            val start = System.nanoTime()
            val failed = assertThrows<AmazonClientException> { images.cleanupOne(id) }
            val causes = generateSequence<Throwable>(failed) { it.cause }.toList()
            assertTrue(causes.any { it is SocketTimeoutException }, failed.toString())
            assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start) < 20)
            assertTrue(requests.isNotEmpty())
            assertTrue(requests.all { it.contains("/original/") })
            assertEquals("DELETE", imageStatus(id))
        } finally { release.countDown(); storage.close(); server.stop(0); pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)) }
    }

    @Test fun `busy product is skipped while lock stays held and later candidate is deleted`() {
        val owner = person()
        val busy = product(owner, "DELETED")
        val next = product(owner, "DELETED")
        jdbc.update("INSERT INTO product_favorite(participant_id,product_id) VALUES (?,?)", owner, busy)
        jdbc.dataSource!!.connection.use { holder ->
            holder.autoCommit = false
            try {
                holder.prepareStatement("SELECT id FROM product WHERE id=? FOR UPDATE").use { s ->
                    s.setLong(1, busy); s.executeQuery().use { assertTrue(it.next()) }
                }
                jdbc.dataSource!!.connection.use { probe ->
                    val failure = assertThrows<java.sql.SQLException> {
                        probe.prepareStatement("SELECT id FROM product WHERE id=? FOR UPDATE NOWAIT").use { s ->
                            s.setLong(1, busy); s.executeQuery().close()
                        }
                    }
                    assertEquals("55P03", failure.sqlState)
                }
                val pool = Executors.newSingleThreadExecutor()
                try {
                    val batch = pool.submit<ProductCleanupBatch> { products.cleanupDeletedBatch(busy - 1, 2) }
                        .get(5, TimeUnit.SECONDS)
                    assertEquals(2, batch.selected); assertEquals(1, batch.deleted); assertEquals(0, batch.failed)
                    assertEquals(next, batch.lastId)
                    assertEquals(1, count("product", busy)); assertEquals(0, count("product", next))
                    assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM product_favorite WHERE product_id=?", Int::class.java, busy))
                } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)) }
            } finally { holder.rollback() }
        }
    }

    private fun awaitBlocked(waiter: Int, blocker: Int, sql: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        var lastQuery: String? = null
        while (System.nanoTime() < deadline) {
            jdbc.execute("SELECT pg_stat_clear_snapshot()")
            val query = jdbc.queryForList("SELECT query FROM pg_stat_activity WHERE pid=? AND ?=ANY(pg_blocking_pids(pid))",
                String::class.java, waiter, blocker).firstOrNull()
            if (query != null) lastQuery = query
            // pg_blocking_pids is live, pg_stat_activity may briefly show the preceding statement.
            // Require both the exact blocker and expected SQL, rather than fail on that transient view.
            if (query?.contains(sql) == true) {
                println("maintenance30 exact blocker=$blocker waiter=$waiter SQL=$query")
                return
            }
            Thread.yield()
        }
        error("Expected backend $waiter did not block behind $blocker on $sql; last SQL=$lastQuery")
    }

    private fun <T> whileParticipantHeld(owner: Long, action: () -> T, change: () -> Unit): T {
        val pool = Executors.newSingleThreadExecutor()
        try {
            var future: Future<T>? = null
            tx.executeWithoutResult {
                jdbc.execute("SET LOCAL statement_timeout='12s'")
                jdbc.queryForList("SELECT id FROM participant WHERE id=? FOR UPDATE", owner)
                val blocker = jdbc.queryForObject("SELECT pg_backend_pid()", Int::class.java)!!
                val captured = LinkedBlockingQueue<Int>()
                future = pool.submit(Callable { observed(captured, action) })
                val waiter = captured.poll(8, TimeUnit.SECONDS) ?: error("Worker did not reach its JPA locks")
                awaitBlocked(waiter, blocker, "FROM participant")
                change()
            }
            return future!!.get(12, TimeUnit.SECONDS)
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS)) }
    }

    private fun person(): Long = jdbc.queryForObject("INSERT INTO participant(login,password,status) VALUES (?,'fixture','ACTIVE') RETURNING id",
        Long::class.java, UUID.randomUUID().toString())!!
    private fun product(owner: Long, status: String = "ACTIVE", availability: String = "PURCHASABLE"): Long = jdbc.queryForObject(
        "INSERT INTO product(name,price,currency,participant_id,status,availability) VALUES (?,1,'RUB',?,?::product_status,?::product_availability) RETURNING id",
        Long::class.java, "maintenance-${UUID.randomUUID()}", owner, status, availability)!!
    private fun image(tag: String, status: String, entity: Long?, uploader: Long?, old: Boolean): Long = jdbc.queryForObject(
        """INSERT INTO image(filename,tag,status,entity_id,uploaded_by,created_at)
            VALUES (?,?::image_tag,?::image_status,?,?,CURRENT_TIMESTAMP - ?::interval) RETURNING id""",
        Long::class.java, "${UUID.randomUUID()}.jpg", tag, status, entity, uploader, if (old) "25 hours" else "1 hour")!!
    private fun order(product: Long, seller: Long, customer: Long, status: String = "BOOKED"): Long {
        val address = jdbc.queryForObject("INSERT INTO address(country,city) VALUES ('RU','Fixture') RETURNING id", Long::class.java)!!
        val transfer = jdbc.queryForObject("INSERT INTO transfer(sending,price,currency,participant_id) VALUES ('PRODUCT_PICKUP',0,'RUB',?) RETURNING id", Long::class.java, seller)!!
        return jdbc.queryForObject(
            """INSERT INTO "order"(seller_id,customer_id,count,status,product_id,total_price,product_unit_price,
                product_currency,product_availability,address_id,transfer_id)
                VALUES (?,?,1,?::order_status,?,1,1,'RUB','PURCHASABLE',?,?) RETURNING id""",
            Long::class.java, seller, customer, status, product, address, transfer)!!
    }
    private fun productStatus(id: Long) = jdbc.queryForObject("SELECT status::text FROM product WHERE id=?", String::class.java, id)
    private fun imageStatus(id: Long) = jdbc.queryForObject("SELECT status::text FROM image WHERE id=?", String::class.java, id)
    private fun count(table: String, id: Long) = jdbc.queryForObject("SELECT count(*) FROM $table WHERE id=?", Int::class.java, id)

    companion object {
        private val postgres = EmbeddedPostgres.start()
        @JvmStatic @DynamicPropertySource fun database(registry: DynamicPropertyRegistry) {
            val url = "jdbc:postgresql://127.0.0.1:${postgres.port}/postgres"
            registry.add("spring.datasource.url") { url }; registry.add("spring.datasource.username") { "postgres" }
            registry.add("spring.datasource.password") { "" }; registry.add("spring.flyway.url") { url }
            registry.add("spring.flyway.user") { "postgres" }; registry.add("spring.flyway.password") { "" }
        }
        @JvmStatic @AfterAll fun close() = postgres.close()
    }
}
