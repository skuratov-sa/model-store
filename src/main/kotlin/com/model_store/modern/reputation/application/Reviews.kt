package com.model_store.modern.reputation.application

import com.model_store.modern.ordering.lifecycle.application.ReviewOrderEligibility
import com.model_store.modern.reputation.domain.*
import com.model_store.modern.shared.domain.Actor
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Profile("modern")
class Reviews(private val store: ReviewStore, private val orders: ReviewOrderEligibility,
              private val transaction: ReviewTransaction, private val read: ReviewRead) {
    @Transactional(transactionManager = "transactionManager", readOnly = true)
    fun authorize(actor: Actor?) { actorId(actor) }
    @Transactional(transactionManager = "transactionManager")
    fun create(actor: Actor?, orderId: Long, rating: Int, comment: String?): Long {
        val reviewer = actorId(actor)
        transaction.requireWritableJpa()
        // Preserve legacy precedence even if an old review has stale order-party snapshots.
        if (store.exists(orderId, reviewer)) fail(ReviewProblem.ALREADY_EXISTS)
        val observed = orders.observe(orderId) ?: fail(ReviewProblem.ORDER_NOT_FOUND)
        ReviewEligibility.requireCustomer(observed.customerId, reviewer)
        val order = orders.lockForReview(observed) ?: fail(ReviewProblem.ORDER_NOT_FOUND)
        ReviewEligibility.requireCustomer(order.customerId, reviewer)
        store.lockSeller(order.sellerId)
        if (store.exists(orderId, reviewer)) fail(ReviewProblem.ALREADY_EXISTS)
        ReviewEligibility.requireTerminated(order.status)
        return store.insert(NewReview(orderId, reviewer, order.sellerId, order.productId, rating, comment))
    }

    @Transactional(transactionManager = "transactionManager")
    fun delete(actor: Actor?, reviewId: Long) {
        val reviewer = actorId(actor)
        transaction.requireWritableJpa()
        val observed = store.find(reviewId)?.takeIf { it.reviewerId == reviewer } ?: return
        val order = orders.observe(observed.orderId) ?: return
        if (orders.lockForReview(order, setOf(observed.reviewerId, observed.sellerId)) == null) return
        store.lockSeller(observed.sellerId)
        val current = store.lockReview(reviewId) ?: return
        // A changed association is not followed to another participant/order/seller frontier.
        if (current != observed) return
        store.delete(reviewId)
    }

    @Transactional(transactionManager = "transactionManager", readOnly = true)
    fun forSeller(actor: Actor?, sellerId: Long): List<ReviewView> {
        actorId(actor)
        return read.forSeller(sellerId)
    }

    private fun actorId(actor: Actor?): Long {
        val id=actor?.participantId?.takeIf { it > 0 } ?: fail(ReviewProblem.INVALID_ACTOR)
        // Legacy AgentAccountAccessFilter rejects bot actors on every /reviews route.
        // It does not restrict ordinary actors by role/account status or token subtype.
        if (read.isAgent(id)) fail(ReviewProblem.BOT_ACTOR)
        return id
    }
    private fun fail(problem: ReviewProblem): Nothing = throw ReviewFailure(problem)
}
