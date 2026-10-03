package com.model_store.modern.media.image.command

import com.model_store.modern.media.image.command.application.ImageCommands
import com.model_store.modern.media.image.command.domain.*
import com.model_store.modern.media.image.command.infrastructure.JdbcImageCommandRows
import com.model_store.modern.media.image.command.infrastructure.JpegImageEncoder
import com.model_store.modern.identity.participant.domain.ParticipantImageNotFound
import com.model_store.modern.identity.participant.infrastructure.JdbcParticipantImagePort
import com.model_store.modern.media.storage.application.ObjectStorage
import com.model_store.modern.media.storage.application.StoredObject
import com.model_store.modern.media.storage.domain.ImageVariantKey
import com.model_store.modern.media.storage.domain.StorageKey
import com.model_store.modern.media.storage.domain.StorageLocation
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32
import javax.imageio.ImageIO

class ImageCommandsTest {
    @Test
    fun `upload writes legacy keys and one atomic DB batch then deletion marks owned active rows`() = database { jdbc, rows ->
        val storage = FakeStorage()
        val commands = ImageCommands(rows, storage, JpegImageEncoder())
        val png = png(60, 40)
        val ids = commands.upload(UploadTarget(StorageLocation.PARTICIPANT, 1, 1), listOf(IncomingImage("avatar.png", png)))
        assertEquals(1, ids.size)
        val saved = jdbc.queryForMap("SELECT filename, tag::text AS tag, status::text AS status, entity_id, uploaded_by, content_type, width, height FROM image WHERE id = ?", ids.single())
        val name = saved["filename"] as String
        assertTrue(name.matches(Regex("[0-9a-f-]{36}\\.jpg")))
        assertEquals("PARTICIPANT", saved["tag"])
        assertEquals("ACTIVE", saved["status"])
        assertEquals(1L, saved["entity_id"])
        assertEquals(1L, saved["uploaded_by"])
        assertEquals("image/jpeg", saved["content_type"])
        assertEquals(60, saved["width"])
        assertEquals(40, saved["height"])
        assertEquals(setOf("original/$name", "medium/$name", "thumbnail/$name"), storage.objects.keys)
        assertTrue(storage.objects.values.all { ImageIO.read(it.inputStream()) != null })
        commands.delete(ids, StorageLocation.PARTICIPANT, 1)
        assertEquals("DELETE", jdbc.queryForObject("SELECT status::text FROM image WHERE id = ?", String::class.java, ids.single()))
        assertEquals(3, storage.objects.size) // Deletion is soft; the existing cleanup scheduler removes S3 later.
    }

    @Test
    fun `partial S3 write and DB rejection leave no objects or image rows`() = database { jdbc, rows ->
        val storage = FakeStorage()
        val commands = ImageCommands(rows, storage, JpegImageEncoder())
        storage.failWriteAt = 2 // The failing PUT may already have stored its bytes.
        assertThrows(IllegalStateException::class.java) {
            commands.upload(UploadTarget(StorageLocation.PRODUCT, null, 1), listOf(IncomingImage("one.png", png(30, 30))))
        }
        assertTrue(storage.objects.isEmpty())
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM image", Int::class.java))

        storage.failWriteAt = null
        jdbc.execute("ALTER TABLE image ADD CONSTRAINT reject_large CHECK (width < 50)")
        assertThrows(Exception::class.java) {
            commands.upload(UploadTarget(StorageLocation.PRODUCT, null, 1),
                listOf(IncomingImage("small.png", png(30, 30)), IncomingImage("large.png", png(60, 40))))
        }
        assertTrue(storage.objects.isEmpty())
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM image", Int::class.java))
    }

    @Test
    fun `ownership status and ORDER deletion checks reject whole request`() = database { jdbc, rows ->
        val commands = ImageCommands(rows, FakeStorage(), JpegImageEncoder())
        val png = png(20, 20)
        assertThrows(ImageCommandDenied::class.java) {
            commands.upload(UploadTarget(StorageLocation.PARTICIPANT, 2, 1), listOf(IncomingImage("a.png", png)))
        }
        assertThrows(ImageCommandDenied::class.java) {
            commands.upload(UploadTarget(StorageLocation.PRODUCT, 11, 1), listOf(IncomingImage("a.png", png)))
        }
        assertThrows(ImageCommandDenied::class.java) {
            commands.upload(UploadTarget(StorageLocation.ORDER, 21, 1), listOf(IncomingImage("a.png", png)))
        }
        assertThrows(ImageCommandDenied::class.java) {
            commands.upload(UploadTarget(StorageLocation.SYSTEM, null, 1), listOf(IncomingImage("a.png", png)))
        }
        val owned = commands.upload(UploadTarget(StorageLocation.PARTICIPANT, 1, 1), listOf(IncomingImage("a.png", png))).single()
        val other = commands.upload(UploadTarget(StorageLocation.PARTICIPANT, 2, 2), listOf(IncomingImage("b.png", png))).single()
        assertThrows(ImageCommandDenied::class.java) { commands.delete(listOf(owned, other), StorageLocation.PARTICIPANT, 1) }
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM image WHERE status = 'ACTIVE'", Int::class.java))
        assertThrows(ImageCommandDenied::class.java) { commands.delete(listOf(owned), StorageLocation.ORDER, 1) }
        assertThrows(ImageCommandNotFound::class.java) { commands.delete(listOf(owned, 999), StorageLocation.PARTICIPANT, 1) }
        assertThrows(InvalidImage::class.java) { commands.delete(listOf(owned, owned), StorageLocation.PARTICIPANT, 1) }
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM image WHERE status = 'ACTIVE'", Int::class.java))
        jdbc.update("UPDATE participant SET status = 'BLOCKED' WHERE id = 1")
        assertThrows(ImageCommandDenied::class.java) { commands.delete(listOf(owned), StorageLocation.PARTICIPANT, 1) }
        assertEquals("ACTIVE", jdbc.queryForObject("SELECT status::text FROM image WHERE id = ?", String::class.java, owned))
    }

    @Test
    fun `unassigned participant upload is bound to uploader before profile image can be claimed`() = database { jdbc, rows ->
        val commands = ImageCommands(rows, FakeStorage(), JpegImageEncoder())
        val id = commands.upload(UploadTarget(StorageLocation.PARTICIPANT, null, 1),
            listOf(IncomingImage("avatar.png", png(20, 20)))).single()
        assertEquals(1L, jdbc.queryForObject("SELECT entity_id FROM image WHERE id = ?", Long::class.java, id))
        assertThrows(ParticipantImageNotFound::class.java) { JdbcParticipantImagePort(jdbc).replace(2, id) }
        JdbcParticipantImagePort(jdbc).replace(1, id)
        assertEquals(1L, jdbc.queryForObject("SELECT entity_id FROM image WHERE id = ?", Long::class.java, id))
    }

    @Test
    fun `admin agent and order party rules are checked for each entity`() = database { jdbc, rows ->
        val commands = ImageCommands(rows, FakeStorage(), JpegImageEncoder())
        val image = IncomingImage("photo.png", png(20, 20))
        assertThrows(ImageCommandDenied::class.java) {
            commands.upload(UploadTarget(StorageLocation.PRODUCT, 11, 3), listOf(image))
        }
        jdbc.update("UPDATE participant SET is_agent = true WHERE id = 2")
        val adminImage = commands.upload(UploadTarget(StorageLocation.PRODUCT, 11, 3), listOf(image)).single()
        assertEquals("TEMPORARY", jdbc.queryForObject("SELECT status::text FROM image WHERE id = ?", String::class.java, adminImage))
        val orderImage = commands.upload(UploadTarget(StorageLocation.ORDER, 20, 2), listOf(image)).single()
        assertEquals("TEMPORARY", jdbc.queryForObject("SELECT status::text FROM image WHERE id = ?", String::class.java, orderImage))
        assertThrows(ImageCommandDenied::class.java) {
            commands.upload(UploadTarget(StorageLocation.ORDER, 20, 3), listOf(image))
        }
        assertThrows(ImageCommandDenied::class.java) {
            commands.upload(UploadTarget(StorageLocation.ORDER, 21, 2), listOf(image))
        }
        jdbc.update("UPDATE image SET status = 'ACTIVE' WHERE id = ?", adminImage)
        assertThrows(ImageCommandDenied::class.java) { commands.delete(listOf(adminImage), StorageLocation.PRODUCT, 1) }
        commands.delete(listOf(adminImage), StorageLocation.PRODUCT, 3)
        assertEquals("DELETE", jdbc.queryForObject("SELECT status::text FROM image WHERE id = ?", String::class.java, adminImage))
    }

    @Test
    fun `invalid later file compensates earlier file and oversized data fails before storage`() = database { jdbc, rows ->
        val storage = FakeStorage()
        val commands = ImageCommands(rows, storage, JpegImageEncoder())
        assertThrows(InvalidImage::class.java) {
            commands.upload(UploadTarget(StorageLocation.PRODUCT, null, 1),
                listOf(IncomingImage("good.png", png(20, 20)), IncomingImage("../../secret.txt", byteArrayOf(1, 2, 3))))
        }
        assertTrue(storage.objects.isEmpty())
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM image", Int::class.java))
        assertThrows(ImageLimitExceeded::class.java) {
            commands.upload(UploadTarget(StorageLocation.PRODUCT, null, 1),
                listOf(IncomingImage("large.png", ByteArray(10 * 1024 * 1024 + 1))))
        }
        assertThrows(InvalidImage::class.java) {
            commands.upload(UploadTarget(StorageLocation.PRODUCT, null, 1),
                listOf(IncomingImage("compressed.png", bombHeader())))
        }
        assertTrue(storage.objects.isEmpty())
    }

    @Test
    fun `cleanup failure retains original cause and identifies remaining S3 keys`() = database { jdbc, rows ->
        val storage = FakeStorage().apply {
            failWriteAt = 1
            failDelete = true
        }
        val error = assertThrows(ImageCompensationFailed::class.java) {
            ImageCommands(rows, storage, JpegImageEncoder()).upload(
                UploadTarget(StorageLocation.PRODUCT, null, 1), listOf(IncomingImage("one.png", png(10, 10))))
        }
        assertTrue(error.cause is IllegalStateException)
        assertEquals(1, error.suppressed.size)
        assertEquals(1, error.remainingKeys.size)
        assertTrue(error.remainingKeys.single().startsWith("PRODUCT/original/"))
        assertEquals(1, storage.objects.size)
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM image", Int::class.java))
    }

    private fun database(block: (JdbcTemplate, JdbcImageCommandRows) -> Unit) {
        EmbeddedPostgres.start().use { postgres ->
            val dataSource = postgres.postgresDatabase
            val jdbc = JdbcTemplate(dataSource)
            jdbc.execute("CREATE TYPE image_tag AS ENUM ('PARTICIPANT', 'PRODUCT', 'ORDER', 'SYSTEM')")
            jdbc.execute("CREATE TYPE image_status AS ENUM ('ACTIVE', 'TEMPORARY', 'DELETE')")
            jdbc.execute("CREATE TYPE participant_role AS ENUM ('USER', 'ADMIN')")
            jdbc.execute("CREATE TYPE participant_status AS ENUM ('ACTIVE', 'BLOCKED')")
            jdbc.execute("CREATE TYPE product_status AS ENUM ('ACTIVE', 'DELETED')")
            jdbc.execute("CREATE TYPE product_availability AS ENUM ('PURCHASABLE', 'GIVEAWAY')")
            jdbc.execute("CREATE TYPE order_status AS ENUM ('BOOKED', 'COMPLETED', 'FAILED')")
            jdbc.execute("CREATE TABLE participant (id BIGINT PRIMARY KEY, role participant_role, status participant_status, is_agent BOOLEAN)")
            jdbc.execute("CREATE TABLE product (id BIGINT PRIMARY KEY, participant_id BIGINT, status product_status, availability product_availability)")
            jdbc.execute("CREATE TABLE \"order\" (id BIGINT PRIMARY KEY, customer_id BIGINT, seller_id BIGINT, status order_status)")
            jdbc.execute("CREATE TABLE image (id BIGSERIAL PRIMARY KEY, filename VARCHAR(500), tag image_tag NOT NULL, status image_status NOT NULL, entity_id BIGINT, uploaded_by BIGINT, content_type VARCHAR(100), width INTEGER, height INTEGER)")
            jdbc.execute("INSERT INTO participant VALUES (1, 'USER', 'ACTIVE', false), (2, 'USER', 'ACTIVE', false), (3, 'ADMIN', 'ACTIVE', false)")
            jdbc.execute("INSERT INTO product VALUES (10, 1, 'ACTIVE', 'PURCHASABLE'), (11, 2, 'ACTIVE', 'PURCHASABLE')")
            jdbc.execute("INSERT INTO \"order\" VALUES (20, 1, 2, 'BOOKED'), (21, 1, 2, 'COMPLETED')")
            block(jdbc, JdbcImageCommandRows(NamedParameterJdbcTemplate(dataSource), DataSourceTransactionManager(dataSource)))
        }
    }

    private fun png(width: Int, height: Int): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        graphics.color = Color.RED
        graphics.fillRect(0, 0, width, height)
        graphics.dispose()
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    private fun bombHeader(): ByteArray = png(1, 1).also { bytes ->
        ByteBuffer.wrap(bytes).putInt(16, 10_000).putInt(20, 10_000)
        val checksum = CRC32().apply { update(bytes, 12, 17) }.value.toInt()
        ByteBuffer.wrap(bytes).putInt(29, checksum)
    }

    private class FakeStorage : ObjectStorage {
        val objects = mutableMapOf<String, ByteArray>()
        var failWriteAt: Int? = null
        var failDelete = false
        private var writes = 0
        override fun read(location: StorageLocation, key: StorageKey): StoredObject = error("unused")
        override fun write(location: StorageLocation, key: ImageVariantKey, content: ByteArray, contentType: String) {
            objects[key.value] = content
            writes++
            if (writes == failWriteAt) throw IllegalStateException("S3 PUT failed after writing")
        }
        override fun delete(location: StorageLocation, key: ImageVariantKey) {
            if (failDelete) throw IllegalStateException("S3 DELETE failed")
            objects.remove(key.value)
        }
    }
}
