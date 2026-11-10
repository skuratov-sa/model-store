package com.model_store.modern.ordering.`case`.admin

import com.model_store.modern.ordering.`case`.admin.domain.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AdminOrderCaseTest {
    @Test
    fun `resolution target matrix preserves exact legacy precedence`() {
        val states=listOf("BOOKED","AWAITING_PREPAYMENT","AWAITING_PREPAYMENT_APPROVAL","AWAITING_PAYMENT",
            "ASSEMBLING","ON_THE_WAY","COMPLETED","CANCELLED","DISPUTED","FAILED")
        for (kind in listOf("DISPUTE", "PAYMENT_APPEAL", "CANCELLATION_REQUEST", "unknown"))
            for (previous in listOf(null) + states)
                for (status in states) for (outcome in CaseOutcome.entries + listOf(null)) {
                    val case = AdminCase(1, 2, 0, kind, "OPEN", previous)
                    val target = when {
                        kind == "DISPUTE" && outcome == CaseOutcome.BUYER -> "FAILED"
                        kind == "DISPUTE" && outcome == CaseOutcome.SELLER && previous == "ON_THE_WAY" -> "COMPLETED"
                        kind == "PAYMENT_APPEAL" && outcome in setOf(CaseOutcome.BUYER, CaseOutcome.SELLER) -> "CANCELLED"
                        kind == "CANCELLATION_REQUEST" && outcome == CaseOutcome.CANCELLED -> "CANCELLED"
                        else -> null
                    }
                    val rejected=kind == "CANCELLATION_REQUEST" && outcome == CaseOutcome.REJECTED
                    val expectedStatus=if(kind == "CANCELLATION_REQUEST") previous else "DISPUTED"
                    val problem=when {
                        target==null && !rejected -> AdminCaseProblem.INVALID_OUTCOME
                        !rejected && status!=expectedStatus -> AdminCaseProblem.INVALID_TRANSITION
                        else -> null
                    }
                    if(problem==null) assertEquals(target, case.target(outcome, status)?.name)
                    else assertEquals(problem,assertThrows(AdminCaseFailure::class.java) { case.target(outcome, status) }.problem,
                        "$kind previous=$previous current=$status outcome=$outcome")
                }
        val case = AdminCase(1, 2, 0, "DISPUTE", "OPEN", "ASSEMBLING")
        assertEquals(AdminCaseProblem.INVALID_OUTCOME,
            assertThrows(AdminCaseFailure::class.java) { case.target(CaseOutcome.SELLER, "COMPLETED") }.problem)
        assertEquals(AdminCaseProblem.INVALID_TRANSITION,
            assertThrows(AdminCaseFailure::class.java) { case.target(CaseOutcome.BUYER, "COMPLETED") }.problem)
        assertEquals(AdminCaseProblem.ALREADY_RESOLVED,
            assertThrows(AdminCaseFailure::class.java) { case.copy(state="RESOLVED").requireOpen() }.problem)
    }

    @Test
    fun `URL and comment match Java unicode and host behavior`() {
        for (value in listOf(null, "", " \t", "\u2003"))
            assertEquals(AdminCaseProblem.COMMENT_REQUIRED,assertThrows(AdminCaseFailure::class.java) { requireResolutionComment(value) }.problem)
        requireResolutionComment("\u00a0")
        for (url in listOf("https://t.me/x", "HTTPS://telegram.me/x?y=z#f", "https://t.me:443/x", "https://t.me/" + "x".repeat(2035)))
            requireCaseTelegramUrl(url)
        for (url in listOf(null, "", "http://t.me/x", "https://T.ME/x", "https://t.me/", "https://t.me", "https://u@t.me/x",
            "https://t.me.evil/x", " https://t.me/x", "https://t.me/" + "x".repeat(2048)))
            assertEquals(AdminCaseProblem.INVALID_URL,assertThrows(AdminCaseFailure::class.java) { requireCaseTelegramUrl(url) }.problem)
    }
}
