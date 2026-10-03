package com.model_store.modern.media.image.read

import com.model_store.modern.media.image.read.api.ImageReadController
import com.model_store.modern.media.image.read.application.ImageReadPort
import com.model_store.modern.media.image.read.application.ReadImages
import com.model_store.modern.media.image.read.domain.OrderParties
import com.model_store.modern.media.image.read.domain.ReadableImage
import com.model_store.modern.media.storage.application.ObjectStorage
import com.model_store.modern.media.storage.application.StoredObject
import com.model_store.modern.media.storage.domain.ImageVariantKey
import com.model_store.modern.media.storage.domain.StorageKey
import com.model_store.modern.media.storage.domain.StorageLocation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class ImageReadControllerTest {
    @Test
    fun `metadata has legacy field values and bucket URLs without order URLs`() {
        val rows = object : ImageReadPort {
            override fun findActive(ids: Collection<Long>) = listOf(
                ReadableImage(1, "avatar.jpg", StorageLocation.PARTICIPANT, 1, 800, 600, "image/jpeg"),
                ReadableImage(2, "proof.jpg", StorageLocation.ORDER, 3, null, null, null),
                ReadableImage(3, "name?token=secret.jpg", StorageLocation.PRODUCT, 10, null, null, "image/jpeg"),
            ).filter { it.id in ids }
            override fun viewerIsAdmin(viewerId: Long): Boolean? = null
            override fun findOrderParties(ids: Collection<Long>): Map<Long, OrderParties> = emptyMap()
        }
        val storage = object : ObjectStorage {
            override fun read(location: StorageLocation, key: StorageKey) = StoredObject(byteArrayOf(), "image/jpeg")
            override fun write(location: StorageLocation, key: ImageVariantKey, content: ByteArray, contentType: String) = Unit
            override fun delete(location: StorageLocation, key: ImageVariantKey) = Unit
        }
        val controller = ImageReadController(ReadImages(rows, storage), "https://cdn.example", "participant", "product", "system")
        val metadata = controller.metadata(listOf(2, 1, 1))
        assertEquals(listOf(1L, 1L), metadata.map { it.id })
        assertEquals("https://cdn.example/participant/original/avatar.jpg", metadata[0].originalUrl)
        assertEquals("https://cdn.example/participant/medium/avatar.jpg", metadata[0].mediumUrl)
        assertEquals("https://cdn.example/participant/thumbnail/avatar.jpg", metadata[0].thumbnailUrl)
        assertEquals(800, metadata[0].width)
        assertEquals(600, metadata[0].height)
        val encoded = controller.metadata(listOf(3)).single()
        assertEquals("https://cdn.example/product/original/name%3Ftoken=secret.jpg", encoded.originalUrl)

        MockMvcBuilders.standaloneSetup(controller).build()
            .perform(get("/images/metadata").param("ids", "2,1,1"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].id").value(1))
            .andExpect(jsonPath("$[0].originalUrl").value("https://cdn.example/participant/original/avatar.jpg"))
            .andExpect(jsonPath("$[1].id").value(1))
            .andExpect(jsonPath("$[2]").doesNotExist())
    }
}
