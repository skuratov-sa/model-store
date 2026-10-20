package com.model_store.modern.media.image.command

import com.model_store.configuration.property.ApplicationProperties
import com.model_store.configuration.property.S3ConfigurationProperties
import com.model_store.exception.ApiException
import com.model_store.exception.constant.ErrorCode
import com.model_store.mapper.ImageMapper
import com.model_store.repository.*
import com.model_store.service.ImageService
import com.model_store.service.ProductService
import com.model_store.service.S3Service
import com.model_store.service.impl.ImageServiceImpl
import com.model_store.service.impl.OrderCaseService
import io.r2dbc.spi.ConnectionFactories
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.data.r2dbc.repository.support.R2dbcRepositoryFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.r2dbc.connection.R2dbcTransactionManager
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.transaction.reactive.TransactionalOperator
import reactor.core.publisher.Mono
import java.time.Duration

/** Actual legacy services and R2DBC repository SQL on a private database, without a boot context. */
class OrderImageEvidenceLegacyContractTest {
    @Test
    fun `actual legacy proof rejects null ineligible and repeated images with its original error`() = database { db ->
        val image = db.image()
        db.proof.activateOrderProof(image, 10, 1).block(TIMEOUT)
        assertEquals("ACTIVE", db.status(image))
        for (id in listOf(null, Long.MAX_VALUE, image, db.image(uploader = null),
            db.image(tag = "PRODUCT"), db.image(status = "DELETE"), db.image(uploader = 2),
            db.image(entityId = 11))) {
            val error = assertThrows(ApiException::class.java) {
                db.proof.activateOrderProof(id, 10, 1).block(TIMEOUT)
            }
            assertEquals(ErrorCode.IMAGE_NOT_FOUND, error.code)
            assertEquals(404, error.status.value())
            assertEquals("Подтверждение оплаты не найдено", error.message)
        }
        verifyNoInteractions(db.storage)
    }

    @Test
    fun `actual legacy case accepts null empty rejects duplicates and rolls back mixed evidence`() = database { db ->
        db.attach(null).block(TIMEOUT)
        db.attach(emptyList()).block(TIMEOUT)
        val image = db.image()
        for (ids in listOf(listOf(image, image), listOf(null), listOf(null, null))) {
            val error = assertThrows(IllegalArgumentException::class.java) { db.attach(ids).block(TIMEOUT) }
            assertEquals("Операция недоступна для текущего состояния заказа или обращения", error.message)
            assertEquals("TEMPORARY", db.status(image))
        }
        assertThrows(IllegalArgumentException::class.java) {
            db.transactions.transactional(db.attach(listOf(image, Long.MAX_VALUE))).block(TIMEOUT)
        }
        assertEquals("TEMPORARY", db.status(image))
        assertEquals(0, db.jdbc.queryForObject("SELECT count(*) FROM order_case_image", Int::class.java))
        db.transactions.transactional(db.attach(listOf(image))).block(TIMEOUT)
        assertEquals("ACTIVE", db.status(image))
        assertEquals(1, db.jdbc.queryForObject("SELECT count(*) FROM order_case_image", Int::class.java))
        assertThrows(IllegalArgumentException::class.java) { db.attach(listOf(image)).block(TIMEOUT) }
        verifyNoInteractions(db.storage)
    }

    private class Database(val jdbc: JdbcTemplate, val proof: ImageService, val cases: OrderCaseService,
                           val transactions: TransactionalOperator, val storage: S3Service) {
        fun image(tag: String = "ORDER", status: String = "TEMPORARY", uploader: Long? = 1,
                  entityId: Long? = null): Long = jdbc.queryForObject("""INSERT INTO image(tag,status,uploaded_by,entity_id)
            VALUES (?::image_tag,?::image_status,?,?) RETURNING id""", Long::class.java, tag, status, uploader, entityId)!!
        fun status(image: Long): String = jdbc.queryForObject(
            "SELECT status::text FROM image WHERE id=?", String::class.java, image)!!
        @Suppress("UNCHECKED_CAST")
        fun attach(ids: List<Long?>?): Mono<Void> {
            val method = OrderCaseService::class.java.getDeclaredMethod("attachEvidence",
                Long::class.javaObjectType, Long::class.javaObjectType, Long::class.javaObjectType, List::class.java)
            method.isAccessible = true
            return method.invoke(cases, 1L, 10L, 1L, ids) as Mono<Void>
        }
    }

    private fun database(action: (Database) -> Unit) {
        EmbeddedPostgres.start().use { postgres ->
            val jdbc = JdbcTemplate(postgres.postgresDatabase)
            jdbc.execute("CREATE TYPE image_tag AS ENUM ('ORDER','PRODUCT','PARTICIPANT','SYSTEM')")
            jdbc.execute("CREATE TYPE image_status AS ENUM ('TEMPORARY','ACTIVE','DELETE')")
            jdbc.execute("""CREATE TABLE image(id BIGSERIAL PRIMARY KEY, tag image_tag NOT NULL,
                status image_status NOT NULL, entity_id BIGINT, uploaded_by BIGINT)""")
            jdbc.execute("CREATE TABLE order_case_image(case_id BIGINT, image_id BIGINT UNIQUE, uploaded_by BIGINT)")
            val factory = ConnectionFactories.get("r2dbc:postgresql://postgres@localhost:${postgres.port}/postgres")
            val client = DatabaseClient.create(factory)
            val images = R2dbcRepositoryFactory(R2dbcEntityTemplate(factory)).getRepository(ImageRepository::class.java)
            val orders = mock(OrderRepository::class.java)
            val participants = mock(ParticipantRepository::class.java)
            val storage = mock(S3Service::class.java)
            val proof = ImageServiceImpl(images, storage, mock(ImageMapper::class.java), participants,
                mock(ProductRepository::class.java), orders, client, mock(ApplicationProperties::class.java),
                mock(S3ConfigurationProperties::class.java))
            val cases = OrderCaseService(orders, mock(OrderCaseRepository::class.java), images, proof,
                mock(ProductService::class.java), participants, client)
            action(Database(jdbc, proof, cases, TransactionalOperator.create(R2dbcTransactionManager(factory)), storage))
        }
    }

    companion object { private val TIMEOUT = Duration.ofSeconds(10) }
}
