package com.model_store.modern.catalog.product.search

import com.model_store.modern.catalog.product.search.domain.CatalogFlag
import com.model_store.modern.catalog.product.search.domain.ProductSearchCriteria
import com.model_store.modern.catalog.product.search.domain.SearchDateRange
import com.model_store.modern.catalog.product.search.domain.SearchPage
import com.model_store.modern.catalog.product.search.domain.SearchPriceRange
import com.model_store.modern.catalog.product.search.domain.SearchSort
import com.model_store.modern.catalog.product.search.infrastructure.JdbcProductSearchReadPort
import com.model_store.modern.catalog.product.search.api.ProductSearchDtoMapper
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDateTime

class JdbcProductSearchReadPortTest {
    @Test
    fun `empty and composite filters preserve visibility and bulk DTO data`() = database { jdbc, port ->
        seed(jdbc)
        val publicIds = port.findProducts(ProductSearchCriteria(), false).map { it.id }
        assertEquals(listOf(6L, 2L, 1L), publicIds)
        assertEquals(listOf(6L, 3L, 2L, 1L), port.findProducts(ProductSearchCriteria(), true).map { it.id })
        assertEquals(18, port.adultAge(10))
        assertEquals(null, port.adultAge(11))
        assertEquals(null, port.adultAge(12))

        val matched = port.findProducts(ProductSearchCriteria(
            name = "cat", categoryId = 100, participantId = 10,
            catalogFlags = listOf(CatalogFlag.PREORDER, CatalogFlag.USED),
            priceRange = SearchPriceRange(200, 300),
            dateRange = SearchDateRange(LocalDateTime.parse("2024-01-02T00:00:00"), LocalDateTime.parse("2024-01-06T00:00:00")),
        ), false)
        assertEquals(listOf(2L), matched.map { it.id })
        assertEquals(listOf(100L, 101L), matched.single().categories.map { it.id })
        assertEquals(201L, matched.single().imageId)
        assertEquals("seller", matched.single().sellerLogin)
        assertEquals(4.5f, matched.single().sellerRating)
        assertEquals(2, matched.single().totalReviews)
        assertEquals(null, ProductSearchDtoMapper.map(matched.single()).externalUrl)
        assertTrue(port.findProducts(ProductSearchCriteria(name = "' OR 1=1 --"), false).isEmpty())
        assertEquals(listOf(1L, 6L), port.findProducts(
            ProductSearchCriteria(catalogFlags = listOf(CatalogFlag.NON_PREORDER)), false,
        ).map { it.id }.sorted())
    }

    @Test
    fun `date and price cursors and page boundaries follow old SQL`() = database { jdbc, port ->
        seed(jdbc)
        val first = port.findProducts(ProductSearchCriteria(pageable = SearchPage(2, lastId = 0, sortBy = SearchSort.DATE_DESC)), false)
        assertEquals(listOf(6L, 2L), first.map { it.id })
        val second = port.findProducts(ProductSearchCriteria(pageable = SearchPage(2, first.last().createdAt, lastId = first.last().id, sortBy = SearchSort.DATE_DESC)), false)
        assertEquals(listOf(1L), second.map { it.id })
        assertTrue(port.findProducts(ProductSearchCriteria(pageable = SearchPage(0, sortBy = SearchSort.DATE_DESC)), false).isEmpty())
        assertEquals(listOf(6L, 2L), port.findProducts(ProductSearchCriteria(pageable = SearchPage(2)), false).map { it.id })
        assertEquals(listOf(1L, 2L), port.findProducts(ProductSearchCriteria(pageable = SearchPage(2, sortBy = SearchSort.PRICE_ASC)), false).map { it.id })
        assertEquals(listOf(6L, 2L), port.findProducts(ProductSearchCriteria(pageable = SearchPage(2, sortBy = SearchSort.PRICE_DESC)), false).map { it.id })
    }

    @Test
    fun `legacy ascending price cursor skips an equal-price row after the first item`() = database { jdbc, port ->
        seed(jdbc)
        jdbc.update("UPDATE product SET price=100 WHERE id=6")
        val first = port.findProducts(ProductSearchCriteria(pageable = SearchPage(1, sortBy = SearchSort.PRICE_ASC)), false).single()
        assertEquals(6L, first.id)
        val second = port.findProducts(ProductSearchCriteria(pageable = SearchPage(
            1, lastPrice = first.price, lastId = first.id, sortBy = SearchSort.PRICE_ASC,
        )), false).single()
        assertEquals(2L, second.id)
    }

    @Test
    fun `legacy ascending price tie repeats a row in both old and modern SQL`() = database { jdbc, port ->
        seed(jdbc)
        jdbc.update("UPDATE product SET price=100 WHERE id=6")
        val first = port.findProducts(ProductSearchCriteria(pageable = SearchPage(2, sortBy = SearchSort.PRICE_ASC)), false)
        assertEquals(listOf(6L, 1L), first.map { it.id })
        val cursor = first.last()
        val legacyNext = jdbc.queryForObject(
            """SELECT p.id FROM product p
               WHERE p.status='ACTIVE' AND p.availability<>'GIVEAWAY'
                 AND (p.count IS NULL OR p.count>0)
                 AND (p.price > ? OR (p.price = ? AND p.id > ?))
               ORDER BY p.price ASC, p.id DESC LIMIT 1""",
            Long::class.java, cursor.price, cursor.price, cursor.id,
        )
        assertEquals(6L, legacyNext)
        val modernNext = port.findProducts(ProductSearchCriteria(pageable = SearchPage(
            1, lastPrice = cursor.price, lastId = cursor.id, sortBy = SearchSort.PRICE_ASC,
        )), false)
        assertEquals(listOf(legacyNext), modernNext.map { it.id })
    }

    @Test
    fun `name suggestions include categories and exclude unavailable products`() = database { jdbc, port ->
        seed(jdbc)
        val names = port.findNames("Cat")
        assertTrue(names.contains("Cat preorder used"))
        assertTrue(names.contains("Cat figures"))
        assertFalse(names.contains("Cat empty"))
        assertFalse(names.contains("Cat expired"))
        assertFalse(names.contains("Cat giveaway"))
        assertTrue(port.findNames(null).isEmpty())
    }

    private fun database(block: (JdbcTemplate, JdbcProductSearchReadPort) -> Unit) {
        EmbeddedPostgres.start().use { postgres ->
            val jdbc = JdbcTemplate(postgres.postgresDatabase)
            jdbc.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm")
            jdbc.execute("CREATE TABLE participant (id bigint PRIMARY KEY, login text, status text, age integer)")
            jdbc.execute("CREATE TABLE category (id bigint PRIMARY KEY, name text, slug text)")
            jdbc.execute("CREATE TABLE product (id bigint PRIMARY KEY, name text, count integer, price real, prepayment_amount real, currency text, participant_id bigint, expiration_date timestamptz, status text, availability text, used boolean, originality text, created_at timestamptz)")
            jdbc.execute("CREATE TABLE product_category (product_id bigint, category_id bigint)")
            jdbc.execute("CREATE TABLE image (id bigint PRIMARY KEY, entity_id bigint, tag text, status text, created_at timestamptz)")
            jdbc.execute("CREATE TABLE seller_rating (seller_id bigint PRIMARY KEY, average_rating numeric, total_reviews integer)")
            block(jdbc, JdbcProductSearchReadPort(NamedParameterJdbcTemplate(postgres.postgresDatabase)))
        }
    }

    private fun seed(jdbc: JdbcTemplate) {
        jdbc.update("INSERT INTO participant VALUES (10,'seller','ACTIVE',18),(11,'blocked','BLOCKED',30),(12,'minor','ACTIVE',NULL)")
        jdbc.update("INSERT INTO category VALUES (100,'Cat figures','figures'),(101,'Other','other'),(102,'Adults','nsfw_adult')")
        jdbc.update("INSERT INTO seller_rating VALUES (10,4.50,2)")
        val data = listOf(
            Triple(1L, "Regular", "PURCHASABLE"), Triple(2L, "Cat preorder used", "PREORDER"),
            Triple(3L, "Adult", "PURCHASABLE"), Triple(4L, "Cat empty", "PURCHASABLE"),
            Triple(5L, "Cat expired", "PURCHASABLE"), Triple(6L, "Newest", "PURCHASABLE"),
            Triple(7L, "Cat giveaway", "GIVEAWAY"), Triple(8L, "Cat zero", "PURCHASABLE"),
        )
        data.forEach { (id, name, availability) ->
            jdbc.update(
                "INSERT INTO product VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id, name, if (id == 8L) 0 else 3, id * 100f, null, "RUB", 10,
                Timestamp.from(Instant.parse("2025-01-01T00:00:00Z")),
                "ACTIVE", availability, id == 2L,
                "Original", Timestamp.from(Instant.parse("2024-01-0${id}T00:00:00Z")),
            )
        }
        jdbc.update("UPDATE product SET status='TIME_EXPIRED' WHERE id=5")
        jdbc.update("UPDATE product SET count=0 WHERE id=4")
        jdbc.update("INSERT INTO product_category VALUES (2,100),(2,101),(3,102)")
        jdbc.update("INSERT INTO image VALUES (201,2,'PRODUCT','ACTIVE','2024-01-01'),(202,2,'PRODUCT','ACTIVE','2024-01-02')")
    }

}
