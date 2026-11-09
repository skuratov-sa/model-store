package com.model_store.modern.ordering.lifecycle.domain

enum class OrderStatus {
    BOOKED, AWAITING_PREPAYMENT, AWAITING_PREPAYMENT_APPROVAL, AWAITING_PAYMENT,
    ASSEMBLING, ON_THE_WAY, COMPLETED, CANCELLED, DISPUTED, FAILED,
}

enum class OrderAction {
    AGREE, PREPAY, APPROVE_PREPAYMENT, PAY, SHIP, COMPLETE, CANCEL,
}

enum class LifecycleProblem {
    INVALID_TRANSITION, STATUS_CHANGED, DISPUTE_PAYMENT_UNAVAILABLE, DISPUTE_PAYMENT_CHANGED,
    DISPUTE_EVIDENCE_FAILED, ACCESS_DENIED, BOT_SELLER, INVALID_ACTOR, PROOF_NOT_FOUND,
    EVIDENCE_BUSY, STOCK_CONFLICT, PRODUCT_NOT_FOUND, PARTICIPANT_NOT_FOUND, TRANSFER_NOT_FOUND,
}

class LifecycleFailure(val problem: LifecycleProblem) : RuntimeException(problem.name)

data class OrderParties(val sellerId: Long, val customerId: Long)

data class PaymentCase(val id: Long, val kind: String, val state: String, val previousStatus: String?) {
    fun requirePaymentAllowed(order: LifecycleOrder) {
        if (kind != "DISPUTE" || state != "OPEN" || previousStatus != OrderStatus.AWAITING_PAYMENT.name ||
            order.status != OrderStatus.DISPUTED || order.paymentProofId == null)
            throw LifecycleFailure(LifecycleProblem.DISPUTE_PAYMENT_UNAVAILABLE)
    }
}

data class LifecycleOrder(
    val id: Long, val parties: OrderParties, val status: OrderStatus,
    val prepaymentAmount: Float?, val productId: Long?, val quantity: Int,
    val paymentProofId: Long?,
) {
    fun transition(action: OrderAction, actorId: Long): OrderStatus {
        val seller = parties.sellerId == actorId
        val buyer = parties.customerId == actorId
        val target = when (action) {
            OrderAction.AGREE -> if (seller && status == OrderStatus.BOOKED)
                if (prepaymentAmount != null && prepaymentAmount > 0) OrderStatus.AWAITING_PREPAYMENT else OrderStatus.AWAITING_PAYMENT else null
            OrderAction.PREPAY -> if (buyer && status == OrderStatus.AWAITING_PREPAYMENT &&
                prepaymentAmount != null && prepaymentAmount != 0f) OrderStatus.AWAITING_PREPAYMENT_APPROVAL else null
            OrderAction.APPROVE_PREPAYMENT -> if (seller && status == OrderStatus.AWAITING_PREPAYMENT_APPROVAL) OrderStatus.AWAITING_PAYMENT else null
            OrderAction.PAY -> if (buyer && status == OrderStatus.AWAITING_PAYMENT) OrderStatus.ASSEMBLING
                else if (buyer && status == OrderStatus.DISPUTED) OrderStatus.DISPUTED else null
            OrderAction.SHIP -> if (seller && status == OrderStatus.ASSEMBLING) OrderStatus.ON_THE_WAY else null
            OrderAction.COMPLETE -> if (buyer && status == OrderStatus.ON_THE_WAY) OrderStatus.COMPLETED else null
            OrderAction.CANCEL -> if ((seller || buyer) && status == OrderStatus.BOOKED) OrderStatus.CANCELLED else null
        }
        return target ?: throw LifecycleFailure(LifecycleProblem.INVALID_TRANSITION)
    }
}
