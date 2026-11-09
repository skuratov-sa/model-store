package com.model_store.modern.reputation.application

import com.model_store.modern.reputation.domain.NewReview
import com.model_store.modern.reputation.domain.Review
import java.math.BigDecimal
import java.time.Instant

interface ReviewTransaction { fun requireWritableJpa() }
class ReviewTransactionRequired : RuntimeException("Writable application JPA transaction required")

/** Writes require caller JPA and the complete participant -> order -> seller lock frontier. */
interface ReviewStore {
    fun find(id: Long): Review?
    fun exists(orderId: Long, reviewerId: Long): Boolean
    fun lockSeller(sellerId: Long)
    fun lockReview(id: Long): Review?
    fun insert(review: NewReview): Long
    fun delete(id: Long)
}

data class ReviewView(val id: Long, val rating: Int, val comment: String?, val reviewerName: String,
                      val imageId: Long?, val createdAt: Instant?)
data class SellerRating(val sellerId: Long, val averageRating: BigDecimal?, val totalReviews: Int?)
interface ReviewRead {
    fun isAgent(participantId: Long): Boolean
    fun forSeller(sellerId: Long): List<ReviewView>
    fun forProduct(productId: Long): List<ReviewView>
    fun rating(sellerId: Long): SellerRating?
}
