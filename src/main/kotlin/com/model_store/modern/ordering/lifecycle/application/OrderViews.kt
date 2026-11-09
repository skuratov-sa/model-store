package com.model_store.modern.ordering.lifecycle.application

import java.time.Instant

data class OrderView(val orderId: Long, val actualStatus: String, val totalPrice: Float?, val prepaymentAmount: Float?,
                     val sellerId: Long?, val createdAt: String?, val userInfo: UserView, val product: ProductView,
                     val transfer: TransferView, val images: List<Long>, val paymentProofImageId: Long?,
                     val deliveryUrl: String?, val histories: List<HistoryView>)
data class UserView(val id: Long?, val imageId: Long? = null, val login: String? = null,
                    val phoneNumber: String? = null, val mail: String? = null)
data class CategoryView(val id: Long, val name: String?)
data class ProductView(val id: Long, val name: String?, val count: Int?, val price: Float?,
                       val prepaymentAmount: Float? = null, val currency: String?, val categories: List<CategoryView>? = null,
                       val imageId: Long? = null, val sellerId: Long?, val expirationDate: Instant? = null,
                       val status: String?, val availability: String?, val used: Boolean? = null,
                       val externalUrl: String? = null, val sellerLogin: String? = null, val sellerRating: Float? = null,
                       val totalReviews: Int? = null, val createdAt: Instant? = null)
data class TransferView(val transferId: Long?, val addressId: Long?, val imageId: Long? = null,
                        val address: String? = null, val price: Float? = null, val currency: String? = null)
data class HistoryView(val status: String, val comment: String?, val changedAt: Instant?)
