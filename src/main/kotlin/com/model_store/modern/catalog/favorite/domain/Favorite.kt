package com.model_store.modern.catalog.favorite.domain

import java.time.Instant
import java.time.LocalDateTime

class FavoriteTargetNotFound : RuntimeException("Product or participant not found")
class FavoriteAccessDenied : RuntimeException("Доступ запрещён")

enum class FavoriteFlag { ALL, PREORDER, NON_PREORDER, USED }
enum class FavoriteSort { DATE_DESC, PRICE_ASC, PRICE_DESC }

data class FavoritePage(
    val size: Int = 0,
    val lastCreatedAt: Instant? = null,
    val lastPrice: Float? = null,
    val lastId: Long? = null,
    val sortBy: FavoriteSort? = null,
)

data class FavoritePriceRange(val minPrice: Int = 0, val maxPrice: Int = 0)
data class FavoriteDateRange(val start: LocalDateTime? = null, val end: LocalDateTime? = null)

data class FavoriteCriteria(
    val name: String? = null,
    val categoryId: Long? = null,
    val catalogFlags: List<FavoriteFlag>? = null,
    val used: Boolean? = null,
    val originality: String? = null,
    val participantId: Long? = null,
    val priceRange: FavoritePriceRange? = null,
    val dateRange: FavoriteDateRange? = null,
    val pageable: FavoritePage? = null,
) {
    val preorderFilter: Boolean?
        get() {
            val preorder = catalogFlags?.contains(FavoriteFlag.PREORDER) == true
            val nonPreorder = catalogFlags?.contains(FavoriteFlag.NON_PREORDER) == true
            return if (preorder == nonPreorder) null else preorder
        }

    val usedFilter: Boolean?
        get() = used ?: if (catalogFlags?.contains(FavoriteFlag.USED) == true) true else null

    val effectivePage: FavoritePage
        get() = (pageable ?: FavoritePage(size = 50)).let {
            it.copy(lastId = it.lastId ?: 0, sortBy = it.sortBy ?: FavoriteSort.DATE_DESC)
        }
}
