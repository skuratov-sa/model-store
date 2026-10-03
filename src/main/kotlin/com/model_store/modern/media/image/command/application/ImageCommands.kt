package com.model_store.modern.media.image.command.application

import com.model_store.modern.media.image.command.domain.*
import com.model_store.modern.media.storage.application.ObjectStorage
import com.model_store.modern.media.storage.domain.ImageVariant
import com.model_store.modern.media.storage.domain.ImageVariantKey
import com.model_store.modern.media.storage.domain.StorageLocation
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service

@Service
@Profile("modern")
class ImageCommands(
    private val rows: ImageCommandRows,
    private val storage: ObjectStorage,
    private val encoder: ImageEncoder,
) {
    private val logger = LoggerFactory.getLogger(ImageCommands::class.java)

    fun upload(target: UploadTarget, incoming: List<IncomingImage>): List<Long> {
        if (incoming.isEmpty()) throw InvalidImage("Файлы не переданы")
        if (incoming.size > MAX_FILES || incoming.any { it.bytes.size > MAX_BYTES } ||
            incoming.sumOf { it.bytes.size.toLong() } > MAX_BYTES)
            throw ImageLimitExceeded("Размер запроса или число файлов превышает лимит")
        // The accepted participant profile port claims ACTIVE rows by ID and entity_id, not uploaded_by.
        // Never create an unowned ACTIVE participant row that another user could claim.
        val effectiveTarget = if (target.tag == StorageLocation.PARTICIPANT && target.entityId == null)
            target.copy(entityId = target.actorId) else target
        rows.requireUploadAllowed(effectiveTarget)
        val attempted = mutableListOf<Pair<StorageLocation, ImageVariantKey>>()
        val images = mutableListOf<ImageToInsert>()
        val filenames = mutableSetOf<String>()
        try {
            for (incomingImage in incoming) {
                val image = encoder.prepare(incomingImage)
                if (!filenames.add(image.filename)) throw InvalidImage("Повторяющееся имя изображения")
                for (variant in ImageVariant.entries) {
                    val key = ImageVariantKey.of(image.filename, variant)
                    // A failed PUT may still have created the object. Include its key in compensation.
                    attempted += effectiveTarget.tag to key
                    storage.write(effectiveTarget.tag, key, image.variants.getValue(variant), "image/jpeg")
                }
                images += ImageToInsert(image.filename, image.width, image.height)
            }
            return rows.insertBatch(effectiveTarget, images)
        } catch (failure: Exception) {
            val cleanupErrors = mutableListOf<Exception>()
            val remainingKeys = mutableListOf<String>()
            for ((location, key) in attempted.asReversed()) {
                var last: Exception? = null
                for (attempt in 1..3) {
                    try {
                        storage.delete(location, key)
                        last = null
                        break
                    } catch (ex: Exception) {
                        last = ex
                    }
                }
                last?.let {
                    cleanupErrors += it
                    remainingKeys += "${location.name}/${key.value}"
                }
            }
            if (cleanupErrors.isNotEmpty()) {
                logger.error("Image upload compensation failed for S3 objects {}", remainingKeys, failure)
                throw ImageCompensationFailed(failure, remainingKeys).also { compensation ->
                    cleanupErrors.forEach(compensation::addSuppressed)
                }
            }
            throw failure
        }
    }

    fun delete(ids: List<Long>, tag: StorageLocation, actorId: Long) {
        if (tag == StorageLocation.ORDER || tag == StorageLocation.SYSTEM)
            throw ImageCommandDenied("Изображения ${if (tag == StorageLocation.ORDER) "заказа" else "системы"} нельзя удалить")
        if (ids.isEmpty()) throw ImageCommandNotFound()
        if (ids.size != ids.toSet().size) throw InvalidImage("Повторяющиеся ID изображений")
        rows.markDeleted(ids, tag, actorId)
    }

    private companion object {
        const val MAX_FILES = 20
        const val MAX_BYTES = 10L * 1024 * 1024
    }
}
