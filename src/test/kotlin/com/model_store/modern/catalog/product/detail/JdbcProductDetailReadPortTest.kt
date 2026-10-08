package com.model_store.modern.catalog.product.detail

import com.model_store.modern.catalog.product.detail.domain.DetailSort
import com.model_store.modern.catalog.product.detail.domain.DetailFlag
import com.model_store.modern.catalog.product.detail.domain.MyProductsFilter
import com.model_store.modern.catalog.product.detail.infrastructure.JdbcProductDetailReadPort
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

class JdbcProductDetailReadPortTest {
    @Test
    fun `public card returns legacy fields and excludes unavailable and giveaway rows`() = database { jdbc, read ->
        seed(jdbc)
        val card = read.publicCard(1)!!
        assertEquals("Ordinary", card.name)
        assertEquals(listOf(100L), card.categories.map { it.id })
        assertEquals(listOf(20L, 21L), card.imageIds)
        assertEquals(1, card.reviews.size)
        assertEquals("Reviewer", card.reviews[0].reviewerName)
        assertEquals(30L, card.reviews[0].imageId)
        assertEquals(4.5f, card.sellerRating)
        assertNull(read.publicCard(2)) // giveaway
        assertNull(read.publicCard(3)) // blocked
        assertNull(read.publicCard(999))
        assertNotNull(read.publicCard(4)) // adult category has no check on legacy detail endpoint
    }

    @Test
    fun `my products are owner scoped with legacy status and giveaway rules`() = database { jdbc, read ->
        seed(jdbc)
        val own = read.myProducts(10, false, MyProductsFilter())
        assertEquals(listOf(4L, 3L, 1L), own.map { it.id })
        assertEquals("BLOCKED", own.single { it.id == 3L }.status)
        assertEquals("Adult", own.first().name)
        assertFalse(own.any { it.sellerId == 11L })
        val admin = read.myProducts(10, true, MyProductsFilter())
        assertEquals(listOf(4L, 3L, 2L, 1L), admin.map { it.id })
        assertEquals("ACTIVE", admin.single { it.id == 2L }.status)
        assertEquals(listOf(5L), read.myProducts(11, false, MyProductsFilter()).map { it.id })
    }

    @Test
    fun `my product filters and price cursor follow legacy`() = database { jdbc, read ->
        seed(jdbc)
        assertEquals(listOf(1L), read.myProducts(10, false, MyProductsFilter(categoryId = 100)).map { it.id })
        assertEquals(listOf(4L), read.myProducts(10, false, MyProductsFilter(used = true)).map { it.id })
        assertEquals(listOf(3L, 1L, 4L), read.myProducts(10, false,
            MyProductsFilter(sort = DetailSort.PRICE_ASC)).map { it.id })
        assertEquals(listOf(4L), read.myProducts(10, false,
            MyProductsFilter(sort = DetailSort.PRICE_ASC, lastPrice = 20f, lastId = 1L)).map { it.id })
        assertEquals(listOf(4L), read.myProducts(10, false,
            MyProductsFilter(flags = listOf(DetailFlag.USED))).map { it.id })
        assertEquals(listOf(4L, 3L, 1L), read.myProducts(10, false,
            MyProductsFilter(flags = listOf(DetailFlag.PREORDER, DetailFlag.NON_PREORDER))).map { it.id })
        assertEquals(emptyList<Long>(), read.myProducts(10, false,
            MyProductsFilter(flags = listOf(DetailFlag.PREORDER))).map { it.id })
        assertEquals(emptyList<Long>(), read.myProducts(10, false, MyProductsFilter(size = 0)).map { it.id })
        assertEquals(listOf(1L), read.myProducts(10, false,
            MyProductsFilter(name = "Figures", categoryId = 100)).map { it.id })
        assertEquals(emptyList<Long>(), read.myProducts(10, false,
            MyProductsFilter(name = "Figures", categoryId = 101)).map { it.id })
    }

    @Test
    fun `deleted and expired products respect separate public and owner visibility`() = database { jdbc, read ->
        seed(jdbc)
        jdbc.execute("""INSERT INTO product VALUES
            (6,'Expired',NULL,50,NULL,0,'RUB',NULL,10,'TIME_EXPIRED','PURCHASABLE',false,now(),now() + interval '1 day',false,NULL,NULL),
            (7,'Deleted',NULL,60,NULL,1,'RUB',NULL,10,'DELETED','PURCHASABLE',false,now(),now() + interval '2 days',false,NULL,NULL),
            (8,'Waiting',NULL,0,NULL,NULL,'RUB',NULL,10,'AWAITING_GIVEAWAY','GIVEAWAY',false,now(),now() + interval '3 days',true,now() + interval '1 day',now() + interval '2 days')""")
        assertNull(read.publicCard(6))
        assertNull(read.publicCard(7))
        assertNull(read.publicCard(8))
        val mine = read.myProducts(10, false, MyProductsFilter()).map { it.id }
        assertTrue(6L in mine)
        assertFalse(7L in mine)
        assertFalse(8L in mine)
        assertEquals("AWAITING_GIVEAWAY", read.myProducts(10, true, MyProductsFilter())
            .single { it.id == 8L }.status)
    }

    private fun database(block: (JdbcTemplate, JdbcProductDetailReadPort) -> Unit) {
        EmbeddedPostgres.start().use { postgres ->
            val jdbc = JdbcTemplate(postgres.postgresDatabase)
            jdbc.execute("CREATE TABLE participant(id bigint PRIMARY KEY, login text, full_name text)")
            jdbc.execute("CREATE TABLE seller_rating(seller_id bigint PRIMARY KEY, average_rating numeric, total_reviews integer)")
            jdbc.execute("CREATE TABLE category(id bigint PRIMARY KEY, name text)")
            jdbc.execute("CREATE TABLE product_category(id bigserial PRIMARY KEY, product_id bigint, category_id bigint)")
            jdbc.execute("""CREATE TABLE product(id bigint PRIMARY KEY, name text, description text, price real,
                prepayment_amount real, count integer, currency text, originality text, participant_id bigint,
                status text, availability text, used boolean, expiration_date timestamptz, created_at timestamptz,
                giveaway_enabled boolean, giveaway_start_at timestamptz, giveaway_end_at timestamptz)""")
            jdbc.execute("CREATE TABLE image(id bigint PRIMARY KEY, entity_id bigint, tag text, status text, created_at timestamptz)")
            jdbc.execute("CREATE TABLE review(id bigint PRIMARY KEY, product_id bigint, reviewer_id bigint, rating integer, comment text, created_at timestamp)")
            block(jdbc, JdbcProductDetailReadPort(NamedParameterJdbcTemplate(postgres.postgresDatabase)))
        }
    }

    private fun seed(jdbc: JdbcTemplate) {
        jdbc.execute("INSERT INTO participant VALUES (10,'seller','Seller'),(11,'other','Other'),(12,'reviewer','Reviewer')")
        jdbc.execute("INSERT INTO seller_rating VALUES (10,4.5,2)")
        jdbc.execute("INSERT INTO category VALUES (100,'Figures'),(101,'Adult')")
        jdbc.execute("""INSERT INTO product VALUES
            (1,'Ordinary','Description',20,NULL,2,'RUB',NULL,10,'ACTIVE','PURCHASABLE',false,now(),now() - interval '4 days',false,NULL,NULL),
            (2,'Giveaway',NULL,0,NULL,NULL,'RUB',NULL,10,'ACTIVE','GIVEAWAY',false,now(),now() - interval '3 days',true,now() - interval '1 day',now() + interval '1 day'),
            (3,'Blocked',NULL,10,NULL,0,'RUB',NULL,10,'BLOCKED','PURCHASABLE',false,now(),now() - interval '2 days',false,NULL,NULL),
            (4,'Adult',NULL,30,NULL,1,'RUB',NULL,10,'ACTIVE','PURCHASABLE',true,now(),now() - interval '1 day',false,NULL,NULL),
            (5,'Foreign',NULL,40,NULL,1,'RUB',NULL,11,'ACTIVE','PURCHASABLE',false,now(),now(),false,NULL,NULL)""")
        jdbc.execute("INSERT INTO product_category(product_id,category_id) VALUES (1,100),(4,101)")
        jdbc.execute("""INSERT INTO image VALUES
            (20,1,'PRODUCT','ACTIVE',now() - interval '2 days'),
            (21,1,'PRODUCT','ACTIVE',now() - interval '1 day'),
            (22,1,'PRODUCT','TEMPORARY',now()),(30,12,'PARTICIPANT','ACTIVE',now())""")
        jdbc.execute("INSERT INTO review VALUES (40,1,12,5,'Great',now())")
    }
}
