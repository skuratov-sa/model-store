package com.model_store.modern.media.image.command

import com.model_store.modern.media.image.command.application.OrderImageEvidenceFailure
import com.model_store.modern.media.image.command.application.OrderImageEvidenceProblem
import com.model_store.modern.media.image.command.infrastructure.JdbcOrderImageEvidence
import com.model_store.repository.ImageRepository
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.data.r2dbc.repository.Query
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

class OrderImageEvidenceTest {
    @Test
    fun `single and batch claims preserve legacy ORDER predicate and reject repeats`() = database { db ->
        val unassigned = db.image()
        val bound = db.image(entityId = 10)
        assertEquals(2, db.jpa.execute { db.port.activate(10, 1, listOf(bound, unassigned)) })
        assertEquals(2, db.jdbc.queryForObject(
            "SELECT count(*) FROM image WHERE id IN (?,?) AND status='ACTIVE' AND entity_id=10",
            Int::class.java, unassigned, bound))
        db.problem(OrderImageEvidenceProblem.IMAGE_NOT_FOUND) {
            db.jpa.execute { db.port.activate(10, 1, listOf(unassigned)) }
        }
        val proof = db.image()
        assertEquals(1, db.jpa.execute { db.port.activate(10, 1, listOf(proof)) })
        assertEquals(0, db.jpa.execute { db.port.activate(10, 1, emptyList()) })
        assertEquals(0, db.jpa.execute { db.port.activate(10, 1, null) })
        db.problem(OrderImageEvidenceProblem.DUPLICATE_IDS) {
            db.jpa.execute { db.port.activate(10, 1, listOf(proof, proof)) }
        }
        db.problem(OrderImageEvidenceProblem.IMAGE_NOT_FOUND) {
            db.jpa.execute { db.port.activate(10, 1, listOf(null)) }
        }
        db.problem(OrderImageEvidenceProblem.INVALID_INPUT) {
            db.jpa.execute { db.port.activate(0, 1, listOf(proof)) }
        }
    }

    @Test
    fun `invalid rows reject entire batch and caller record rolls back`() = database { db ->
        val cases = listOf(
            db.image(tag = "PRODUCT"), db.image(tag = "PARTICIPANT"),
            db.image(status = "ACTIVE"), db.image(status = "DELETE"),
            db.image(uploader = 2), db.image(uploader = null),
            db.image(entityId = 11), Long.MAX_VALUE,
        )
        for (invalid in cases) {
            val valid = db.image()
            db.problem(OrderImageEvidenceProblem.IMAGE_NOT_FOUND) {
                db.jpa.executeWithoutResult {
                    db.jdbc.update("INSERT INTO caller_record VALUES (?)", valid)
                    db.port.activate(10, 1, listOf(valid, invalid))
                }
            }
            assertEquals("TEMPORARY", db.status(valid))
            assertNull(db.entity(valid))
            assertEquals(0, db.jdbc.queryForObject("SELECT count(*) FROM caller_record WHERE id=?",
                Int::class.java, valid))
        }
        val valid = db.image()
        assertThrows(IllegalStateException::class.java) {
            db.jpa.executeWithoutResult {
                db.port.activate(10, 1, listOf(valid))
                db.jdbc.update("INSERT INTO caller_record VALUES (?)", valid)
                throw IllegalStateException("late ordering failure")
            }
        }
        assertEquals("TEMPORARY", db.status(valid))
        assertNull(db.entity(valid))
        assertEquals(0, db.jdbc.queryForObject("SELECT count(*) FROM caller_record WHERE id=?",
            Int::class.java, valid))
    }

    @Test
    fun `only a writable transaction on the matching JPA manager can claim`() = database { db ->
        val id = db.image()
        db.problem(OrderImageEvidenceProblem.TRANSACTION_REQUIRED) { db.port.activate(10, 1, listOf(id)) }
        db.problem(OrderImageEvidenceProblem.TRANSACTION_REQUIRED) {
            db.jdbcTx.execute { db.port.activate(10, 1, listOf(id)) }
        }
        val readOnly = TransactionTemplate(db.jpa.transactionManager!!).apply { isReadOnly = true }
        db.problem(OrderImageEvidenceProblem.TRANSACTION_REQUIRED) {
            readOnly.execute { db.port.activate(10, 1, listOf(id)) }
        }
        assertEquals("TEMPORARY", db.status(id))
    }

    @Test
    fun `a foreign JPA EMF on the same DataSource is rejected`() = database { db ->
        val id = db.image()
        val foreign = entityManagerFactory(db.source)
        try {
            val manager = JpaTransactionManager(foreign.`object`!!).apply {
                dataSource = db.source
                afterPropertiesSet()
            }
            db.problem(OrderImageEvidenceProblem.TRANSACTION_REQUIRED) {
                TransactionTemplate(manager).execute { db.port.activate(10, 1, listOf(id)) }
            }
        } finally { foreign.destroy() }
        assertEquals("TEMPORARY", db.status(id))
        assertEquals(1, db.jpa.execute { db.port.activate(10, 1, listOf(id)) })
    }

    @Test
    fun `NOWAIT gives a typed conflict without waiting for another order`() = database { db ->
        val id = db.image()
        val held = db.source.connection
        val pool = Executors.newSingleThreadExecutor()
        try {
            held.autoCommit = false
            held.prepareStatement("SELECT id FROM image WHERE id=? FOR UPDATE").use { statement ->
                statement.setLong(1, id)
                statement.executeQuery().use { assertTrue(it.next()) }
            }
            val result = pool.submit<OrderImageEvidenceProblem> {
                try {
                    db.jpa.execute {
                        db.jdbc.update("INSERT INTO caller_record VALUES (?)", id)
                        db.port.activate(11, 2, listOf(id))
                    }
                    throw AssertionError("claim unexpectedly succeeded")
                } catch (failure: OrderImageEvidenceFailure) { failure.problem }
            }
            assertEquals(OrderImageEvidenceProblem.IMAGE_BUSY, result.get(5, TimeUnit.SECONDS))
        } finally {
            held.rollback()
            held.close()
            pool.shutdownNow()
        }
        assertEquals("TEMPORARY", db.status(id))
        assertEquals(0, db.jdbc.queryForObject("SELECT count(*) FROM caller_record", Int::class.java))
        assertEquals(1, db.jpa.execute { db.port.activate(10, 1, listOf(id)) })
        db.problem(OrderImageEvidenceProblem.IMAGE_NOT_FOUND) {
            db.jpa.execute { db.port.activate(11, 2, listOf(id)) }
        }
    }

    @Test
    fun `concurrent orders claim a shared batch once in ascending image order`() = database { db ->
        val first = db.image()
        val second = db.image()
        val start = CountDownLatch(2)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val results = listOf(10L, 11L).map { orderId ->
                pool.submit<String> {
                    start.countDown()
                    assertTrue(start.await(5, TimeUnit.SECONDS))
                    try {
                        db.jpa.execute { db.port.activate(orderId, 1,
                            if (orderId == 10L) listOf(second, first) else listOf(first, second)) }
                        "claimed:$orderId"
                    } catch (failure: OrderImageEvidenceFailure) { failure.problem.name }
                }
            }.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it.startsWith("claimed:") })
            assertTrue(results.any { it == "IMAGE_BUSY" || it == "IMAGE_NOT_FOUND" })
            val winner = results.single { it.startsWith("claimed:") }.substringAfter(':').toLong()
            assertEquals(winner, db.entity(first))
            assertEquals(winner, db.entity(second))
            assertEquals("ACTIVE", db.status(first))
            assertEquals("ACTIVE", db.status(second))
        } finally { pool.shutdownNow() }
    }

    @Test
    fun `competing uploaders never transfer another party's image`() = database { db ->
        val id = db.image(uploader = 1)
        val start = CountDownLatch(2)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val results = listOf(1L, 2L).map { uploader ->
                pool.submit<String> {
                    start.countDown()
                    assertTrue(start.await(5, TimeUnit.SECONDS))
                    try {
                        db.jpa.execute { db.port.activate(if (uploader == 1L) 10 else 11, uploader, listOf(id)) }
                        "claimed:$uploader"
                    } catch (failure: OrderImageEvidenceFailure) { failure.problem.name }
                }
            }.map { it.get(10, TimeUnit.SECONDS) }
            assertNotEquals("claimed:2", results[1])
            assertTrue(results[1] == "IMAGE_BUSY" || results[1] == "IMAGE_NOT_FOUND")
            if (db.status(id) == "TEMPORARY") {
                assertEquals(1, db.jpa.execute { db.port.activate(10, 1, listOf(id)) })
            }
        } finally { pool.shutdownNow() }
        assertEquals("ACTIVE", db.status(id))
        assertEquals(10L, db.entity(id))
        db.problem(OrderImageEvidenceProblem.IMAGE_NOT_FOUND) {
            db.jpa.execute { db.port.activate(11, 2, listOf(id)) }
        }
    }

    @Test
    fun `legacy repository SQL agrees on matching and mismatching rows`() = database { db ->
        val method = ImageRepository::class.java.getMethod("activateCaseEvidence",
            Array<Long>::class.java, java.lang.Long::class.java, java.lang.Long::class.java)
        val sql = method.getAnnotation(Query::class.java).value
            .replace(":ids", "?::bigint[]").replace(":orderId", "?").replace(":participantId", "?")
        for ((tag, status, uploader, entity, expected) in listOf(
            LegacyCase("ORDER", "TEMPORARY", 1, null, 1),
            LegacyCase("ORDER", "TEMPORARY", 1, 10, 1),
            LegacyCase("ORDER", "TEMPORARY", 1, 11, 0),
            LegacyCase("ORDER", "TEMPORARY", 2, null, 0),
            LegacyCase("ORDER", "ACTIVE", 1, null, 0),
            LegacyCase("ORDER", "DELETE", 1, null, 0),
            LegacyCase("PRODUCT", "TEMPORARY", 1, null, 0),
        )) {
            val modern = db.image(tag, status, uploader, entity)
            val legacy = db.image(tag, status, uploader, entity)
            val legacyUpdated = db.source.connection.use { connection ->
                connection.prepareStatement(sql).use { statement ->
                    statement.setLong(1, 10)
                    statement.setArray(2, connection.createArrayOf("bigint", arrayOf(legacy)))
                    statement.setLong(3, 1)
                    statement.setLong(4, 10)
                    statement.executeUpdate()
                }
            }
            assertEquals(expected, legacyUpdated)
            if (expected == 1) assertEquals(1, db.jpa.execute { db.port.activate(10, 1, listOf(modern)) })
            else db.problem(OrderImageEvidenceProblem.IMAGE_NOT_FOUND) {
                db.jpa.execute { db.port.activate(10, 1, listOf(modern)) }
            }
            assertEquals(db.status(legacy), db.status(modern))
            assertEquals(db.entity(legacy), db.entity(modern))
        }
    }

    private data class LegacyCase(val tag: String, val status: String, val uploader: Long?,
                                  val entity: Long?, val expected: Int)

    private class Database(val source: DataSource, val jdbc: JdbcTemplate,
                           val port: JdbcOrderImageEvidence, val jpa: TransactionTemplate,
                           val jdbcTx: TransactionTemplate) {
        fun image(tag: String = "ORDER", status: String = "TEMPORARY", uploader: Long? = 1,
                  entityId: Long? = null): Long = jdbc.queryForObject(
            """INSERT INTO image(tag,status,uploaded_by,entity_id)
               VALUES (?::image_tag,?::image_status,?,?) RETURNING id""",
            Long::class.java, tag, status, uploader, entityId)!!
        fun status(id: Long): String = jdbc.queryForObject(
            "SELECT status::text FROM image WHERE id=?", String::class.java, id)!!
        fun entity(id: Long): Long? = jdbc.queryForObject(
            "SELECT entity_id FROM image WHERE id=?", Long::class.java, id)
        fun problem(expected: OrderImageEvidenceProblem, action: () -> Unit) {
            assertEquals(expected, assertThrows(OrderImageEvidenceFailure::class.java, action).problem)
        }
    }

    private fun database(block: (Database) -> Unit) {
        EmbeddedPostgres.start().use { postgres ->
            val source = postgres.postgresDatabase
            val jdbc = JdbcTemplate(source)
            jdbc.execute("CREATE TYPE image_tag AS ENUM ('ORDER','PRODUCT','PARTICIPANT','SYSTEM')")
            jdbc.execute("CREATE TYPE image_status AS ENUM ('TEMPORARY','ACTIVE','DELETE')")
            jdbc.execute("""CREATE TABLE image(id BIGSERIAL PRIMARY KEY, tag image_tag NOT NULL,
                status image_status NOT NULL, entity_id BIGINT, uploaded_by BIGINT)""")
            jdbc.execute("CREATE TABLE caller_record(id BIGINT PRIMARY KEY)")
            val emfBean = entityManagerFactory(source)
            try {
                val emf = emfBean.`object`!!
                val manager = JpaTransactionManager(emf).apply { dataSource = source; afterPropertiesSet() }
                block(Database(source, jdbc, JdbcOrderImageEvidence(emf, source),
                    TransactionTemplate(manager), TransactionTemplate(DataSourceTransactionManager(source))))
            } finally { emfBean.destroy() }
        }
    }

    private fun entityManagerFactory(source: DataSource) = LocalContainerEntityManagerFactoryBean().apply {
        dataSource = source
        setPackagesToScan("com.model_store.modern.identity.participant.infrastructure")
        jpaVendorAdapter = HibernateJpaVendorAdapter()
        setJpaPropertyMap(mapOf("hibernate.hbm2ddl.auto" to "none"))
        afterPropertiesSet()
    }
}
