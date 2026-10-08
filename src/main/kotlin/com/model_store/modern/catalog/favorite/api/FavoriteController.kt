package com.model_store.modern.catalog.favorite.api

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.model_store.modern.catalog.favorite.application.FavoriteProduct
import com.model_store.modern.catalog.favorite.application.FavoriteUseCases
import com.model_store.modern.catalog.favorite.domain.FavoriteAccessDenied
import com.model_store.modern.catalog.favorite.domain.*
import com.model_store.modern.shared.domain.Actor
import org.springframework.context.annotation.Profile
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import java.time.Instant

data class FavoritePageRequest(
    val size: Int? = null, val lastCreatedAt: Instant? = null,
    val lastPrice: Float? = null, val lastId: Long? = null, val sortBy: FavoriteSort? = null,
) {
    fun criteria() = FavoritePage(size ?: 0, lastCreatedAt, lastPrice, lastId, sortBy)
}

data class FavoritePriceRangeRequest(val minPrice: Int? = null, val maxPrice: Int? = null) {
    fun criteria() = FavoritePriceRange(minPrice ?: 0, maxPrice ?: 0)
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class FavoriteFindRequest(
    val name: String? = null, val categoryId: Long? = null,
    val catalogFlags: List<FavoriteFlag>? = null, val used: Boolean? = null,
    val originality: String? = null, val participantId: Long? = null,
    val priceRange: FavoritePriceRangeRequest? = null,
    val dateRange: FavoriteDateRange? = null,
    val pageable: FavoritePageRequest? = null,
) {
    fun criteria() = FavoriteCriteria(name, categoryId, catalogFlags, used, originality,
        participantId, priceRange?.criteria(), dateRange, pageable?.criteria())
}

data class FavoriteCategoryResponse(val id: Long, val name: String?)
data class FavoriteProductResponse(
    val id: Long, val name: String?, val count: Int?, val price: Float,
    val prepaymentAmount: Float?, val currency: String,
    val categories: List<FavoriteCategoryResponse>, val imageId: Long?, val sellerId: Long,
    val expirationDate: Instant?, val status: String, val availability: String,
    val used: Boolean?, val externalUrl: String?, val sellerLogin: String,
    val sellerRating: Float, val totalReviews: Int, val createdAt: Instant?,
) {
    companion object {
        fun from(product: FavoriteProduct) = FavoriteProductResponse(
            product.id, product.name, product.count, product.price, product.prepaymentAmount,
            product.currency, product.categories.map { FavoriteCategoryResponse(it.id, it.name) },
            product.imageId, product.sellerId, product.expirationDate, product.status,
            product.availability, product.used, null, product.sellerLogin,
            product.sellerRating, product.totalReviews, product.createdAt,
        )
    }
}

@RestController
@Profile("modern")
@RequestMapping("/favorites")
class FavoriteController(private val favorites: FavoriteUseCases) {
    @PostMapping("/find")
    fun find(@AuthenticationPrincipal actor: Actor?, @RequestBody request: FavoriteFindRequest): List<FavoriteProductResponse> =
        favorites.find(actor.ownerId(), request.criteria()).map(FavoriteProductResponse::from)

    @PostMapping
    fun add(@AuthenticationPrincipal actor: Actor?, @RequestParam productId: Long) =
        favorites.add(actor.ownerId(), productId)

    @DeleteMapping
    fun remove(@AuthenticationPrincipal actor: Actor?, @RequestParam productId: Long) =
        favorites.remove(actor.ownerId(), productId)

    private fun Actor?.ownerId(): Long = this?.participantId ?: throw FavoriteAccessDenied()
}
