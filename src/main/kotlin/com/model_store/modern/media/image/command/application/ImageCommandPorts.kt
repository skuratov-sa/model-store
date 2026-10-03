package com.model_store.modern.media.image.command.application

import com.model_store.modern.media.image.command.domain.ImageToInsert
import com.model_store.modern.media.image.command.domain.IncomingImage
import com.model_store.modern.media.image.command.domain.PreparedImage
import com.model_store.modern.media.image.command.domain.UploadTarget
import com.model_store.modern.media.storage.domain.StorageLocation

interface ImageCommandRows {
    fun requireUploadAllowed(target: UploadTarget)

    /** A single DB transaction rechecks ownership and inserts every row or none. */
    fun insertBatch(target: UploadTarget, images: List<ImageToInsert>): List<Long>

    /** A single DB transaction checks every requested row and then marks all DELETE. */
    fun markDeleted(ids: List<Long>, tag: StorageLocation, actorId: Long)
}

interface ImageEncoder {
    fun prepare(image: IncomingImage): PreparedImage
}
