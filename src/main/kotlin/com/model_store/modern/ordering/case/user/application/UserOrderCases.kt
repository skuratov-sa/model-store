package com.model_store.modern.ordering.`case`.user.application

import com.model_store.modern.ordering.`case`.user.domain.*
import com.model_store.modern.ordering.lifecycle.application.LifecycleStore
import com.model_store.modern.ordering.lifecycle.application.LifecycleTransaction
import com.model_store.modern.media.image.command.application.*
import com.model_store.modern.shared.domain.Actor
import com.model_store.modern.shared.domain.TokenType
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Profile("modern")
class UserOrderCases(private val store: UserOrderCaseStore, private val locks: LifecycleStore,
                     private val transaction: LifecycleTransaction, private val evidence: OrderImageEvidence) {
    @Transactional(transactionManager = "transactionManager", readOnly = true)
    fun authorize(actor: Actor?) { rejectAgent(actorId(actor)) }

    @Transactional(transactionManager = "transactionManager")
    fun open(actor: Actor?, orderId: Long, kind: CaseKind, comment: String?, imageIds: List<Long?>? = null): Long {
        val actorId = actorId(actor)
        rejectAgent(actorId)
        val trimmed = requireCaseComment(comment)
        transaction.requireWritableJpa()
        val observed = store.order(orderId) ?: fail(CaseProblem.INVALID)
        observed.requireParty(actorId, kind == CaseKind.PAYMENT_APPEAL)
        listOf(observed.buyerId, observed.sellerId).distinct().sorted().forEach(locks::lockParticipant)
        rejectAgent(actorId)
        val locked = locks.lockOrder(orderId) ?: fail(CaseProblem.INVALID)
        if (locked.parties.customerId != observed.buyerId || locked.parties.sellerId != observed.sellerId) fail(CaseProblem.INVALID)
        val order = store.order(orderId) ?: fail(CaseProblem.INVALID)
        order.requireParty(actorId, kind == CaseKind.PAYMENT_APPEAL)
        // Appeal uniqueness precedes status and OPEN checks in the actual service.
        if (kind == CaseKind.PAYMENT_APPEAL) {
            if (store.hasAppeal(orderId)) fail(CaseProblem.APPEAL_EXISTS)
            order.requireOpening(kind)
        }
        if (store.hasOpen(orderId)) fail(CaseProblem.ALREADY_OPEN)
        order.requireOpening(kind)
        if (kind != CaseKind.CANCELLATION_REQUEST && !store.markDisputed(order, comment!!)) fail(CaseProblem.INVALID_TRANSITION)
        val id = store.create(order, kind, actorId, trimmed)
        if (kind != CaseKind.CANCELLATION_REQUEST) {
            try { evidence.activate(orderId, actorId, imageIds) }
            catch (failure: OrderImageEvidenceFailure) {
                when (failure.problem) {
                    OrderImageEvidenceProblem.TRANSACTION_REQUIRED -> throw failure
                    OrderImageEvidenceProblem.IMAGE_BUSY -> fail(CaseProblem.IMAGE_BUSY)
                    else -> fail(CaseProblem.INVALID)
                }
            }
            imageIds.orEmpty().forEach { store.attach(id, it ?: fail(CaseProblem.INVALID), actorId) }
        }
        return id
    }

    @Transactional(transactionManager = "transactionManager", readOnly = true)
    fun list(actor: Actor?, orderId: Long): List<CaseDetailView> {
        val id = actorId(actor)
        rejectAgent(id)
        val order = store.order(orderId) ?: fail(CaseProblem.INVALID)
        order.requireParty(id)
        return store.cases(order)
    }

    private fun actorId(actor: Actor?): Long {
        if (actor?.tokenType != TokenType.ACCESS || actor.role !in setOf("USER", "ADMIN")) fail(CaseProblem.ACCESS_DENIED)
        return actor.participantId?.takeIf { it > 0 } ?: fail(CaseProblem.INVALID_ACTOR)
    }
    // Legacy AgentAccountAccessFilter denies a bot actor before reaching all four case routes.
    // A human party still has access to an order whose counterparty is a bot.
    private fun rejectAgent(id: Long) { if (locks.isAgent(id)) fail(CaseProblem.AGENT_ACCOUNT) }
    private fun fail(problem: CaseProblem): Nothing = throw CaseFailure(problem)
}
