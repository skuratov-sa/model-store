package com.model_store.modern.media.image.read

import com.model_store.modern.media.image.read.infrastructure.JdbcImageReadPort
import com.model_store.modern.media.storage.domain.StorageLocation
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

class JdbcImageReadPortTest {
    @Test
    fun `reads active images roles and order ownership from isolated postgres`() {
        EmbeddedPostgres.start().use { postgres ->
            val jdbc = JdbcTemplate(postgres.postgresDatabase)
            jdbc.execute("CREATE TYPE image_tag AS ENUM ('PARTICIPANT', 'PRODUCT', 'ORDER', 'SYSTEM')")
            jdbc.execute("CREATE TYPE image_status AS ENUM ('ACTIVE', 'TEMPORARY', 'DELETE')")
            jdbc.execute("CREATE TYPE participant_role AS ENUM ('USER', 'ADMIN')")
            jdbc.execute("CREATE TABLE image (id BIGINT PRIMARY KEY, filename TEXT, tag image_tag, status image_status, entity_id BIGINT, width INTEGER, height INTEGER, content_type TEXT)")
            jdbc.execute("CREATE TABLE participant (id BIGINT PRIMARY KEY, role participant_role)")
            jdbc.execute("CREATE TABLE \"order\" (id BIGINT PRIMARY KEY, customer_id BIGINT, seller_id BIGINT)")
            jdbc.update("INSERT INTO image VALUES (1, 'a.jpg', 'PRODUCT', 'ACTIVE', 10, 40, 30, 'image/jpeg')")
            jdbc.update("INSERT INTO image VALUES (2, 'proof.jpg', 'ORDER', 'ACTIVE', 20, NULL, NULL, NULL)")
            jdbc.update("INSERT INTO image VALUES (3, 'temp.jpg', 'PRODUCT', 'TEMPORARY', NULL, NULL, NULL, NULL)")
            jdbc.update("INSERT INTO image VALUES (4, NULL, 'PRODUCT', 'ACTIVE', NULL, NULL, NULL, NULL)")
            jdbc.update("INSERT INTO image VALUES (5, 'draft-proof.jpg', 'ORDER', 'TEMPORARY', 20, NULL, NULL, NULL)")
            jdbc.update("INSERT INTO image VALUES (6, 'deleted-proof.jpg', 'ORDER', 'DELETE', 20, NULL, NULL, NULL)")
            jdbc.update("INSERT INTO participant VALUES (4, 'USER'), (5, 'ADMIN')")
            jdbc.update("INSERT INTO \"order\" VALUES (20, 4, 6)")

            val port = JdbcImageReadPort(NamedParameterJdbcTemplate(postgres.postgresDatabase))
            val images = port.findActive(listOf(1, 2, 3, 4, 5, 6, 999))
            assertEquals(setOf(1L, 2L, 4L), images.map { it.id }.toSet())
            assertEquals(StorageLocation.ORDER, images.single { it.id == 2L }.location)
            assertEquals(40, images.single { it.id == 1L }.width)
            assertNull(images.single { it.id == 2L }.height)
            assertEquals("", images.single { it.id == 4L }.filename)
            assertFalse(port.viewerIsAdmin(4)!!)
            assertTrue(port.viewerIsAdmin(5)!!)
            assertNull(port.viewerIsAdmin(999))
            assertEquals(4L, port.findOrderParties(listOf(20, 999))[20]?.customerId)
            assertTrue(port.findOrderParties(emptyList()).isEmpty())
        }
    }
}
