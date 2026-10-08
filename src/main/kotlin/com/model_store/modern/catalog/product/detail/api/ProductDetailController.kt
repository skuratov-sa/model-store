package com.model_store.modern.catalog.product.detail.api

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.model_store.modern.catalog.product.detail.application.ProductDetailQuery
import com.model_store.modern.catalog.product.detail.domain.*
import com.model_store.modern.shared.domain.Actor
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.time.LocalDateTime

data class CategoryInCard(val id: Long, val name: String?)
data class ReviewInCard(val id: Long, val rating: Int, val comment: String?, val reviewerName: String, val imageId: Long?, val createdAt: Instant?)
data class ProductCardResponse(
    val id: Long, val name: String?, val description: String?, val price: Float,
    val prepaymentAmount: Float?, val count: Int?, val currency: String, val originality: String?,
    val participantId: Long, val status: String, val categories: List<CategoryInCard>,
    val availability: String, val used: Boolean?, val externalUrl: String?, val imageIds: List<Long>,
    val reviews: List<ReviewInCard>, val sellerLogin: String, val sellerRating: Float, val totalReviews: Int,
)
data class MyProductResponse(
    val id: Long, val name: String?, val count: Int?, val price: Float, val prepaymentAmount: Float?,
    val currency: String, val categories: List<CategoryInCard>, val imageId: Long?, val sellerId: Long,
    val expirationDate: Instant?, val status: String, val availability: String, val used: Boolean?,
    val externalUrl: String?, val sellerLogin: String, val sellerRating: Float, val totalReviews: Int,
    val createdAt: Instant?,
)

data class MyPageRequest(val size: Int? = null, val lastCreatedAt: Instant? = null,
    val lastPrice: Float? = null, val lastId: Long? = null, val sortBy: DetailSort? = null)
data class MyPriceRangeRequest(val minPrice: Int? = null, val maxPrice: Int? = null)
data class MyDateRangeRequest(val start: LocalDateTime? = null, val end: LocalDateTime? = null)
@JsonIgnoreProperties(ignoreUnknown = true)
data class MyProductsRequest(
    val name: String? = null, val categoryId: Long? = null, val catalogFlags: List<DetailFlag>? = null,
    val used: Boolean? = null, val originality: String? = null, val priceRange: MyPriceRangeRequest? = null,
    val dateRange: MyDateRangeRequest? = null, val pageable: MyPageRequest? = null,
) {
    fun filter() = MyProductsFilter(name, categoryId, catalogFlags, used, originality,
        priceRange?.minPrice ?: if (priceRange != null) 0 else null,
        priceRange?.maxPrice ?: if (priceRange != null) 0 else null,
        dateRange?.start, dateRange?.end, pageable?.size ?: if (pageable != null) 0 else 50,
        pageable?.lastCreatedAt, pageable?.lastPrice, pageable?.lastId ?: 0,
        pageable?.sortBy ?: DetailSort.DATE_DESC)
}

@RestController
@Profile("modern")
class ProductDetailController(private val query: ProductDetailQuery) {
    @GetMapping("/product/{id}")
    fun card(@PathVariable id: Long): ResponseEntity<ProductCardResponse> =
        query.card(id)?.let { ResponseEntity.ok(it.response()) } ?: ResponseEntity.notFound().build()

    @PostMapping("/products/my")
    fun mine(@AuthenticationPrincipal actor: Actor?, @RequestBody request: MyProductsRequest): List<MyProductResponse> {
        val ownerId = actor?.participantId ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        return query.mine(ownerId, actor.role == "ADMIN", request.filter()).map { it.response() }
    }
}

private fun DetailCategory.response() = CategoryInCard(id, name)
private fun ProductCard.response() = ProductCardResponse(id, name, description, price, prepaymentAmount,
    count, currency, originality, participantId, status, categories.map { it.response() }, availability,
    used, null, imageIds, reviews.map { ReviewInCard(it.id, it.rating, it.comment, it.reviewerName,
        it.imageId, it.createdAt) }, sellerLogin, sellerRating, totalReviews)
private fun MyProduct.response() = MyProductResponse(id, name, count, price, prepaymentAmount, currency,
    categories.map { it.response() }, imageId, sellerId, expirationDate, status, availability, used,
    null, sellerLogin, sellerRating, totalReviews, createdAt)
