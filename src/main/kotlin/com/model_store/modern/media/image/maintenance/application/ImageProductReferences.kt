package com.model_store.modern.media.image.maintenance.application

/** Caller holds the product lock in the common writable JPA transaction. Includes every status. */
interface ImageProductReferences {
    fun hasImages(productId: Long): Boolean
}

class ImageReferencesTransactionRequired : RuntimeException("Writable caller JPA transaction required")
