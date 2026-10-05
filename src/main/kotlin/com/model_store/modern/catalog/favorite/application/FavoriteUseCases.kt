package com.model_store.modern.catalog.favorite.application

import com.model_store.modern.catalog.favorite.domain.FavoriteTargetNotFound
import com.model_store.modern.catalog.favorite.domain.FavoriteCriteria
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

data class FavoriteCategory(val id: Long, val name: String?)
data class FavoriteProduct(
    val id: Long, val name: String?, val count: Int?, val price: Float,
    val prepaymentAmount: Float?, val currency: String, val categories: List<FavoriteCategory>,
    val imageId: Long?, val sellerId: Long, val expirationDate: Instant?,
    val status: String, val availability: String, val used: Boolean?,
    val sellerLogin: String, val sellerRating: Float, val totalReviews: Int,
    val createdAt: Instant?,
)

/** These reads cross into product and identity only by identifier. */
interface FavoriteTargetRead {
    fun activeParticipant(ownerId: Long): Boolean
    fun adultAge(ownerId: Long): Int?
    fun actualProduct(productId: Long): Boolean
    fun visibleFavorites(ownerId: Long, criteria: FavoriteCriteria, includeAdult: Boolean): List<FavoriteProduct>
}

interface FavoriteStore {
    /** Serialize add and remove commands for this owner within the current database transaction. */
    fun lockOwner(ownerId: Long)
    fun addIfAbsent(ownerId: Long, productId: Long)
    fun remove(ownerId: Long, productId: Long)
}

@Service
class FavoriteUseCases(private val targets: FavoriteTargetRead, private val store: FavoriteStore) {
    @Transactional(transactionManager = "transactionManager", readOnly = true)
    fun find(ownerId: Long, criteria: FavoriteCriteria): List<FavoriteProduct> =
        targets.visibleFavorites(ownerId, criteria, (targets.adultAge(ownerId) ?: 0) >= 18)

    @Transactional(transactionManager = "transactionManager")
    fun add(ownerId: Long, productId: Long) {
        store.lockOwner(ownerId)
        if (!targets.activeParticipant(ownerId) || !targets.actualProduct(productId)) throw FavoriteTargetNotFound()
        store.addIfAbsent(ownerId, productId)
    }

    @Transactional(transactionManager = "transactionManager")
    fun remove(ownerId: Long, productId: Long) {
        store.lockOwner(ownerId)
        store.remove(ownerId, productId)
    }
}
