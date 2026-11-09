package com.model_store.modern.ordering.`case`.user.application

import com.model_store.modern.ordering.`case`.user.domain.*
import java.time.Instant

/** All mutations join the caller's writable JPA transaction after participant -> order locks. */
interface UserOrderCaseStore {
    fun order(id: Long): CaseOrder?
    fun hasOpen(orderId: Long): Boolean
    fun hasAppeal(orderId: Long): Boolean
    fun markDisputed(order: CaseOrder, comment: String): Boolean
    fun create(order: CaseOrder, kind: CaseKind, actor: Long, comment: String): Long
    fun attach(caseId: Long, imageId: Long, actor: Long)
    fun cases(order: CaseOrder): List<CaseDetailView>
}

data class CaseView(val id: Long, val version: Long, val orderId: Long, val kind: String, val state: String,
                    val openedBy: Long?, val openingComment: String?, val previousOrderStatus: String?,
                    val telegramUrl: String?, val resolvedBy: Long?, val outcome: String?, val resolutionComment: String?,
                    val createdAt: Instant?, val resolvedAt: Instant?)
data class CaseDetailView(val orderCase: CaseView, val order: CaseOrder, val orderImages: List<Long>, val caseImages: List<Long>)
