package com.model_store.modern.catalog.product.search.api

import com.model_store.modern.catalog.product.search.application.ProductSearchRow
import java.time.Instant

data class SearchCategoryDto(val id: Long, val name: String?)

data class ProductSearchDto(
    val id: Long,
    val name: String?,
    val count: Int?,
    val price: Float,
    val prepaymentAmount: Float?,
    val currency: String,
    val categories: List<SearchCategoryDto>,
    val imageId: Long?,
    val sellerId: Long,
    val expirationDate: Instant?,
    val status: String,
    val availability: String,
    val used: Boolean?,
    val externalUrl: String?,
    val sellerLogin: String,
    val sellerRating: Float,
    val totalReviews: Int,
    val createdAt: Instant?,
)

object ProductSearchDtoMapper {
    fun map(row: ProductSearchRow): ProductSearchDto = ProductSearchDto(
        row.id, row.name, row.count, row.price, row.prepaymentAmount, row.currency,
        row.categories.map { SearchCategoryDto(it.id, it.name) }, row.imageId, row.sellerId,
        row.expirationDate, row.status, row.availability, row.used, null,
        row.sellerLogin, row.sellerRating, row.totalReviews, row.createdAt,
    )
}
