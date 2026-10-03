package com.model_store.modern.media.image.read.domain

import com.model_store.modern.media.storage.domain.StorageLocation

/** A snapshot of an ACTIVE row in the existing image table. */
data class ReadableImage(
    val id: Long,
    val filename: String,
    val location: StorageLocation,
    val entityId: Long?,
    val width: Int?,
    val height: Int?,
    val contentType: String?,
)

data class OrderParties(val customerId: Long, val sellerId: Long)

fun OrderParties.visibleTo(viewerId: Long, isAdmin: Boolean): Boolean =
    isAdmin || customerId == viewerId || sellerId == viewerId
