package com.model_store.modern.media.image.command

import com.amazonaws.services.s3.AmazonS3
import com.model_store.modern.media.image.command.application.ImageCommandRows
import com.model_store.modern.media.image.command.application.OrderImageEvidence
import com.model_store.modern.media.image.command.application.OrderImageEvidenceFailure
import com.model_store.modern.media.image.command.application.OrderImageEvidenceProblem
import com.model_store.modern.media.image.command.domain.ImageToInsert
import com.model_store.modern.media.image.command.domain.ImageCommandDenied
import com.model_store.modern.media.image.command.domain.UploadTarget
import com.model_store.modern.media.image.read.application.ImageReadPort
import com.model_store.modern.media.storage.domain.StorageLocation
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import jakarta.persistence.EntityManagerFactory
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.orm.jpa.EntityManagerFactoryUtils
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.UnexpectedRollbackException
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

@SpringBootTest(properties = [
    "app.public-key-path=keys/test_public_key.pem",
    "app.private-key-path=keys/test_private_key.pem",
])
@ActiveProfiles("modern")
class OrderImageEvidenceIntegrationTest {
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var port: OrderImageEvidence
    @Autowired lateinit var rows: ImageCommandRows
    @Autowired lateinit var read: ImageReadPort
    @Autowired lateinit var emf: EntityManagerFactory
    @Autowired @Qualifier("transactionManager") lateinit var manager: PlatformTransactionManager
    @MockitoBean lateinit var s3: AmazonS3
    @MockitoBean lateinit var mail: JavaMailSender

    @Test
    fun `ORDER upload activation and read share the caller JPA connection without storage calls`() {
        val (buyer, order) = order()
        val ids = rows.insertBatch(UploadTarget(StorageLocation.ORDER, order, buyer),
            listOf(ImageToInsert("evidence.jpg", 10, 10), ImageToInsert("proof.jpg", 10, 10)))
        assertTrue(read.findActive(ids).isEmpty())
        clearInvocations(s3, mail)
        TransactionTemplate(manager).executeWithoutResult {
            // Caller-owned locks and authorization precede media; media never reads these tables.
            jdbc.queryForObject("SELECT id FROM participant WHERE id=? FOR UPDATE", Long::class.java, buyer)
            jdbc.queryForObject("SELECT id FROM \"order\" WHERE id=? FOR UPDATE", Long::class.java, order)
            val em = EntityManagerFactoryUtils.getTransactionalEntityManager(emf)!!
            assertEquals((em.createNativeQuery("SELECT txid_current()").singleResult as Number).toLong(),
                jdbc.queryForObject("SELECT txid_current()", Long::class.java))
            assertEquals(2, port.activate(order, buyer, ids.reversed()))
            em.createNativeQuery("UPDATE \"order\" SET image_payment_proof_id=:image WHERE id=:id")
                .setParameter("image", ids.first()).setParameter("id", order).executeUpdate()
        }
        assertEquals(2, read.findActive(ids).size)
        assertTrue(read.findActive(ids).all { it.entityId == order && it.location == StorageLocation.ORDER })
        assertEquals(ids.first(), jdbc.queryForObject(
            "SELECT image_payment_proof_id FROM \"order\" WHERE id=?", Long::class.java, order))
        verifyNoInteractions(s3, mail)
        assertEquals(29, jdbc.queryForObject(
            "SELECT count(*) FROM flyway_schema_history WHERE success AND version IS NOT NULL", Int::class.java))
    }

    @Test
    fun `a late failure rolls back actual order proof and image activation together`() {
        val (buyer, order) = order()
        val image = rows.insertBatch(UploadTarget(StorageLocation.ORDER, null, buyer),
            listOf(ImageToInsert("rollback.jpg", 10, 10))).single()
        clearInvocations(s3, mail)
        assertThrows(IllegalStateException::class.java) {
            TransactionTemplate(manager).executeWithoutResult {
                jdbc.queryForObject("SELECT id FROM participant WHERE id=? FOR UPDATE", Long::class.java, buyer)
                jdbc.queryForObject("SELECT id FROM \"order\" WHERE id=? FOR UPDATE", Long::class.java, order)
                val em = EntityManagerFactoryUtils.getTransactionalEntityManager(emf)!!
                em.createNativeQuery("UPDATE \"order\" SET image_payment_proof_id=:image WHERE id=:id")
                    .setParameter("image", image).setParameter("id", order).executeUpdate()
                port.activate(order, buyer, listOf(image))
                throw IllegalStateException("later ordering failure")
            }
        }
        assertNull(jdbc.queryForObject("SELECT image_payment_proof_id FROM \"order\" WHERE id=?",
            Long::class.java, order))
        assertEquals("TEMPORARY", jdbc.queryForObject("SELECT status::text FROM image WHERE id=?",
            String::class.java, image))
        assertNull(jdbc.queryForObject("SELECT entity_id FROM image WHERE id=?", Long::class.java, image))
        verifyNoInteractions(s3, mail)
    }

    @Test
    fun `caught media failure still rolls back earlier activation and actual order writes`() {
        val (buyer, order) = order()
        val images = rows.insertBatch(UploadTarget(StorageLocation.ORDER, null, buyer),
            listOf(ImageToInsert("first.jpg", 10, 10), ImageToInsert("second.jpg", 10, 10)))
        clearInvocations(s3, mail)
        assertThrows(UnexpectedRollbackException::class.java) {
            TransactionTemplate(manager).executeWithoutResult {
                jdbc.queryForObject("SELECT id FROM participant WHERE id=? FOR UPDATE", Long::class.java, buyer)
                jdbc.queryForObject("SELECT id FROM \"order\" WHERE id=? FOR UPDATE", Long::class.java, order)
                port.activate(order, buyer, listOf(images.first()))
                jdbc.update("UPDATE \"order\" SET image_payment_proof_id=? WHERE id=?", images.first(), order)
                val failure = assertThrows(OrderImageEvidenceFailure::class.java) {
                    port.activate(order, buyer, listOf(images.last(), Long.MAX_VALUE))
                }
                assertEquals(OrderImageEvidenceProblem.IMAGE_NOT_FOUND, failure.problem)
                // Deliberately swallow the exception: the port must have marked this transaction.
            }
        }
        assertNull(jdbc.queryForObject("SELECT image_payment_proof_id FROM \"order\" WHERE id=?",
            Long::class.java, order))
        assertTrue(read.findActive(images).isEmpty())
        for (image in images) {
            assertEquals("TEMPORARY", jdbc.queryForObject("SELECT status::text FROM image WHERE id=?",
                String::class.java, image))
            assertNull(jdbc.queryForObject("SELECT entity_id FROM image WHERE id=?", Long::class.java, image))
        }
        verifyNoInteractions(s3, mail)
    }

    @Test
    fun `ORDER upload shares participant order lock sequence while ORDER delete refuses immediately`() {
        val (buyer, order) = order()
        val image = rows.insertBatch(UploadTarget(StorageLocation.ORDER, null, buyer),
            listOf(ImageToInsert("claim.jpg", 10, 10))).single()
        clearInvocations(s3, mail)
        val pool = Executors.newSingleThreadExecutor()
        lateinit var uploaded: Future<List<Long>>
        try {
            TransactionTemplate(manager).executeWithoutResult {
                jdbc.queryForObject("SELECT id FROM participant WHERE id=? FOR UPDATE", Long::class.java, buyer)
                jdbc.queryForObject("SELECT id FROM \"order\" WHERE id=? FOR UPDATE", Long::class.java, order)
                port.activate(order, buyer, listOf(image))
                pool.submit {
                    assertThrows(ImageCommandDenied::class.java) {
                        rows.markDeleted(listOf(image), StorageLocation.ORDER, buyer)
                    }
                }.get(5, TimeUnit.SECONDS)
                val started = CountDownLatch(1)
                uploaded = pool.submit<List<Long>> {
                    started.countDown()
                    rows.insertBatch(UploadTarget(StorageLocation.ORDER, order, buyer),
                        listOf(ImageToInsert("concurrent-upload.jpg", 10, 10)))
                }
                assertTrue(started.await(5, TimeUnit.SECONDS))
                assertThrows(TimeoutException::class.java) { uploaded.get(200, TimeUnit.MILLISECONDS) }
            }
            val newImage = uploaded.get(10, TimeUnit.SECONDS).single()
            assertEquals("ACTIVE", jdbc.queryForObject("SELECT status::text FROM image WHERE id=?",
                String::class.java, image))
            assertEquals("TEMPORARY", jdbc.queryForObject("SELECT status::text FROM image WHERE id=?",
                String::class.java, newImage))
            assertEquals(order, jdbc.queryForObject("SELECT entity_id FROM image WHERE id=?",
                Long::class.java, newImage))
            verifyNoInteractions(s3, mail)
        } finally { pool.shutdownNow() }
    }

    private fun order(): Pair<Long, Long> {
        val buyer = participant()
        val seller = participant()
        val address = jdbc.queryForObject("INSERT INTO address(country,city) VALUES ('RU','Moscow') RETURNING id",
            Long::class.java)!!
        val transfer = jdbc.queryForObject("""INSERT INTO transfer(sending,participant_id)
            VALUES ('PRODUCT_PICKUP',?) RETURNING id""", Long::class.java, seller)!!
        val order = jdbc.queryForObject("""INSERT INTO "order"(seller_id,customer_id,count,status,product_id,
            address_id,transfer_id,product_name,product_unit_price,product_currency,product_availability)
            VALUES (?,?,1,'AWAITING_PAYMENT',1,?,?,'snapshot',1,'RUB','PURCHASABLE') RETURNING id""",
            Long::class.java, seller, buyer, address, transfer)!!
        return buyer to order
    }

    private fun participant(): Long = jdbc.queryForObject("""INSERT INTO participant(login,password,status)
        VALUES (?,'test-hash','ACTIVE') RETURNING id""", Long::class.java, UUID.randomUUID().toString())!!

    companion object {
        private val postgres = EmbeddedPostgres.start()
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
