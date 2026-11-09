package com.model_store.modern.ordering.lifecycle.application

import com.model_store.modern.catalog.product.reservation.application.*
import com.model_store.modern.media.image.command.application.*
import com.model_store.modern.ordering.lifecycle.domain.*
import com.model_store.modern.shared.domain.Actor
import com.model_store.modern.shared.domain.TokenType
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

interface LifecycleStore {
    fun observeOrder(orderId: Long): LifecycleOrder?
    fun lockParticipant(id: Long)
    fun isAgent(id: Long): Boolean
    fun lockOrder(orderId: Long): LifecycleOrder?
    fun transition(order: LifecycleOrder, target: OrderStatus, comment: String?, proofId: Long?, deliveryUrl: String?, action: OrderAction): Boolean
    fun disputedPayment(order: LifecycleOrder, proofId: Long?): Boolean
}

/** Minimal existing-case effects of ordinary payments. Caller holds participant -> order locks in its JPA transaction.
 * Case opening and administrator decisions belong to 26/27; they must acquire the order before its cases too.
 */
interface OrderPaymentCases {
    fun dismissCancellation(orderId: Long)
    fun lockPaymentDispute(orderId: Long): PaymentCase?
    fun addCaseEvidence(caseId: Long, imageId: Long?, uploaderId: Long): Boolean
}

interface LifecycleRead { fun orders(participantId: Long, seller: Boolean): List<OrderView> }

interface LifecycleTransaction { fun requireWritableJpa() }
class LifecycleTransactionRequired : RuntimeException("Writable application JPA transaction required")

@Service
@Profile("modern")
class OrderLifecycle(private val store: LifecycleStore, private val read: LifecycleRead, private val cases: OrderPaymentCases,
                     private val transaction: LifecycleTransaction,
                     private val stock: ProductReservation, private val evidence: OrderImageEvidence) {
    @Transactional(transactionManager = "transactionManager", readOnly = true)
    fun list(actor: Actor?, seller: Boolean): List<OrderView> = read.orders(actorId(actor), seller)

    @Transactional(transactionManager = "transactionManager")
    fun execute(actor: Actor?, orderId: Long, action: OrderAction, comment: String? = null,
                imageId: Long? = null, deliveryUrl: String? = null): Long {
        val participantId = actorId(actor)
        transaction.requireWritableJpa()
        // The controller's legacy guard runs even for CANCEL invoked by a bot customer.
        if (blocksBotActor(action) && store.isAgent(participantId)) fail(LifecycleProblem.BOT_SELLER)
        val observed = store.observeOrder(orderId) ?: fail(LifecycleProblem.INVALID_TRANSITION)
        observed.transition(action, participantId)
        val parties = observed.parties
        listOf(parties.sellerId, parties.customerId).distinct().sorted().forEach(store::lockParticipant)
        if (blocksBotActor(action) && store.isAgent(participantId)) fail(LifecycleProblem.BOT_SELLER)
        val order = store.lockOrder(orderId) ?: fail(LifecycleProblem.INVALID_TRANSITION)
        if (order.parties != parties) fail(LifecycleProblem.STATUS_CHANGED)
        val disputeOrder = if (action == OrderAction.PAY && observed.status == OrderStatus.DISPUTED) observed
            else if (action == OrderAction.PAY && order.status == OrderStatus.DISPUTED) order else null
        if (disputeOrder != null) {
            val paymentCase = cases.lockPaymentDispute(orderId) ?: fail(LifecycleProblem.DISPUTE_PAYMENT_UNAVAILABLE)
            // Legacy checks the open case and the originally observed proof before its guarded write.
            paymentCase.requirePaymentAllowed(disputeOrder)
            if (order.status != OrderStatus.DISPUTED || order.paymentProofId != disputeOrder.paymentProofId)
                fail(LifecycleProblem.DISPUTE_PAYMENT_CHANGED)
            if (!store.disputedPayment(disputeOrder, imageId)) fail(LifecycleProblem.DISPUTE_PAYMENT_CHANGED)
            activate(orderId, participantId, imageId)
            if (!cases.addCaseEvidence(paymentCase.id, imageId, participantId)) fail(LifecycleProblem.DISPUTE_EVIDENCE_FAILED)
        } else {
            if (order.status != observed.status) fail(LifecycleProblem.STATUS_CHANGED)
            val target = order.transition(action, participantId)
            if (!store.transition(order, target, comment, imageId, deliveryUrl, action)) fail(LifecycleProblem.STATUS_CHANGED)
            if (action == OrderAction.CANCEL && order.productId != null) {
                try { stock.returnLimitedStock(order.productId, order.quantity) }
                catch (failure: ProductReservationFailure) {
                    if (failure.problem == ProductReservationProblem.TRANSACTION_REQUIRED) throw failure
                    fail(LifecycleProblem.STOCK_CONFLICT)
                }
            }
            if (action == OrderAction.PAY || action == OrderAction.PREPAY) {
                activate(orderId, participantId, imageId)
                cases.dismissCancellation(orderId)
            }
        }
        return orderId
    }

    private fun activate(orderId: Long, uploaderId: Long, imageId: Long?) {
        try {
            if (evidence.activate(orderId, uploaderId, listOf(imageId)) != 1) fail(LifecycleProblem.PROOF_NOT_FOUND)
        } catch (failure: OrderImageEvidenceFailure) {
            when (failure.problem) {
                OrderImageEvidenceProblem.IMAGE_BUSY -> fail(LifecycleProblem.EVIDENCE_BUSY)
                OrderImageEvidenceProblem.TRANSACTION_REQUIRED -> throw failure
                else -> fail(LifecycleProblem.PROOF_NOT_FOUND)
            }
        }
    }

    private fun actorId(actor: Actor?): Long {
        if (actor?.tokenType != TokenType.ACCESS || actor.role !in setOf("USER", "ADMIN")) fail(LifecycleProblem.ACCESS_DENIED)
        return actor.participantId?.takeIf { it > 0 } ?: fail(LifecycleProblem.INVALID_ACTOR)
    }
    private fun blocksBotActor(action: OrderAction) = action in setOf(
        OrderAction.AGREE, OrderAction.APPROVE_PREPAYMENT, OrderAction.SHIP, OrderAction.CANCEL)
    private fun fail(problem: LifecycleProblem): Nothing = throw LifecycleFailure(problem)
}
