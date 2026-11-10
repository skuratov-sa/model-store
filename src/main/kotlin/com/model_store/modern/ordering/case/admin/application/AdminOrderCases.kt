package com.model_store.modern.ordering.`case`.admin.application

import com.model_store.modern.catalog.product.reservation.application.*
import com.model_store.modern.identity.participant.application.CurrentAdminLock
import com.model_store.modern.ordering.`case`.admin.domain.*
import com.model_store.modern.ordering.`case`.user.application.CaseDetailView
import com.model_store.modern.ordering.lifecycle.application.LifecycleStore
import com.model_store.modern.ordering.lifecycle.application.LifecycleTransaction
import com.model_store.modern.ordering.lifecycle.domain.LifecycleOrder
import com.model_store.modern.shared.domain.Actor
import com.model_store.modern.shared.domain.TokenType
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Profile("modern")
class AdminOrderCases(private val store: AdminOrderCaseStore,
    private val lifecycle: LifecycleStore, private val transaction: LifecycleTransaction,
    private val admins: CurrentAdminLock, private val stock: ProductReservation) {

    // Runs before MVC argument conversion, as the legacy account filter does.
    @Transactional(transactionManager = "transactionManager", readOnly = true)
    fun authorize(actor: Actor?) { rejectAgent(adminId(actor)) }

    @Transactional(transactionManager = "transactionManager")
    fun queue(actor: Actor?, state: CaseState?, limit: Int, offset: Long): List<AdminCaseSummary> {
        currentAdmin(actor)
        if (state == null || limit !in 1..100 || offset < 0) fail(AdminCaseProblem.INVALID_PAGE)
        return store.queue(state, limit, offset)
    }

    @Transactional(transactionManager = "transactionManager")
    fun detail(actor: Actor?, caseId: Long): CaseDetailView {
        currentAdmin(actor)
        return store.detail(caseId) ?: fail(AdminCaseProblem.INVALID)
    }

    @Transactional(transactionManager = "transactionManager")
    fun telegram(actor: Actor?, caseId: Long, url: String?): Long {
        val id = adminId(actor)
        rejectAgent(id)
        requireCaseTelegramUrl(url)
        val (case, _) = lock(id, caseId, optimisticTelegram = true)
        if (!store.telegram(case, url!!)) fail(AdminCaseProblem.INVALID_TRANSITION)
        return caseId
    }

    @Transactional(transactionManager = "transactionManager")
    fun resolve(actor: Actor?, caseId: Long, outcome: CaseOutcome?, comment: String?): Long {
        val id = adminId(actor)
        rejectAgent(id)
        requireResolutionComment(comment)
        if (outcome == null || outcome == CaseOutcome.LEGACY) fail(AdminCaseProblem.INVALID_OUTCOME)
        val (case, order) = lock(id, caseId)
        val target = case.target(outcome, order.status.name)
        if (target != null) {
            if (!store.transition(order.id, order.status.name, target, comment!!)) fail(AdminCaseProblem.INVALID_TRANSITION)
            // Only the cancellation request reverses reserved stock. Appeal cancellation already did so in25.
            // Persisted legacy rows may have nonpositive count (no SQL CHECK). Legacy's
            // increment service treats those as a no-op; do not call the positive-quantity owner port.
            if (case.kind == "CANCELLATION_REQUEST" && order.productId != null && order.quantity > 0) {
                try { stock.returnLimitedStock(order.productId, order.quantity) }
                catch (failure: ProductReservationFailure) {
                    if (failure.problem == ProductReservationProblem.TRANSACTION_REQUIRED) throw failure
                    fail(AdminCaseProblem.STOCK_CONFLICT)
                }
            }
        }
        if (!store.resolve(case, id, outcome, comment!!)) fail(AdminCaseProblem.INVALID_TRANSITION)
        return caseId
    }

    private fun lock(adminId: Long, caseId: Long, optimisticTelegram: Boolean = false): Pair<AdminCase, LifecycleOrder> {
        transaction.requireWritableJpa()
        val observed = store.find(caseId) ?: run { admins.lockActiveAdmin(adminId); fail(AdminCaseProblem.INVALID) }
        val order = lifecycle.observeOrder(observed.orderId)
            ?: run { admins.lockActiveAdmin(adminId); fail(AdminCaseProblem.INVALID) }
        listOf(adminId, order.parties.customerId, order.parties.sellerId).distinct().sorted().forEach {
            if (it == adminId) admins.lockActiveAdmin(it) else lifecycle.lockParticipant(it)
        }
        val locked = lifecycle.lockOrder(order.id) ?: fail(AdminCaseProblem.INVALID)
        if (locked.parties != order.parties) fail(AdminCaseProblem.INVALID)
        val case = store.lock(caseId) ?: fail(AdminCaseProblem.INVALID)
        if (case.orderId != order.id) fail(AdminCaseProblem.INVALID)
        if (optimisticTelegram) {
            // Legacy URL updates CAS the version read before waiting; do not silently
            // overwrite a competing URL or turn an observed-OPEN CAS loss into replay409.
            observed.requireOpen()
            if (case.version != observed.version || case.state != "OPEN") fail(AdminCaseProblem.INVALID_TRANSITION)
        } else case.requireOpen()
        return case to locked
    }

    private fun currentAdmin(actor: Actor?): Long {
        val id = adminId(actor)
        rejectAgent(id)
        transaction.requireWritableJpa()
        admins.lockActiveAdmin(id)
        return id
    }
    private fun adminId(actor: Actor?): Long {
        if (actor?.tokenType != TokenType.ACCESS || actor.role != "ADMIN") fail(AdminCaseProblem.ACCESS_DENIED)
        return actor.participantId?.takeIf { it > 0 } ?: fail(AdminCaseProblem.INVALID_ACTOR)
    }
    private fun rejectAgent(id: Long) { if (lifecycle.isAgent(id)) fail(AdminCaseProblem.AGENT_ACCOUNT) }
    private fun fail(problem: AdminCaseProblem): Nothing = throw AdminCaseFailure(problem)
}
