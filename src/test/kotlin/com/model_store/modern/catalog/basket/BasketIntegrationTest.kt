package com.model_store.modern.catalog.basket

import com.model_store.exception.constant.ErrorCode
import com.model_store.modern.catalog.basket.application.BasketFailure
import com.model_store.modern.catalog.basket.application.BasketUseCases
import com.model_store.modern.catalog.basket.infrastructure.JdbcBasketStore
import com.model_store.modern.catalog.product.search.domain.ProductSearchCriteria
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class BasketIntegrationTest {
    @Test
    fun `commands preserve errors ownership quantities and idempotent delete`() = database { jdbc, store, tx ->
        seed(jdbc)
        val cases = BasketUseCases(store)
        tx.executeWithoutResult { cases.add(1, 10, 2) }
        assertEquals(listOf(10L to 2), store.items(1).map { it.productId to it.count })
        assertTrue(store.items(2).isEmpty())
        assertError(ErrorCode.PRODUCT_ALREADY_IN_BASKET) { tx.executeWithoutResult { cases.add(1, 10, 1) } }
        assertError(ErrorCode.COUNT_INVALID) { tx.executeWithoutResult { cases.add(1, 11, -1) } }
        assertError(ErrorCode.COUNT_INVALID) { tx.executeWithoutResult { cases.update(1, 10, 0) } }
        assertError(ErrorCode.NOT_ENOUGH_STOCK) { tx.executeWithoutResult { cases.update(1, 10, 4) } }
        assertError(ErrorCode.PRODUCT_NOT_FOUND) { tx.executeWithoutResult { cases.add(1, 12, 1) } }
        assertError(ErrorCode.PRODUCT_NOT_FOUND) { tx.executeWithoutResult { cases.add(1, 13, 1) } }
        assertError(ErrorCode.PRODUCT_NOT_FOUND) { tx.executeWithoutResult { cases.add(1, 999, 1) } }
        assertError(ErrorCode.PARTICIPANT_NOT_FOUND) { tx.executeWithoutResult { cases.add(3, 10, 1) } }
        assertError(ErrorCode.BASKET_ITEM_NOT_FOUND) { tx.executeWithoutResult { cases.update(2, 10, 1) } }
        jdbc.update("INSERT INTO product_basket(participant_id, product_id, count) VALUES (3,10,1)")
        tx.executeWithoutResult { cases.remove(3, 10) }
        assertTrue(store.items(3).isEmpty())
        jdbc.update("UPDATE product SET count = NULL WHERE id = 14")
        tx.executeWithoutResult { cases.add(1, 14, Int.MAX_VALUE) }
        assertEquals(Int.MAX_VALUE, store.items(1).single { it.productId == 14L }.count)
        tx.executeWithoutResult { cases.update(1, 10, 3) }
        assertEquals(3, store.items(1).single { it.productId == 10L }.count)
        tx.executeWithoutResult { cases.remove(2, 10) }
        assertEquals(3, store.items(1).single { it.productId == 10L }.count)
        tx.executeWithoutResult { cases.remove(1, 10); cases.remove(1, 10) }
        assertEquals(listOf(14L), store.items(1).map { it.productId })
    }

    @Test
    fun `find filters adult giveaway and status while retaining insufficient stock`() = database { jdbc, store, tx ->
        seed(jdbc)
        jdbc.update("INSERT INTO product_basket(participant_id, product_id, count) VALUES (1,10,5),(1,11,1),(1,12,1),(1,13,1),(1,14,1),(2,11,1)")
        val cases = BasketUseCases(store)
        val mine = tx.execute { cases.find(1, ProductSearchCriteria()) }!!
        assertEquals(listOf(14L, 10L), mine.map { it.product.id })
        val lowStock = mine.single { it.product.id == 10L }
        assertEquals(5, lowStock.count)
        assertEquals(3, lowStock.availableCount)
        assertFalse(lowStock.enoughStock)
        val adult = tx.execute { cases.find(2, ProductSearchCriteria()) }!!
        assertEquals(listOf(11L), adult.map { it.product.id })
        assertEquals(listOf(10L), store.items(1).filter { it.productId == 10L }.map { it.productId })
        assertTrue(tx.execute { cases.find(1, ProductSearchCriteria(name = "' OR 1=1 --")) }!!.isEmpty())
    }

    @Test
    fun `same owner concurrent add has one winner and stable row`() = database { jdbc, store, tx ->
        seed(jdbc)
        val cases = BasketUseCases(store)
        val ready = CountDownLatch(2)
        val go = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val futures = (1..2).map {
                pool.submit<String> {
                    ready.countDown()
                    assertTrue(go.await(5, TimeUnit.SECONDS))
                    try {
                        tx.executeWithoutResult { cases.add(1, 10, 1) }
                        "ok"
                    } catch (e: BasketFailure) { e.code.name }
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            go.countDown()
            assertEquals(setOf("ok", "PRODUCT_ALREADY_IN_BASKET"), futures.map { it.get(10, TimeUnit.SECONDS) }.toSet())
            assertEquals(1, store.items(1).single().count)
        } finally { pool.shutdownNow() }
    }

    @Test
    fun `concurrent update and remove serialize without resurrecting a row`() = database { jdbc, store, tx ->
        seed(jdbc)
        val cases = BasketUseCases(store)
        tx.executeWithoutResult { cases.add(1, 10, 1) }
        val ready = CountDownLatch(2)
        val go = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val update = pool.submit<String> {
                ready.countDown()
                assertTrue(go.await(5, TimeUnit.SECONDS))
                try {
                    tx.executeWithoutResult { cases.update(1, 10, 2) }
                    "ok"
                } catch (e: BasketFailure) { e.code.name }
            }
            val remove = pool.submit {
                ready.countDown()
                assertTrue(go.await(5, TimeUnit.SECONDS))
                tx.executeWithoutResult { cases.remove(1, 10) }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            go.countDown()
            assertTrue(update.get(10, TimeUnit.SECONDS) in setOf("ok", "BASKET_ITEM_NOT_FOUND"))
            remove.get(10, TimeUnit.SECONDS)
            assertTrue(store.items(1).isEmpty())
        } finally { pool.shutdownNow() }
    }

    private fun assertError(code: ErrorCode, block: () -> Unit) {
        val error = assertThrows(BasketFailure::class.java, block)
        assertEquals(code, error.code)
    }

    private fun database(block: (JdbcTemplate, JdbcBasketStore, TransactionTemplate) -> Unit) {
        EmbeddedPostgres.start().use { postgres ->
            val dataSource = postgres.postgresDatabase
            val jdbc = JdbcTemplate(dataSource)
            jdbc.execute("CREATE TABLE participant(id bigint PRIMARY KEY, login text, status text, age integer)")
            jdbc.execute("CREATE TABLE product(id bigint PRIMARY KEY, name text, count integer, price real, prepayment_amount real, currency text, participant_id bigint, expiration_date timestamptz, status text, availability text, used boolean, originality text, created_at timestamptz)")
            jdbc.execute("CREATE TABLE product_basket(id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, participant_id bigint, product_id bigint, count integer NOT NULL, UNIQUE(participant_id, product_id))")
            jdbc.execute("CREATE TABLE category(id bigint PRIMARY KEY, name text, slug text)")
            jdbc.execute("CREATE TABLE product_category(product_id bigint, category_id bigint)")
            jdbc.execute("CREATE TABLE image(id bigint PRIMARY KEY, entity_id bigint, tag text, status text, created_at timestamptz)")
            jdbc.execute("CREATE TABLE seller_rating(seller_id bigint PRIMARY KEY, average_rating numeric, total_reviews integer)")
            block(jdbc, JdbcBasketStore(NamedParameterJdbcTemplate(dataSource)), TransactionTemplate(DataSourceTransactionManager(dataSource)))
        }
    }

    private fun seed(jdbc: JdbcTemplate) {
        jdbc.update("INSERT INTO participant VALUES (1,'minor','ACTIVE',17),(2,'adult','ACTIVE',25),(3,'blocked','BLOCKED',30)")
        jdbc.update("INSERT INTO category VALUES (1,'Adult','nsfw_adult')")
        for (id in 10L..14L) {
            jdbc.update(
                "INSERT INTO product VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)", id, "Product $id",
                if (id == 14L) 0 else 3, 100f, null, "RUB", 2L,
                Timestamp.from(Instant.parse("2030-01-01T00:00:00Z")),
                if (id == 13L) "BLOCKED" else "ACTIVE", if (id == 12L) "GIVEAWAY" else "PURCHASABLE",
                false, "original", Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")),
            )
        }
        jdbc.update("INSERT INTO product_category VALUES (11,1)")
    }
}
