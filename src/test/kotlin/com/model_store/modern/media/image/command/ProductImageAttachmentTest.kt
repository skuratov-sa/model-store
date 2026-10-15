package com.model_store.modern.media.image.command

import com.model_store.modern.media.image.command.application.ProductImageAttachmentFailure
import com.model_store.modern.media.image.command.application.ProductImageAttachmentProblem
import com.model_store.modern.media.image.command.infrastructure.JdbcProductImageAttachment
import com.model_store.modern.media.image.command.infrastructure.JdbcImageCommandRows
import com.model_store.modern.media.storage.domain.StorageLocation
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.SQLException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

class ProductImageAttachmentTest {
    @Test
    fun `claim validates every image and preserves legacy attached rows`() = database { jdbc, port, tx, _ ->
        val first = image(jdbc, 1)
        val second = image(jdbc, 1)
        tx.executeWithoutResult { port.claim(10, 1, listOf(second, first)) }
        assertEquals(2, jdbc.queryForObject(
            "SELECT count(*) FROM image WHERE entity_id=10 AND status='ACTIVE'", Int::class.java))

        val legacy = image(jdbc, null, status = "ACTIVE", entityId = 10)
        tx.executeWithoutResult { port.claim(10, 1, listOf(legacy)) }
        assertEquals(null, jdbc.queryForObject("SELECT uploaded_by FROM image WHERE id=?", Long::class.java, legacy))
        val fourth = image(jdbc, 1)
        tx.executeWithoutResult { port.claim(10, 1, listOf(first, legacy, fourth)) }
        tx.executeWithoutResult { port.claim(10, 1, listOf(first)) }
        assertEquals(4, jdbc.queryForObject(
            "SELECT count(*) FROM image WHERE entity_id=10 AND status='ACTIVE'", Int::class.java))

        val staged = image(jdbc, 1)
        assertThrows(IllegalStateException::class.java) { port.claim(10, 1, listOf(staged)) }
        assertThrows(IllegalStateException::class.java) { port.markProductImagesDeleted(10) }
        val cases = listOf(
            ProductImageAttachmentProblem.DUPLICATE_IDS to listOf(staged, staged),
            ProductImageAttachmentProblem.IMAGE_NOT_FOUND to listOf(staged, Long.MAX_VALUE),
            ProductImageAttachmentProblem.ACCESS_DENIED to listOf(staged, image(jdbc, 2)),
            ProductImageAttachmentProblem.ACCESS_DENIED to listOf(staged, image(jdbc, null)),
            ProductImageAttachmentProblem.ACCESS_DENIED to listOf(staged, image(jdbc, 1, tag = "ORDER")),
            ProductImageAttachmentProblem.ACCESS_DENIED to listOf(staged, image(jdbc, 1, tag = "PARTICIPANT")),
            ProductImageAttachmentProblem.ACCESS_DENIED to listOf(staged, image(jdbc, 1, status = "DELETE")),
            ProductImageAttachmentProblem.ACCESS_DENIED to listOf(staged, image(jdbc, 1, status = "ACTIVE")),
            ProductImageAttachmentProblem.ACCESS_DENIED to listOf(staged, image(jdbc, 2, status = "ACTIVE", entityId = 11)),
            ProductImageAttachmentProblem.ALREADY_ATTACHED to listOf(staged, image(jdbc, 1, status = "ACTIVE", entityId = 11)),
            ProductImageAttachmentProblem.TOO_MANY_IMAGES to listOf(staged, image(jdbc, 1)),
        )
        for ((problem, ids) in cases) {
            val failure = assertThrows(ProductImageAttachmentFailure::class.java) {
                tx.executeWithoutResult { port.claim(10, 1, ids) }
            }
            assertEquals(problem, failure.problem)
            assertEquals("TEMPORARY", jdbc.queryForObject("SELECT status::text FROM image WHERE id=?",
                String::class.java, staged))
            assertNull(jdbc.queryForObject("SELECT entity_id FROM image WHERE id=?", Long::class.java, staged))
        }
    }

    @Test
    fun `claim and soft delete share caller rollback`() = database { jdbc, port, tx, _ ->
        val staged = image(jdbc, 1)
        assertThrows(IllegalStateException::class.java) {
            tx.executeWithoutResult {
                port.claim(10, 1, listOf(staged))
                throw IllegalStateException("later product or category failure")
            }
        }
        assertEquals("TEMPORARY", jdbc.queryForObject("SELECT status::text FROM image WHERE id=?",
            String::class.java, staged))
        assertNull(jdbc.queryForObject("SELECT entity_id FROM image WHERE id=?", Long::class.java, staged))

        tx.executeWithoutResult { port.claim(10, 1, listOf(staged)) }
        val legacy = image(jdbc, null, status = "ACTIVE", entityId = 10)
        assertThrows(IllegalStateException::class.java) {
            tx.executeWithoutResult {
                port.markProductImagesDeleted(10)
                throw IllegalStateException("later product failure")
            }
        }
        assertEquals(2, jdbc.queryForObject(
            "SELECT count(*) FROM image WHERE entity_id=10 AND status='ACTIVE'", Int::class.java))
        tx.executeWithoutResult { port.markProductImagesDeleted(10) }
        assertEquals(2, jdbc.queryForObject(
            "SELECT count(*) FROM image WHERE entity_id=10 AND status='DELETE'", Int::class.java))
        assertEquals("DELETE", jdbc.queryForObject("SELECT status::text FROM image WHERE id=?",
            String::class.java, legacy))
    }

    @Test
    fun `contended image fails immediately and leaves transaction untouched`() = database { jdbc, port, tx, dataSource ->
        val staged = image(jdbc, 1)
        val holder = dataSource.connection
        val pool = Executors.newSingleThreadExecutor()
        try {
            holder.autoCommit = false
            holder.prepareStatement("SELECT id FROM image WHERE id=? FOR UPDATE").use { statement ->
                statement.setLong(1, staged)
                statement.executeQuery().use { assertTrue(it.next()) }
            }
            val claim = pool.submit<ProductImageAttachmentProblem> {
                try {
                    tx.executeWithoutResult { port.claim(10, 1, listOf(staged)) }
                    throw AssertionError("claim unexpectedly succeeded")
                } catch (failure: ProductImageAttachmentFailure) { failure.problem }
            }
            assertEquals(ProductImageAttachmentProblem.IMAGE_BUSY, claim.get(5, TimeUnit.SECONDS))
        } finally {
            holder.rollback()
            holder.close()
            pool.shutdownNow()
        }
        assertEquals("TEMPORARY", jdbc.queryForObject("SELECT status::text FROM image WHERE id=?",
            String::class.java, staged))
        assertNull(jdbc.queryForObject("SELECT entity_id FROM image WHERE id=?", Long::class.java, staged))
    }

    @Test
    fun `opposing media and product lock order resolves without deadlock`() = database { jdbc, port, tx, dataSource ->
        val attached = image(jdbc, 1, status = "ACTIVE", entityId = 10)
        val mediaRows = JdbcImageCommandRows(NamedParameterJdbcTemplate(dataSource),
            DataSourceTransactionManager(dataSource))
        val pool = Executors.newSingleThreadExecutor()
        try {
            val conflict = assertThrows(ProductImageAttachmentFailure::class.java) {
                tx.executeWithoutResult {
                    jdbc.queryForObject("SELECT id FROM product WHERE id=10 FOR UPDATE", Long::class.java)
                    val mediaDelete = pool.submit<Unit> {
                        mediaRows.markDeleted(listOf(attached), StorageLocation.PRODUCT, 1)
                    }
                    awaitImageLock(dataSource, attached)
                    try {
                        port.markProductImagesDeleted(10)
                    } finally {
                        // The media transaction is waiting for this product transaction to roll back.
                        assertFalse(mediaDelete.isDone)
                    }
                }
            }
            assertEquals(ProductImageAttachmentProblem.IMAGE_BUSY, conflict.problem)
            pool.shutdown()
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
            assertEquals("DELETE", jdbc.queryForObject("SELECT status::text FROM image WHERE id=?",
                String::class.java, attached))
        } finally {
            pool.shutdownNow()
        }
    }

    private fun awaitImageLock(dataSource: DataSource, imageId: Long) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            try {
                dataSource.connection.use { connection ->
                    connection.prepareStatement("SELECT id FROM image WHERE id=? FOR UPDATE NOWAIT").use { statement ->
                        statement.setLong(1, imageId)
                        statement.executeQuery().use { assertTrue(it.next()) }
                    }
                }
            } catch (error: SQLException) {
                if (error.sqlState == "55P03") return
                throw error
            }
            Thread.sleep(20)
        }
        fail<Unit>("Media delete did not lock the image")
    }

    private fun database(block: (JdbcTemplate, JdbcProductImageAttachment, TransactionTemplate, DataSource) -> Unit) {
        EmbeddedPostgres.start().use { postgres ->
            val dataSource = postgres.postgresDatabase
            val jdbc = JdbcTemplate(dataSource)
            jdbc.execute("CREATE TYPE image_tag AS ENUM ('PARTICIPANT', 'PRODUCT', 'ORDER', 'SYSTEM')")
            jdbc.execute("CREATE TYPE image_status AS ENUM ('ACTIVE', 'TEMPORARY', 'DELETE')")
            jdbc.execute("CREATE TYPE participant_role AS ENUM ('USER', 'ADMIN')")
            jdbc.execute("CREATE TYPE participant_status AS ENUM ('ACTIVE', 'BLOCKED')")
            jdbc.execute("CREATE TYPE product_status AS ENUM ('ACTIVE', 'DELETED')")
            jdbc.execute("CREATE TYPE product_availability AS ENUM ('PURCHASABLE', 'GIVEAWAY')")
            jdbc.execute("CREATE TABLE participant (id BIGINT PRIMARY KEY, role participant_role, status participant_status, is_agent BOOLEAN)")
            jdbc.execute("CREATE TABLE product (id BIGINT PRIMARY KEY, participant_id BIGINT, status product_status, availability product_availability)")
            jdbc.execute("""CREATE TABLE image (id BIGSERIAL PRIMARY KEY, filename TEXT, tag image_tag NOT NULL,
                status image_status NOT NULL, entity_id BIGINT, uploaded_by BIGINT)""")
            jdbc.execute("INSERT INTO participant VALUES (1, 'USER', 'ACTIVE', false), (2, 'USER', 'ACTIVE', false)")
            jdbc.execute("INSERT INTO product VALUES (10, 1, 'ACTIVE', 'PURCHASABLE'), (11, 2, 'ACTIVE', 'PURCHASABLE')")
            block(jdbc, JdbcProductImageAttachment(NamedParameterJdbcTemplate(dataSource), 4),
                TransactionTemplate(DataSourceTransactionManager(dataSource)), dataSource)
        }
    }

    private fun image(jdbc: JdbcTemplate, uploader: Long?, tag: String = "PRODUCT",
                      status: String = "TEMPORARY", entityId: Long? = null): Long = jdbc.queryForObject(
        """INSERT INTO image(filename,tag,status,entity_id,uploaded_by)
           VALUES ('test.jpg',?::image_tag,?::image_status,?,?) RETURNING id""",
        Long::class.java, tag, status, entityId, uploader,
    )!!
}
