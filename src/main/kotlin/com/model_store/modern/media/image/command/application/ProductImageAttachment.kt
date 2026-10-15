package com.model_store.modern.media.image.command.application

/** ID-only boundary for product commands that already own a database transaction. */
interface ProductImageAttachment {
    fun claim(productId: Long, ownerId: Long, imageIds: List<Long>)
    fun markProductImagesDeleted(productId: Long)
}

enum class ProductImageAttachmentProblem {
    DUPLICATE_IDS, IMAGE_NOT_FOUND, ACCESS_DENIED, ALREADY_ATTACHED,
    TOO_MANY_IMAGES, CLAIM_CONFLICT, IMAGE_BUSY,
}

class ProductImageAttachmentFailure(val problem: ProductImageAttachmentProblem) : RuntimeException(problem.name)
