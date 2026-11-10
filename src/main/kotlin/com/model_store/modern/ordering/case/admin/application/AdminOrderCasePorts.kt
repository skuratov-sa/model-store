package com.model_store.modern.ordering.`case`.admin.application

import com.model_store.modern.ordering.`case`.admin.domain.*
import com.model_store.modern.ordering.`case`.user.application.CaseDetailView
import java.time.Instant

data class AdminCaseSummary(val caseId: Long, val orderId: Long, val kind: String, val state: String,
    val openedBy: Long?, val openingComment: String?, val createdAt: Instant?, val orderStatus: String)

/** Ordering-owned writes. Caller holds all participants ASC -> order -> case in a writable JPA tx.
 * No separate transaction or history writes; the existing trigger records transitions.
 */
interface AdminOrderCaseStore {
    fun find(caseId: Long): AdminCase?
    fun lock(caseId: Long): AdminCase?
    fun queue(state: CaseState, limit: Int, offset: Long): List<AdminCaseSummary>
    fun detail(caseId: Long): CaseDetailView?
    fun telegram(case: AdminCase, url: String): Boolean
    fun transition(orderId: Long, expected: String, target: CaseResolutionTarget, comment: String): Boolean
    fun resolve(case: AdminCase, adminId: Long, outcome: CaseOutcome, comment: String): Boolean
}
