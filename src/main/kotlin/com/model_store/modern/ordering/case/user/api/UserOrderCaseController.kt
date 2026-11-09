package com.model_store.modern.ordering.`case`.user.api

import com.model_store.modern.ordering.`case`.user.application.UserOrderCases
import com.model_store.modern.ordering.`case`.user.domain.CaseKind
import com.model_store.modern.shared.domain.Actor
import org.springframework.context.annotation.Profile
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RestController
@Profile("modern")
@RequestMapping("/order")
class UserOrderCaseController(private val cases: UserOrderCases) {
    // Run before argument binding, matching the legacy filter even for a missing comment.
    @ModelAttribute
    fun authorize(@AuthenticationPrincipal actor: Actor?) = cases.authorize(actor)

    @PostMapping("/orders/{orderId}/DISPUTED")
    fun dispute(@AuthenticationPrincipal actor: Actor?, @PathVariable orderId: Long, @RequestParam comment: String,
                @RequestParam(required = false) imageIds: List<Long?>?) = cases.open(actor,orderId,CaseKind.DISPUTE,comment,imageIds)
    @PostMapping("/{orderId}/cancellation-request")
    fun cancellation(@AuthenticationPrincipal actor: Actor?, @PathVariable orderId: Long, @RequestParam comment: String) =
        cases.open(actor,orderId,CaseKind.CANCELLATION_REQUEST,comment)
    @PostMapping("/{orderId}/payment-appeal")
    fun appeal(@AuthenticationPrincipal actor: Actor?, @PathVariable orderId: Long, @RequestParam comment: String,
               @RequestParam(required = false) imageIds: List<Long?>?) = cases.open(actor,orderId,CaseKind.PAYMENT_APPEAL,comment,imageIds)
    @GetMapping("/{orderId}/cases")
    fun list(@AuthenticationPrincipal actor: Actor?, @PathVariable orderId: Long) = cases.list(actor,orderId).map(CaseDetailResponse::from)
}
