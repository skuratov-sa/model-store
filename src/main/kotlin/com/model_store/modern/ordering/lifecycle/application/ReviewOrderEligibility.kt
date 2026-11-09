package com.model_store.modern.ordering.lifecycle.application

/** Scalar owner read. Commands must observe before crossing the participant lock frontier. */
data class ReviewOrderSnapshot(val orderId: Long, val customerId: Long, val sellerId: Long,
                               val productId: Long?, val status: String)

interface ReviewOrderEligibility {
    fun observe(orderId: Long): ReviewOrderSnapshot?

    /** Caller writable JPA transaction; participant IDs ASC -> order FOR UPDATE.
     * Additional IDs are historical review parties, captured before locking. Returns null on
     * changed order associations; never follows a new owner after the participant frontier.
     * Status is returned fresh, without imposing account/role/eligibility policy.
     */
    fun lockForReview(observed: ReviewOrderSnapshot, additionalParticipantIds: Set<Long> = emptySet()): ReviewOrderSnapshot?
}
