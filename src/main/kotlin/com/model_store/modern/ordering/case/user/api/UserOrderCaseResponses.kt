package com.model_store.modern.ordering.`case`.user.api

import com.model_store.modern.ordering.`case`.user.application.CaseDetailView
import java.time.Instant

data class UserCaseResponse(val id: Long, val version: Long, val orderId: Long, val kind: String, val state: String,
    val openedBy: Long?, val openingComment: String?, val previousOrderStatus: String?, val telegramUrl: String?,
    val resolvedBy: Long?, val outcome: String?, val resolutionComment: String?, val createdAt: Instant?, val resolvedAt: Instant?)
data class CaseDetailResponse(val orderCase: UserCaseResponse, val orderStatus: String, val buyerId: Long, val sellerId: Long,
    val productId: Long?, val count: Int?, val totalPrice: Float?, val prepaymentAmount: Float?, val currency: String?,
    val paymentProofImageId: Long?, val orderImages: List<Long>, val caseImages: List<Long>) {
    companion object {
        fun from(view: CaseDetailView): CaseDetailResponse {
            val c = view.orderCase
            val o = view.order
            return CaseDetailResponse(UserCaseResponse(c.id,c.version,c.orderId,c.kind,c.state,c.openedBy,c.openingComment,
                c.previousOrderStatus,c.telegramUrl,c.resolvedBy,c.outcome,c.resolutionComment,c.createdAt,c.resolvedAt),
                o.status,o.buyerId,o.sellerId,o.productId,o.count,o.totalPrice,o.prepaymentAmount,o.currency,o.paymentProofImageId,
                view.orderImages,view.caseImages)
        }
    }
}
