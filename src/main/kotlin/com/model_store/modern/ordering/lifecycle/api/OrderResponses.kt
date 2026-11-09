package com.model_store.modern.ordering.lifecycle.api

import com.fasterxml.jackson.annotation.JsonFormat
import com.model_store.modern.ordering.lifecycle.application.*
import java.time.Instant

data class CloseOrderBody(val closureReason: String? = null, val comment: String? = null)
data class OrderHistoryResponse(val status: String, val comment: String?,
    @get:JsonFormat(pattern = "dd.MM.yyyy HH:mm:ss", timezone = "Europe/Moscow") val changedAt: Instant?)
data class OrderUserResponse(val id: Long?, val imageId: Long?, val login: String?, val phoneNumber: String?, val mail: String?)
data class OrderCategoryResponse(val id: Long, val name: String?)
data class OrderTransferResponse(val transferId: Long?, val addressId: Long?, val imageId: Long?, val address: String?,
    val price: Float?, val currency: String?)
data class OrderProductResponse(val id: Long, val name: String?, val count: Int?, val price: Float?,
    val prepaymentAmount: Float?, val currency: String?, val categories: List<OrderCategoryResponse>?,
    val imageId: Long?, val sellerId: Long?, val expirationDate: Instant?, val status: String?, val availability: String?,
    val used: Boolean?, val externalUrl: String?, val sellerLogin: String?, val sellerRating: Float?,
    val totalReviews: Int?, val createdAt: Instant?)

/** Serialization belongs entirely to API; application read models carry no HTTP contract. */
data class OrderResponse(val orderId: Long, val actualStatus: String, val totalPrice: Float?, val prepaymentAmount: Float?,
    val sellerId: Long?, val sellerLogin: String? = null, val createdAt: String?, val userInfo: OrderUserResponse,
    val product: OrderProductResponse, val transfer: OrderTransferResponse, val images: List<Long>, val paymentProofImageId: Long?,
    val deliveryUrl: String?, val histories: List<OrderHistoryResponse>) {
    companion object {
        fun from(view: OrderView): OrderResponse {
            val user = view.userInfo
            val product = view.product
            val transfer = view.transfer
            return OrderResponse(view.orderId, view.actualStatus, view.totalPrice, view.prepaymentAmount, view.sellerId,
                createdAt = view.createdAt,
                userInfo = OrderUserResponse(user.id, user.imageId, user.login, user.phoneNumber, user.mail),
                product = OrderProductResponse(product.id, product.name, product.count, product.price, product.prepaymentAmount,
                    product.currency, product.categories?.map { OrderCategoryResponse(it.id, it.name) }, product.imageId,
                    product.sellerId, product.expirationDate, product.status, product.availability, product.used,
                    product.externalUrl, product.sellerLogin, product.sellerRating, product.totalReviews, product.createdAt),
                transfer = OrderTransferResponse(transfer.transferId, transfer.addressId, transfer.imageId, transfer.address,
                    transfer.price, transfer.currency), images = view.images, paymentProofImageId = view.paymentProofImageId,
                deliveryUrl = view.deliveryUrl,
                histories = view.histories.map { OrderHistoryResponse(it.status, it.comment, it.changedAt) })
        }
    }
}
