package com.model_store.modern.ordering.`case`.user.domain

enum class CaseKind { DISPUTE, CANCELLATION_REQUEST, PAYMENT_APPEAL }
enum class CaseProblem { COMMENT_REQUIRED, INVALID, INVALID_TRANSITION, ALREADY_OPEN, APPEAL_EXISTS, ACCESS_DENIED, INVALID_ACTOR, IMAGE_BUSY, AGENT_ACCOUNT }
class CaseFailure(val problem: CaseProblem) : RuntimeException(problem.name)

data class CaseOrder(val id: Long, val buyerId: Long, val sellerId: Long, val status: String,
                     val productId: Long?, val count: Int?, val totalPrice: Float?, val prepaymentAmount: Float?,
                     val currency: String?, val paymentProofImageId: Long?) {
    fun requireParty(actor: Long, buyerOnly: Boolean = false) {
        if (actor != buyerId && (buyerOnly || actor != sellerId)) throw CaseFailure(CaseProblem.INVALID)
    }
    fun requireOpening(kind: CaseKind) {
        val allowed = when (kind) {
            CaseKind.DISPUTE -> status in setOf("AWAITING_PREPAYMENT_APPROVAL", "AWAITING_PAYMENT", "ASSEMBLING", "ON_THE_WAY") && paymentProofImageId != null
            CaseKind.CANCELLATION_REQUEST -> paymentProofImageId == null && status in setOf("AWAITING_PREPAYMENT", "AWAITING_PAYMENT") &&
                (prepaymentAmount == null || prepaymentAmount == 0f || status == "AWAITING_PREPAYMENT")
            CaseKind.PAYMENT_APPEAL -> status == "CANCELLED"
        }
        if (!allowed) throw CaseFailure(CaseProblem.INVALID_TRANSITION)
    }
}

fun requireCaseComment(comment: String?): String {
    if (comment == null || comment.codePoints().allMatch { Character.isWhitespace(it) }) throw CaseFailure(CaseProblem.COMMENT_REQUIRED)
    return comment.trim { it <= ' ' }
}
