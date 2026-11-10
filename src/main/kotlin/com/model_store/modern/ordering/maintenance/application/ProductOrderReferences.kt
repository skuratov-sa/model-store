package com.model_store.modern.ordering.maintenance.application

/** These reads/locks participate in the caller's writable JPA transaction. Never write order/history IDs. */
interface ProductOrderReferences {
    fun hasOrders(productId: Long): Boolean
}

data class ImageOrderParties(val customerId: Long, val sellerId: Long, val status: String)

interface ImageOrderMaintenance {
    fun observeImageOrder(orderId: Long): ImageOrderParties?
    fun lockImageOrder(orderId: Long): ImageOrderParties?
    fun isImageReferenced(imageId: Long): Boolean
}

class OrderMaintenanceTransactionRequired : RuntimeException("Writable caller JPA transaction required")
