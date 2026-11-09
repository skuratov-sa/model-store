package com.model_store.modern.reputation.domain

import java.time.Instant

enum class ReviewProblem { ALREADY_EXISTS, ORDER_NOT_FOUND, ORDER_NOT_TERMINATED, BOT_ACTOR, INVALID_ACTOR }
class ReviewFailure(val problem: ReviewProblem) : RuntimeException(problem.name)

data class Review(val id: Long, val orderId: Long, val reviewerId: Long, val sellerId: Long,
                  val productId: Long?, val rating: Int, val comment: String?, val createdAt: Instant?)

data class NewReview(val orderId: Long, val reviewerId: Long, val sellerId: Long,
                     val productId: Long?, val rating: Int, val comment: String?) {
    init { require(rating in 1..5) }
}

object ReviewEligibility {
    fun requireCustomer(customerId: Long, reviewerId: Long) {
        if (customerId != reviewerId) throw ReviewFailure(ReviewProblem.ORDER_NOT_FOUND)
    }
    fun requireTerminated(status: String) {
        if (status !in setOf("COMPLETED", "FAILED")) throw ReviewFailure(ReviewProblem.ORDER_NOT_TERMINATED)
    }
}
