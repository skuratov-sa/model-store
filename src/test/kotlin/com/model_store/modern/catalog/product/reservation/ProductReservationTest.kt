package com.model_store.modern.catalog.product.reservation

import com.model_store.modern.catalog.product.reservation.application.*
import com.model_store.modern.catalog.product.reservation.infrastructure.JdbcProductReservation
import com.model_store.modern.catalog.product.command.application.LockMode
import com.model_store.modern.catalog.product.command.infrastructure.JdbcProductCommandStore
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ProductReservationTest {
    @Test
    fun `snapshot and stock rules follow legacy availability`() = database { db ->
        val limited = db.product(5)
        val unlimited = db.product(null)
        val preorder = db.product(3, "PREORDER")
        val external = db.product(4, "EXTERNAL_PRODUCT")
        val snapshots = db.tx.execute {
            db.port.reserve(listOf(ProductQuantity(preorder, 2), ProductQuantity(unlimited, 10),
                ProductQuantity(limited, 2), ProductQuantity(external, 2)))
        }
        assertEquals(listOf(limited, unlimited, preorder, external), snapshots.map { it.id })
        assertEquals(5, snapshots.first().count)
        assertEquals(7.5f, snapshots.first().unitPrice)
        assertEquals("RUB", snapshots.first().currency)
        assertEquals(1L, snapshots.first().ownerId)
        assertEquals("Product", snapshots.first().name)
        assertEquals(3, db.count(limited))
        assertNull(db.count(unlimited))
        assertEquals(3, db.count(preorder))
        assertEquals(4, db.count(external))
        db.expect(ProductReservationProblem.OUT_OF_STOCK) {
            db.tx.executeWithoutResult { db.port.reserve(listOf(ProductQuantity(preorder, 4))) }
        }
    }

    @Test
    fun `invalid lines and invisible products leave all stock unchanged`() = database { db ->
        val good = db.product(5)
        val empty = db.product(1)
        val blocked = db.product(5, status = "BLOCKED")
        val giveaway = db.product(5, "GIVEAWAY")
        val cases = listOf(
            ProductReservationProblem.INVALID_PRODUCT_ID to listOf(ProductQuantity(0, 1)),
            ProductReservationProblem.INVALID_QUANTITY to listOf(ProductQuantity(good, 0)),
            ProductReservationProblem.DUPLICATE_IDS to listOf(ProductQuantity(good, 1), ProductQuantity(good, 2)),
            ProductReservationProblem.PRODUCT_NOT_FOUND to listOf(ProductQuantity(good, 1), ProductQuantity(Long.MAX_VALUE, 1)),
            ProductReservationProblem.PRODUCT_NOT_FOUND to listOf(ProductQuantity(good, 1), ProductQuantity(blocked, 1)),
            ProductReservationProblem.PRODUCT_NOT_FOUND to listOf(ProductQuantity(good, 1), ProductQuantity(giveaway, 1)),
            ProductReservationProblem.OUT_OF_STOCK to listOf(ProductQuantity(good, 1), ProductQuantity(empty, 2)),
        )
        cases.forEach { (problem, lines) ->
            db.expect(problem) { db.tx.executeWithoutResult { db.port.reserve(lines) } }
            assertEquals(5, db.count(good))
        }
        assertEquals(emptyList<OrderProductSnapshot>(), db.tx.execute { db.port.reserve(emptyList()) })
    }

    @Test
    fun `caller exception rolls back reservation on the same JPA transaction`() = database { db ->
        val id = db.product(2)
        assertThrows(IllegalStateException::class.java) {
            db.tx.executeWithoutResult {
                db.port.reserve(listOf(ProductQuantity(id, 1)))
                db.jdbc.update("UPDATE product SET name='changed' WHERE id=?", id)
                throw IllegalStateException("later order write failed")
            }
        }
        assertEquals(2, db.count(id))
        assertEquals("Product", db.jdbc.queryForObject("SELECT name FROM product WHERE id=?", String::class.java, id))
    }

    @Test
    fun `return changes only limited purchasable stock and detects overflow`() = database { db ->
        val limited = db.product(2)
        val deleted = db.product(2, status = "DELETED")
        val unlimited = db.product(null)
        val preorder = db.product(2, "PREORDER")
        db.tx.executeWithoutResult {
            assertTrue(db.port.returnLimitedStock(limited, 3))
            assertTrue(db.port.returnLimitedStock(deleted, 1))
            assertFalse(db.port.returnLimitedStock(unlimited, 3))
            assertFalse(db.port.returnLimitedStock(preorder, 3))
            assertFalse(db.port.returnLimitedStock(Long.MAX_VALUE, 3))
        }
        assertEquals(5, db.count(limited))
        assertEquals(3, db.count(deleted))
        assertNull(db.count(unlimited))
        assertEquals(2, db.count(preorder))
        val max = db.product(Int.MAX_VALUE)
        db.expect(ProductReservationProblem.COUNT_OVERFLOW) {
            db.tx.executeWithoutResult { db.port.returnLimitedStock(max, 1) }
        }
        assertEquals(Int.MAX_VALUE, db.count(max))
    }

    @Test
    fun `both methods reject calls without a transaction`() = database { db ->
        val id = db.product(2)
        db.expect(ProductReservationProblem.TRANSACTION_REQUIRED) {
            db.port.reserve(listOf(ProductQuantity(id, 1)))
        }
        db.expect(ProductReservationProblem.TRANSACTION_REQUIRED) { db.port.returnLimitedStock(id, 1) }
    }

    @Test
    fun `a JDBC-only transaction cannot call the JPA port`() = database { db ->
        val id = db.product(2)
        val jdbcOnly = TransactionTemplate(DataSourceTransactionManager(db.jdbc.dataSource!!))
        db.expect(ProductReservationProblem.TRANSACTION_REQUIRED) {
            jdbcOnly.executeWithoutResult { db.port.reserve(listOf(ProductQuantity(id, 1))) }
        }
        assertEquals(2, db.count(id))
    }

    @Test
    fun `two callers cannot reserve the same last item`() = database { db ->
        val id = db.product(1)
        val ready = CountDownLatch(2)
        val go = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val jobs = (1..2).map {
                pool.submit<String> {
                    ready.countDown()
                    assertTrue(go.await(5, TimeUnit.SECONDS))
                    try {
                        db.tx.executeWithoutResult { db.port.reserve(listOf(ProductQuantity(id, 1))) }
                        "reserved"
                    } catch (failure: ProductReservationFailure) { failure.problem.name }
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            go.countDown()
            assertEquals(setOf("reserved", "OUT_OF_STOCK"), jobs.map { it.get(10, TimeUnit.SECONDS) }.toSet())
            assertEquals(0, db.count(id))
        } finally {
            go.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun `opposite batch order serializes and prevents double reservation`() = database { db ->
        val first = db.product(1)
        val second = db.product(1)
        val ready = CountDownLatch(2)
        val go = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val jobs = listOf(listOf(first, second), listOf(second, first)).map { ids ->
                pool.submit<String> {
                    ready.countDown()
                    assertTrue(go.await(5, TimeUnit.SECONDS))
                    try {
                        db.tx.executeWithoutResult {
                            db.port.reserve(ids.map { ProductQuantity(it, 1) })
                        }
                        "reserved"
                    } catch (failure: ProductReservationFailure) { failure.problem.name }
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            go.countDown()
            assertEquals(setOf("reserved", "OUT_OF_STOCK"), jobs.map { it.get(10, TimeUnit.SECONDS) }.toSet())
            assertEquals(0, db.count(first))
            assertEquals(0, db.count(second))
        } finally {
            go.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun `reservation waits for catalog product command and reads its committed stock`() = database { db ->
        val id = db.product(1)
        val command = JdbcProductCommandStore(NamedParameterJdbcTemplate(db.jdbc.dataSource!!))
        val locked = CountDownLatch(1)
        val attempting = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val edit = pool.submit {
                db.tx.executeWithoutResult {
                    val record = command.lockProduct(id, LockMode.ACTIVE_ORDINARY)!!
                    command.update(record.copy(count = 0))
                    locked.countDown()
                    assertTrue(release.await(5, TimeUnit.SECONDS))
                }
            }
            assertTrue(locked.await(5, TimeUnit.SECONDS))
            val reserve = pool.submit<ProductReservationProblem> {
                attempting.countDown()
                try {
                    db.tx.executeWithoutResult { db.port.reserve(listOf(ProductQuantity(id, 1))) }
                    throw AssertionError("stock edit was ignored")
                } catch (failure: ProductReservationFailure) { failure.problem }
            }
            assertTrue(attempting.await(5, TimeUnit.SECONDS))
            Thread.sleep(100)
            assertFalse(reserve.isDone)
            release.countDown()
            edit.get(10, TimeUnit.SECONDS)
            assertEquals(ProductReservationProblem.OUT_OF_STOCK, reserve.get(10, TimeUnit.SECONDS))
            assertEquals(0, db.count(id))
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun `return and reservation serialize on the same product row`() = database { db ->
        val id = db.product(0)
        val returned = CountDownLatch(1)
        val attempting = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val giveBack = pool.submit {
                db.tx.executeWithoutResult {
                    assertTrue(db.port.returnLimitedStock(id, 1))
                    returned.countDown()
                    assertTrue(release.await(5, TimeUnit.SECONDS))
                }
            }
            assertTrue(returned.await(5, TimeUnit.SECONDS))
            val reserve = pool.submit {
                attempting.countDown()
                db.tx.executeWithoutResult { db.port.reserve(listOf(ProductQuantity(id, 1))) }
            }
            assertTrue(attempting.await(5, TimeUnit.SECONDS))
            Thread.sleep(100)
            assertFalse(reserve.isDone)
            release.countDown()
            giveBack.get(10, TimeUnit.SECONDS)
            reserve.get(10, TimeUnit.SECONDS)
            assertEquals(0, db.count(id))
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    private class Database(val jdbc: JdbcTemplate, val port: JdbcProductReservation, val tx: TransactionTemplate) {
        fun product(count: Int?, availability: String = "PURCHASABLE", status: String = "ACTIVE"): Long =
            jdbc.queryForObject(
                """INSERT INTO product(participant_id,name,price,prepayment_amount,count,currency,availability,status)
                   VALUES (1,'Product',7.5,1.5,?,'RUB',?::product_availability,?::product_status) RETURNING id""",
                Long::class.java, count, availability, status,
            )!!

        fun count(id: Long): Int? = jdbc.queryForObject("SELECT count FROM product WHERE id=?", Int::class.java, id)

        fun expect(problem: ProductReservationProblem, block: () -> Unit) {
            assertEquals(problem, assertThrows(ProductReservationFailure::class.java, block).problem)
        }
    }

    private fun database(block: (Database) -> Unit) {
        EmbeddedPostgres.start().use { postgres ->
            val source = postgres.postgresDatabase
            val jdbc = JdbcTemplate(source)
            jdbc.execute("CREATE TYPE currency AS ENUM ('RUB')")
            jdbc.execute("CREATE TYPE product_availability AS ENUM ('PURCHASABLE','PREORDER','EXTERNAL_PRODUCT','GIVEAWAY')")
            jdbc.execute("CREATE TYPE product_status AS ENUM ('ACTIVE','BLOCKED','DELETED')")
            jdbc.execute("""CREATE TABLE product(id BIGSERIAL PRIMARY KEY, participant_id BIGINT NOT NULL,
                name TEXT, description TEXT, price FLOAT NOT NULL, prepayment_amount FLOAT, count INTEGER,
                currency currency NOT NULL, originality TEXT, expiration_date TIMESTAMPTZ NOT NULL DEFAULT now(),
                availability product_availability NOT NULL, status product_status NOT NULL, used BOOLEAN NOT NULL DEFAULT false,
                external_url TEXT, giveaway_enabled BOOLEAN NOT NULL DEFAULT false)""")
            val emfBean = LocalContainerEntityManagerFactoryBean().apply {
                dataSource = source
                setPackagesToScan("com.model_store.modern.identity.participant.infrastructure")
                jpaVendorAdapter = HibernateJpaVendorAdapter()
                setJpaPropertyMap(mapOf("hibernate.hbm2ddl.auto" to "none"))
                afterPropertiesSet()
            }
            try {
                val manager = JpaTransactionManager(emfBean.`object`!!).apply { dataSource = source; afterPropertiesSet() }
                block(Database(jdbc, JdbcProductReservation(NamedParameterJdbcTemplate(source), emfBean.`object`!!),
                    TransactionTemplate(manager)))
            } finally {
                emfBean.destroy()
            }
        }
    }
}
