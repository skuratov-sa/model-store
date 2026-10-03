package com.model_store.modern.media.image.read.application

import com.model_store.modern.media.image.read.domain.ReadableImage
import com.model_store.modern.media.image.read.domain.visibleTo
import com.model_store.modern.media.storage.application.ObjectStorage
import com.model_store.modern.media.storage.domain.DefaultImageKey
import com.model_store.modern.media.storage.domain.ImageVariant
import com.model_store.modern.media.storage.domain.ImageVariantKey
import com.model_store.modern.media.storage.domain.StorageLocation
import org.springframework.stereotype.Service

data class ImageContent(val fileName: String, val contentType: String, val imageData: ByteArray)

@Service
class ReadImages(private val rows: ImageReadPort, private val storage: ObjectStorage) {
    fun byIds(ids: List<Long>, viewerId: Long?): List<ImageContent> {
        if (ids.isEmpty()) return emptyList()
        val active = rows.findActive(ids.distinct()).associateBy { it.id }
        val orderIds = active.values.asSequence()
            .filter { it.location == StorageLocation.ORDER }
            .mapNotNull { it.entityId }.toSet()
        val viewerIsAdmin = if (viewerId != null && orderIds.isNotEmpty()) rows.viewerIsAdmin(viewerId) else null
        val orders = if (viewerIsAdmin != null && orderIds.isNotEmpty()) rows.findOrderParties(orderIds) else emptyMap()
        return ids.mapNotNull { active[it] }
            .filter { image ->
                image.location != StorageLocation.ORDER ||
                    (viewerId != null && viewerIsAdmin != null && image.entityId?.let { orderId ->
                        orders[orderId]?.visibleTo(viewerId, viewerIsAdmin)
                    } == true)
            }
            .map { image ->
                // Legacy substitutes the system image when reading an individual object fails.
                try {
                    val key = ImageVariantKey.of(image.filename, ImageVariant.ORIGINAL)
                    val objectData = storage.read(image.location, key)
                    ImageContent(key.value, objectData.contentType, objectData.content)
                } catch (_: Exception) {
                    default()
                }
            }
    }

    fun default(): ImageContent {
        val objectData = storage.read(StorageLocation.SYSTEM, DefaultImageKey)
        return ImageContent(DefaultImageKey.value, objectData.contentType, objectData.content)
    }

    fun activeImages(ids: List<Long>): List<ReadableImage> =
        if (ids.isEmpty()) emptyList() else rows.findActive(ids.distinct())
            .filter { it.location != StorageLocation.ORDER }
            .filter { runCatching { ImageVariantKey.of(it.filename, ImageVariant.ORIGINAL) }.isSuccess }
            .associateBy { it.id }
            .let { byId -> ids.mapNotNull(byId::get) }
}
