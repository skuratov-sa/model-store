package com.model_store.modern.catalog.product.search.domain

import java.time.Instant
import java.time.LocalDateTime

enum class CatalogFlag { ALL, PREORDER, NON_PREORDER, USED }
enum class SearchSort { DATE_DESC, PRICE_ASC, PRICE_DESC }

data class SearchPage(
    val size: Int = 0,
    val lastCreatedAt: Instant? = null,
    val lastPrice: Float? = null,
    val lastId: Long? = null,
    val sortBy: SearchSort? = null,
)

data class SearchPriceRange(val minPrice: Int = 0, val maxPrice: Int = 0)
data class SearchDateRange(val start: LocalDateTime? = null, val end: LocalDateTime? = null)

data class ProductSearchCriteria(
    val name: String? = null,
    val categoryId: Long? = null,
    val catalogFlags: List<CatalogFlag>? = null,
    val used: Boolean? = null,
    val originality: String? = null,
    val participantId: Long? = null,
    val priceRange: SearchPriceRange? = null,
    val dateRange: SearchDateRange? = null,
    val pageable: SearchPage? = null,
) {
    val preorderFilter: Boolean?
        get() {
            val preorder = catalogFlags?.contains(CatalogFlag.PREORDER) == true
            val nonPreorder = catalogFlags?.contains(CatalogFlag.NON_PREORDER) == true
            return if (preorder == nonPreorder) null else preorder
        }

    val usedFilter: Boolean?
        get() = used ?: if (catalogFlags?.contains(CatalogFlag.USED) == true) true else null

    val effectivePage: SearchPage
        get() = (pageable ?: SearchPage(size = 50)).let {
            it.copy(lastId = it.lastId ?: 0, sortBy = it.sortBy ?: SearchSort.DATE_DESC)
        }
}
