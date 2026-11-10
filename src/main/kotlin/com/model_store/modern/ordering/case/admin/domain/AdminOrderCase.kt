package com.model_store.modern.ordering.`case`.admin.domain

import java.net.URI

enum class CaseState { OPEN, RESOLVED }
enum class CaseOutcome { BUYER, SELLER, CANCELLED, REJECTED, LEGACY }
enum class CaseResolutionTarget { FAILED, COMPLETED, CANCELLED }
enum class AdminCaseProblem { INVALID, INVALID_TRANSITION, INVALID_OUTCOME, ALREADY_RESOLVED,
    COMMENT_REQUIRED, INVALID_URL, INVALID_PAGE, ACCESS_DENIED, INVALID_ACTOR, AGENT_ACCOUNT, STOCK_CONFLICT }
class AdminCaseFailure(val problem: AdminCaseProblem) : RuntimeException(problem.name)

data class AdminCase(val id: Long, val orderId: Long, val version: Long, val kind: String,
                     val state: String, val previousStatus: String?) {
    fun requireOpen() {
        if (state != "OPEN") throw AdminCaseFailure(AdminCaseProblem.ALREADY_RESOLVED)
    }

    /** Null target is the legacy rejection: close case without changing order or stock. */
    fun target(outcome: CaseOutcome?, currentStatus: String): CaseResolutionTarget? {
        fun invalid(): Nothing = throw AdminCaseFailure(AdminCaseProblem.INVALID_OUTCOME)
        fun expected(status: String?): Unit {
            if (currentStatus != status) throw AdminCaseFailure(AdminCaseProblem.INVALID_TRANSITION)
        }
        return when (kind) {
            "DISPUTE" -> {
                if (outcome !in setOf(CaseOutcome.BUYER, CaseOutcome.SELLER) ||
                    outcome == CaseOutcome.SELLER && previousStatus != "ON_THE_WAY") invalid()
                expected("DISPUTED")
                if (outcome == CaseOutcome.BUYER) CaseResolutionTarget.FAILED else CaseResolutionTarget.COMPLETED
            }
            "PAYMENT_APPEAL" -> {
                if (outcome !in setOf(CaseOutcome.BUYER, CaseOutcome.SELLER)) invalid()
                expected("DISPUTED")
                CaseResolutionTarget.CANCELLED
            }
            "CANCELLATION_REQUEST" -> {
                when (outcome) {
                    CaseOutcome.REJECTED -> null
                    CaseOutcome.CANCELLED -> { expected(previousStatus); CaseResolutionTarget.CANCELLED }
                    else -> invalid()
                }
            }
            else -> invalid()
        }
    }
}

fun requireResolutionComment(comment: String?) {
    if (comment == null || comment.codePoints().allMatch { Character.isWhitespace(it) })
        throw AdminCaseFailure(AdminCaseProblem.COMMENT_REQUIRED)
}

fun requireCaseTelegramUrl(url: String?) {
    val valid = try {
        val uri = URI.create(url)
        url != null && url.length <= 2048 && "https".equals(uri.scheme, true) &&
            uri.host in setOf("t.me", "telegram.me") && uri.userInfo == null &&
            uri.path != null && !uri.path.codePoints().allMatch { Character.isWhitespace(it) } && uri.path != "/"
    } catch (_: RuntimeException) { false }
    if (!valid) throw AdminCaseFailure(AdminCaseProblem.INVALID_URL)
}
