package com.model_store.modern.media.image.read

import com.model_store.modern.media.image.read.application.ImageReadPort
import com.model_store.modern.media.image.read.application.ReadImages
import com.model_store.modern.media.image.read.domain.OrderParties
import com.model_store.modern.media.image.read.domain.ReadableImage
import com.model_store.modern.media.storage.application.ObjectStorage
import com.model_store.modern.media.storage.application.StoredObject
import com.model_store.modern.media.storage.domain.DefaultImageKey
import com.model_store.modern.media.storage.domain.ImageVariantKey
import com.model_store.modern.media.storage.domain.StorageKey
import com.model_store.modern.media.storage.domain.StorageLocation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReadImagesTest {
    private val public = ReadableImage(1, "public.jpg", StorageLocation.PRODUCT, 100, 120, 80, "image/jpeg")
    private val private = ReadableImage(2, "proof.jpg", StorageLocation.ORDER, 200, null, null, "image/jpeg")
    private val repo = FakeRows(listOf(public, private))
    private val storage = FakeStorage()
    private val reader = ReadImages(repo, storage)

    @Test
    fun `keeps request order duplicates and skips missing rows`() {
        val result = reader.byIds(listOf(1, 999, 1), null)
        assertEquals(listOf("original/public.jpg", "original/public.jpg"), result.map { it.fileName })
        assertEquals(listOf("original/public.jpg", "original/public.jpg"), storage.readKeys)
    }

    @Test
    fun `order image is hidden before S3 for guest and outsider`() {
        assertTrue(reader.byIds(listOf(2), null).isEmpty())
        assertTrue(reader.byIds(listOf(2), 9).isEmpty())
        assertTrue(storage.readKeys.isEmpty())
    }

    @Test
    fun `buyer seller and admin can read order image`() {
        assertEquals(1, reader.byIds(listOf(2), 3).size)
        assertEquals(1, reader.byIds(listOf(2), 4).size)
        assertEquals(1, reader.byIds(listOf(2), 5).size)
        assertEquals(3, storage.readKeys.size)
    }

    @Test
    fun `deleted viewer or order cannot read proof`() {
        repo.viewers.remove(3)
        assertTrue(reader.byIds(listOf(2), 3).isEmpty())
        repo.orders.clear()
        assertTrue(reader.byIds(listOf(2), 5).isEmpty())
        assertTrue(storage.readKeys.isEmpty())
    }

    @Test
    fun `metadata excludes order image even for admin and preserves order`() {
        assertEquals(listOf(1L, 1L), reader.activeImages(listOf(2, 1, 1)).map { it.id })
        assertTrue(storage.readKeys.isEmpty())
    }

    @Test
    fun `unsafe filename cannot create public metadata URL`() {
        val unsafeRows = FakeRows(listOf(public.copy(id = 7, filename = "../../../order/proof.jpg")))
        assertTrue(ReadImages(unsafeRows, storage).activeImages(listOf(7)).isEmpty())
        assertEquals("not_found.jpeg", ReadImages(unsafeRows, storage).byIds(listOf(7), null).single().fileName)
    }

    @Test
    fun `failed object read falls back to default image`() {
        storage.failOriginal = true
        val result = reader.byIds(listOf(1), null).single()
        assertEquals("not_found.jpeg", result.fileName)
        assertEquals(listOf("original/public.jpg", "not_found.jpeg"), storage.readKeys)
    }

    private class FakeRows(private val images: List<ReadableImage>) : ImageReadPort {
        val viewers = mutableMapOf(3L to false, 4L to false, 5L to true, 9L to false)
        val orders = mutableMapOf(200L to OrderParties(3, 4))
        override fun findActive(ids: Collection<Long>) = images.filter { it.id in ids }
        override fun viewerIsAdmin(viewerId: Long) = viewers[viewerId]
        override fun findOrderParties(ids: Collection<Long>) = orders.filterKeys { it in ids }
    }

    private class FakeStorage : ObjectStorage {
        val readKeys = mutableListOf<String>()
        var failOriginal = false
        override fun read(location: StorageLocation, key: StorageKey): StoredObject {
            readKeys.add(key.value)
            if (failOriginal && key !== DefaultImageKey) error("S3 read failed")
            return StoredObject(byteArrayOf(1), "image/jpeg")
        }
        override fun write(location: StorageLocation, key: ImageVariantKey, content: ByteArray, contentType: String) = Unit
        override fun delete(location: StorageLocation, key: ImageVariantKey) = Unit
    }
}
