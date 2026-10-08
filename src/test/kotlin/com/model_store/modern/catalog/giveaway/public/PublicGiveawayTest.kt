package com.model_store.modern.catalog.giveaway.`public`

import com.fasterxml.jackson.databind.ObjectMapper
import com.model_store.modern.catalog.giveaway.`public`.api.PublicGiveawayController
import com.model_store.modern.catalog.giveaway.`public`.application.PublicGiveawayQuery
import com.model_store.modern.catalog.giveaway.`public`.application.PublicGiveawayReadPort
import com.model_store.modern.catalog.giveaway.`public`.domain.PublicGiveaway
import com.model_store.modern.catalog.giveaway.`public`.infrastructure.JdbcPublicGiveawayReadPort
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class PublicGiveawayTest {
    @Test
    fun `period, enabled flag and stored status control public visibility`() = database { jdbc, read ->
        seed(jdbc)
        assertEquals(2L, read.active()?.productId) // running before scheduled
        assertEquals("ACTIVE", read.byProductId(2)!!.status)
        assertEquals("AWAITING_GIVEAWAY", read.byProductId(3)!!.status)
        assertNull(read.byProductId(3)!!.description)
        assertEquals(listOf(20L, 21L), read.byProductId(2)!!.imageIds)
        assertNull(read.byProductId(1)) // ordinary product
        assertNull(read.byProductId(4)) // ended
        assertNull(read.byProductId(5)) // disabled
        assertNull(read.byProductId(6)) // blocked
        assertNull(read.byProductId(999))
        assertNull(read.byProductId(Long.MIN_VALUE))
        jdbc.update("UPDATE product SET giveaway_enabled = false WHERE id = 2")
        assertEquals(3L, read.active()?.productId) // future period is visible
        jdbc.update("UPDATE product SET giveaway_start_at = now() WHERE id = 3")
        assertEquals("ACTIVE", read.byProductId(3)!!.status)
        jdbc.update("UPDATE product SET giveaway_end_at = now() WHERE id = 3")
        assertNull(read.byProductId(3)) // end boundary is exclusive
        assertNull(read.active())
    }

    @Test
    fun `controller preserves legacy body header and 404`() {
        val giveaway = PublicGiveaway(42, "Prize", "Description", listOf(10),
            "https://t.me/prize", java.time.Instant.parse("2026-01-01T00:00:00Z"),
            java.time.Instant.parse("2026-02-01T00:00:00Z"), 2, "Rules", "Home", "ACTIVE")
        val read = object : PublicGiveawayReadPort {
            override fun active() = giveaway
            override fun byProductId(productId: Long) = giveaway.takeIf { productId == 42L }
        }
        val mvc = MockMvcBuilders.standaloneSetup(PublicGiveawayController(PublicGiveawayQuery(read))).build()
        val response = mvc.perform(get("/giveaways/active"))
            .andExpect(status().isOk)
            .andExpect(header().string("X-Robots-Tag", "noindex, nofollow"))
            .andExpect(jsonPath("$.productId").value(42))
            .andExpect(jsonPath("$.startAt").value("2026-01-01T00:00:00Z"))
            .andExpect(jsonPath("$.status").value("ACTIVE"))
            .andReturn().response
        assertEquals(setOf("productId", "name", "description", "imageIds", "telegramUrl",
            "startAt", "endAt", "winnersCount", "rules", "homeText", "status"),
            ObjectMapper().readTree(response.contentAsString).fieldNames().asSequence().toSet())
        mvc.perform(get("/giveaways/products/42")).andExpect(status().isOk)
        mvc.perform(get("/giveaways/products/43")).andExpect(status().isNotFound)
        val emptyMvc = MockMvcBuilders.standaloneSetup(PublicGiveawayController(PublicGiveawayQuery(
            object : PublicGiveawayReadPort {
                override fun active(): PublicGiveaway? = null
                override fun byProductId(productId: Long): PublicGiveaway? = null
            }))).build()
        emptyMvc.perform(get("/giveaways/active")).andExpect(status().isNotFound)
    }

    private fun database(block: (JdbcTemplate, JdbcPublicGiveawayReadPort) -> Unit) {
        EmbeddedPostgres.start().use { postgres ->
            val jdbc = JdbcTemplate(postgres.postgresDatabase)
            jdbc.execute("""CREATE TABLE product(id bigint PRIMARY KEY, name text, description text,
                availability text, status text, giveaway_enabled boolean, giveaway_telegram_url text,
                giveaway_start_at timestamptz, giveaway_end_at timestamptz, giveaway_winners_count integer,
                giveaway_rules text, giveaway_home_text text)""")
            jdbc.execute("CREATE TABLE image(id bigint PRIMARY KEY, entity_id bigint, tag text, status text, created_at timestamptz)")
            block(jdbc, JdbcPublicGiveawayReadPort(NamedParameterJdbcTemplate(postgres.postgresDatabase)))
        }
    }

    private fun seed(jdbc: JdbcTemplate) {
        jdbc.execute("""INSERT INTO product VALUES
            (1,'Ordinary','Description','PURCHASABLE','ACTIVE',false,NULL,NULL,NULL,NULL,NULL,NULL),
            (2,'Running','Description','GIVEAWAY','AWAITING_GIVEAWAY',true,'https://t.me/2',now()-interval '1 day',now()+interval '1 day',2,'Rules','Home'),
            (3,'Scheduled',NULL,'GIVEAWAY','ACTIVE',true,'https://t.me/3',now()+interval '1 day',now()+interval '2 days',1,'Rules','Home'),
            (4,'Ended','Description','GIVEAWAY','ACTIVE',true,'https://t.me/4',now()-interval '2 days',now()-interval '1 day',1,'Rules','Home'),
            (5,'Disabled','Description','GIVEAWAY','ACTIVE',false,'https://t.me/5',now()-interval '1 day',now()+interval '1 day',1,'Rules','Home'),
            (6,'Blocked','Description','GIVEAWAY','BLOCKED',true,'https://t.me/6',now()-interval '1 day',now()+interval '1 day',1,'Rules','Home')""")
        jdbc.execute("""INSERT INTO image VALUES
            (20,2,'PRODUCT','ACTIVE',now()-interval '2 days'),
            (21,2,'PRODUCT','ACTIVE',now()-interval '1 day'),
            (22,2,'PRODUCT','TEMPORARY',now()),(23,2,'PARTICIPANT','ACTIVE',now())""")
    }
}
