package com.model_store.modern.ordering.`case`.admin.api

import com.model_store.modern.ordering.`case`.admin.application.AdminOrderCases
import com.model_store.modern.ordering.`case`.admin.domain.*
import com.model_store.modern.ordering.`case`.user.api.CaseDetailResponse
import com.model_store.modern.shared.domain.Actor
import org.springframework.context.annotation.Profile
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import java.time.Instant

data class AdminCaseSummaryResponse(val caseId: Long, val orderId: Long, val kind: String, val state: String,
    val openedBy: Long?, val openingComment: String?, val createdAt: Instant?, val orderStatus: String)

@RestController
@Profile("modern")
@RequestMapping("/admin/actions/order-cases")
class AdminOrderCaseController(private val cases: AdminOrderCases) {
    @ModelAttribute
    fun authorize(@AuthenticationPrincipal actor: Actor?) = cases.authorize(actor)

    @GetMapping
    fun queue(@AuthenticationPrincipal actor: Actor?, @RequestParam(defaultValue = "OPEN") state: CaseState,
        @RequestParam(defaultValue = "50") limit: Int, @RequestParam(defaultValue = "0") offset: Long) =
        cases.queue(actor, state, limit, offset).map {
            AdminCaseSummaryResponse(it.caseId, it.orderId, it.kind, it.state, it.openedBy, it.openingComment, it.createdAt, it.orderStatus)
        }

    @GetMapping("/{caseId}")
    fun detail(@AuthenticationPrincipal actor: Actor?, @PathVariable caseId: Long) = CaseDetailResponse.from(cases.detail(actor, caseId))

    @PutMapping("/{caseId}/telegram-url")
    fun telegram(@AuthenticationPrincipal actor: Actor?, @PathVariable caseId: Long, @RequestParam url: String) =
        cases.telegram(actor, caseId, url)

    @PostMapping("/{caseId}/resolve")
    fun resolve(@AuthenticationPrincipal actor: Actor?, @PathVariable caseId: Long,
        @RequestParam outcome: CaseOutcome, @RequestParam comment: String) = cases.resolve(actor, caseId, outcome, comment)
}
