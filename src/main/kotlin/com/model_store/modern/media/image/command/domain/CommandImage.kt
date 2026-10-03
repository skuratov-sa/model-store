package com.model_store.modern.media.image.command.domain

import com.model_store.modern.media.storage.domain.ImageVariant
import com.model_store.modern.media.storage.domain.StorageLocation

data class IncomingImage(val filename: String, val bytes: ByteArray)

data class PreparedImage(
    val filename: String,
    val width: Int,
    val height: Int,
    val variants: Map<ImageVariant, ByteArray>,
) {
    init {
        require(variants.keys == ImageVariant.entries.toSet()) { "All image variants are required" }
    }
}

data class ImageToInsert(val filename: String, val width: Int, val height: Int)

data class UploadTarget(val tag: StorageLocation, val entityId: Long?, val actorId: Long)

class ImageCommandDenied(message: String = "Доступ запрещён") : RuntimeException(message)
class ImageCommandNotFound(message: String = "Не удалось найти изображение") : RuntimeException(message)
class InvalidImage(message: String) : RuntimeException(message)
class ImageLimitExceeded(message: String) : RuntimeException(message)

/** The original failure is retained; failed compensation is attached as suppressed exceptions. */
class ImageCompensationFailed(cause: Throwable, val remainingKeys: List<String>) : RuntimeException(
    "Не удалось удалить все объекты после ошибки загрузки", cause,
)
