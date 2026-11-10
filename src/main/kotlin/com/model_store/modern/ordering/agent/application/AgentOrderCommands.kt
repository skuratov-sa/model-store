package com.model_store.modern.ordering.agent.application

import com.model_store.modern.catalog.product.reservation.application.*
import com.model_store.modern.identity.participant.application.AgentAccessLock
import com.model_store.modern.ordering.lifecycle.application.*
import com.model_store.modern.ordering.lifecycle.domain.*
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

enum class AgentOrderProblem { ORDER_NOT_FOUND, UNKNOWN_ACTION, INVALID_PAGE, ACCESS_DENIED, INVALID_ACTOR, BOT_CALLER }
class AgentOrderFailure(val problem: AgentOrderProblem) : RuntimeException(problem.name)

/** Explicit administrator delegation, independent of ordinary bot-restricted HTTP actions. */
@Service
@Profile("modern")
class AgentOrderCommands(private val lifecycle: LifecycleStore, private val transaction: LifecycleTransaction,
    private val access: AgentAccessLock, private val stock: ProductReservation, private val audit: AgentOrderAudit) {
    @Transactional(transactionManager = "transactionManager")
    fun execute(callerAdminId: Long, agentId: Long, orderId: Long, action: String,
        comment: String?, deliveryUrl: String?): Long {
        transaction.requireWritableJpa()
        val observed = lifecycle.observeOrder(orderId)
        (listOf(callerAdminId, agentId) + listOfNotNull(observed?.parties?.sellerId, observed?.parties?.customerId))
            .distinct().sorted().forEach(lifecycle::lockParticipant)
        // Both rows are already held as part of the COMPLETE sorted participant set.
        access.lockActiveAdminAndAgent(callerAdminId, agentId)
        if (observed == null) throw AgentOrderFailure(AgentOrderProblem.ORDER_NOT_FOUND)
        val order = lifecycle.lockOrder(orderId) ?: throw AgentOrderFailure(AgentOrderProblem.ORDER_NOT_FOUND)
        if (order.parties != observed.parties || order.parties.sellerId != agentId)
            throw AgentOrderFailure(AgentOrderProblem.ORDER_NOT_FOUND)
        val operation = when (action) {
            "CONFIRM" -> OrderAction.AGREE
            "CONFIRM_PREPAYMENT" -> OrderAction.APPROVE_PREPAYMENT
            "SHIP" -> OrderAction.SHIP
            "CANCEL" -> OrderAction.CANCEL
            else -> throw AgentOrderFailure(AgentOrderProblem.UNKNOWN_ACTION)
        }
        // Preserve legacy observed-state validation, then distinguish a CAS loser from a replay.
        observed.transition(operation, agentId)
        if (order.status != observed.status) throw LifecycleFailure(LifecycleProblem.STATUS_CHANGED)
        val target = order.transition(operation, agentId)
        if (!lifecycle.transition(order, target, comment, null, deliveryUrl, operation))
            throw LifecycleFailure(LifecycleProblem.STATUS_CHANGED)
        if (operation == OrderAction.CANCEL && order.productId != null && order.quantity > 0) {
            try { stock.returnLimitedStock(order.productId, order.quantity) }
            catch (failure: ProductReservationFailure) {
                if (failure.problem == ProductReservationProblem.TRANSACTION_REQUIRED) throw failure
                throw LifecycleFailure(LifecycleProblem.STOCK_CONFLICT)
            }
        }
        audit.record(callerAdminId, agentId, orderId, action)
        return orderId
    }
}
