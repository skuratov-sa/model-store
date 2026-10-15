package com.model_store.modern.catalog.product.reservation.application

/** Values captured while the product row is locked, before stock is changed. No catalog entity crosses this boundary. */
data class OrderProductSnapshot(
    val id: Long,
    val ownerId: Long,
    val name: String?,
    val unitPrice: Float,
    val prepaymentAmount: Float?,
    val currency: String,
    val availability: String,
    val status: String,
    val count: Int?,
)

data class ProductQuantity(val productId: Long, val quantity: Int)

enum class ProductReservationProblem {
    INVALID_PRODUCT_ID, INVALID_QUANTITY, DUPLICATE_IDS, PRODUCT_NOT_FOUND,
    OUT_OF_STOCK, COUNT_OVERFLOW, RESERVATION_CONFLICT, TRANSACTION_REQUIRED,
}

class ProductReservationFailure(val problem: ProductReservationProblem, val productId: Long? = null) :
    RuntimeException("${problem.name}${productId?.let { ": $it" } ?: ""}")

/**
 * Caller must already own a JPA transaction spanning its order writes. The implementation joins its JDBC connection;
 * it never starts or commits a transaction. A failure must escape that transaction so the caller rolls it back.
 *
 * Duplicate IDs are rejected rather than merged. The caller may merge lines before calling, with overflow checks.
 * Rows are locked in ascending product ID order and snapshots are returned in that same order. A batch is fully
 * validated before any stock is written. ACTIVE products other than GIVEAWAY are visible, matching legacy order
 * creation. Any non-null count is checked, including PREORDER; only PURCHASABLE stock is decremented.
 * The ordering caller owns buyer/owner/agent checks and must acquire product locks through this port before any
 * other product row locks, in ascending ID order. Catalog product commands use the same row lock.
 *
 * [returnLimitedStock] is the catalog-owned reverse write for a completed order transition. It returns false when
 * the product is absent, no longer PURCHASABLE, or unlimited; status does not affect this legacy rule. The caller
 * (task 25) must decide whether the transition warrants a return and ensure it happens exactly once. This method
 * has no order ID and is intentionally not independently idempotent.
 * In particular, process_expired_booked_orders() already restores stock in SQL; do not return it a second time.
 */
interface ProductReservation {
    fun reserve(lines: List<ProductQuantity>): List<OrderProductSnapshot>
    fun returnLimitedStock(productId: Long, quantity: Int): Boolean
}
