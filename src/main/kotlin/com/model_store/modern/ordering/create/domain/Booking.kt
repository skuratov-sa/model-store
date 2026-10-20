package com.model_store.modern.ordering.create.domain

/** Booking input contains no client-supplied product, seller, price, currency or status snapshot. */
data class OrderLine(val productId: Long?, val count: Int?, val addressId: Long?,
                     val transferId: Long?, val comment: String?)

enum class OrderProblem {
    PRODUCT_NOT_FOUND, COUNT_INVALID, OUT_OF_STOCK, OWN_PRODUCT_ORDER_FORBIDDEN,
    ACCESS_DENIED, INVALID_ADDRESS, INVALID_TRANSFER, TOKEN_INVALID_OR_EXPIRED,
    TRANSFER_NOT_FOUND, SELLER_CHANGED, RESERVATION_CONFLICT, AGENT_PRODUCT,
}

class OrderFailure(val problem: OrderProblem) : RuntimeException(problem.name)

/** Preserves legacy duplicate-line behavior: first delivery details win, counts add exactly. */
object OrderBatch {
    fun merge(requests: List<OrderLine>): List<OrderLine> {
        val merged = sortedMapOf<Long, OrderLine>()
        requests.forEach { line ->
            val id = line.productId ?: throw OrderFailure(OrderProblem.PRODUCT_NOT_FOUND)
            val count = line.count?.takeIf { it > 0 } ?: throw OrderFailure(OrderProblem.COUNT_INVALID)
            val previous = merged[id]
            merged[id] = if (previous == null) line else previous.copy(count =
                try { Math.addExact(requireNotNull(previous.count), count) }
                catch (_: ArithmeticException) { throw OrderFailure(OrderProblem.COUNT_INVALID) })
        }
        return merged.values.toList()
    }
}

/** All values are copied from a catalog snapshot taken under the product lock. */
data class ProductTerms(val id: Long, val ownerId: Long, val name: String?, val unitPrice: Float,
                        val prepaymentAmount: Float?, val currency: String, val availability: String)

/** Scalar write model for one BOOKED order. */
data class BookingDraft(
    val sellerId: Long, val customerId: Long, val productId: Long, val count: Int,
    val productName: String?, val productUnitPrice: Float, val productCurrency: String,
    val productAvailability: String, val addressId: Long, val transferId: Long,
    val totalPrice: Float, val prepaymentAmount: Float, val comment: String?,
) {
    companion object {
        fun from(customerId: Long, line: OrderLine, product: ProductTerms): BookingDraft {
            val count = requireNotNull(line.count)
            val prepayment = if (product.availability == "PURCHASABLE") 0f else product.prepaymentAmount ?: 0f
            return BookingDraft(product.ownerId, customerId, product.id, count, product.name,
                product.unitPrice, product.currency, product.availability,
                requireNotNull(line.addressId), requireNotNull(line.transferId),
                (product.unitPrice - prepayment) * count,
                if (product.availability == "PURCHASABLE") 0f else prepayment * count,
                line.comment)
        }
    }
}
