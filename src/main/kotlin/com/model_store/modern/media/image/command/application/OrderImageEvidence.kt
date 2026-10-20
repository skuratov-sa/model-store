package com.model_store.modern.media.image.command.application

/**
 * Activates ORDER images inside the caller's existing, writable JPA transaction.
 * The caller must first lock participants in ascending ID order, then the order, authorize the
 * uploader as the appropriate party, and validate the payment/case transition. Media owns only
 * image rows and trusts that caller authorization; knowing IDs alone is not authorization.
 * A nonempty call claims every ID or fails and marks the caller transaction rollback-only.
 * A null/empty batch is a no-op for a case without images. A proof caller must pass a singleton
 * list: listOf(null) fails with IMAGE_NOT_FOUND, preserving the missing-proof contract.
 * All failures must propagate; IMAGE_BUSY requires rollback before any retry in a new transaction.
 */
interface OrderImageEvidence {
    fun activate(orderId: Long?, uploaderId: Long?, imageIds: List<Long?>?): Int
}

enum class OrderImageEvidenceProblem {
    INVALID_INPUT, DUPLICATE_IDS, IMAGE_NOT_FOUND, IMAGE_BUSY, TRANSACTION_REQUIRED,
}

class OrderImageEvidenceFailure(val problem: OrderImageEvidenceProblem) : RuntimeException(problem.name)
