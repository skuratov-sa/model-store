package com.model_store.modern.catalog.product.detail.domain

import java.time.Instant
import java.time.LocalDateTime

data class DetailCategory(val id: Long, val name: String?)
data class DetailReview(val id: Long, val rating: Int, val comment: String?, val reviewerName: String, val imageId: Long?, val createdAt: Instant?)

data class ProductCard(
    val id: Long, val name: String?, val description: String?, val price: Float,
    val prepaymentAmount: Float?, val count: Int?, val currency: String, val originality: String?,
    val participantId: Long, val status: String, val availability: String, val used: Boolean?,
    val categories: List<DetailCategory>, val imageIds: List<Long>, val reviews: List<DetailReview>,
    val sellerLogin: String, val sellerRating: Float, val totalReviews: Int,
)

data class MyProduct(
    val id: Long, val name: String?, val count: Int?, val price: Float, val prepaymentAmount: Float?,
    val currency: String, val categories: List<DetailCategory>, val imageId: Long?, val sellerId: Long,
    val expirationDate: Instant?, val status: String, val availability: String, val used: Boolean?,
    val sellerLogin: String, val sellerRating: Float, val totalReviews: Int, val createdAt: Instant?,
)

enum class DetailSort { DATE_DESC, PRICE_ASC, PRICE_DESC }
enum class DetailFlag { ALL, PREORDER, NON_PREORDER, USED }
data class MyProductsFilter(
    val name: String? = null, val categoryId: Long? = null, val flags: List<DetailFlag>? = null,
    val used: Boolean? = null, val originality: String? = null, val minPrice: Int? = null,
    val maxPrice: Int? = null, val from: LocalDateTime? = null, val to: LocalDateTime? = null,
    val size: Int = 50, val lastCreatedAt: Instant? = null, val lastPrice: Float? = null,
    val lastId: Long = 0, val sort: DetailSort = DetailSort.DATE_DESC,
) {
    val preorder: Boolean? get() {
        val pre = flags?.contains(DetailFlag.PREORDER) == true
        val non = flags?.contains(DetailFlag.NON_PREORDER) == true
        return if (pre == non) null else pre
    }
    val usedFilter: Boolean? get() = used ?: if (flags?.contains(DetailFlag.USED) == true) true else null
}
