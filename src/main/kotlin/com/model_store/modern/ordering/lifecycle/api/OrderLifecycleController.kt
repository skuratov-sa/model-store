package com.model_store.modern.ordering.lifecycle.api

import com.model_store.modern.ordering.lifecycle.application.*
import com.model_store.modern.ordering.lifecycle.domain.OrderAction
import com.model_store.modern.shared.domain.Actor
import org.springframework.context.annotation.Profile
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RestController
@Profile("modern")
@RequestMapping("/order")
class OrderLifecycleController(private val orders: OrderLifecycle) {
    @GetMapping("/seller")
    fun seller(@AuthenticationPrincipal actor: Actor?) = orders.list(actor, true).map(OrderResponse::from)
    @GetMapping("/customer")
    fun customer(@AuthenticationPrincipal actor: Actor?) = orders.list(actor, false).map(OrderResponse::from)

    @PostMapping("/{orderId}/AWAITING_PREPAYMENT")
    fun agree(@AuthenticationPrincipal actor: Actor?, @PathVariable orderId: Long, @RequestParam(required = false) comment: String?) =
        orders.execute(actor, orderId, OrderAction.AGREE, comment)
    @PostMapping("/{orderId}/AWAITING_PREPAYMENT_APPROVAL")
    fun prepay(@AuthenticationPrincipal actor: Actor?, @PathVariable orderId: Long, @RequestParam(required = false) comment: String?,
               @RequestParam(required = false) imageId: Long?) = orders.execute(actor, orderId, OrderAction.PREPAY, comment, imageId)
    @PostMapping("/{orderId}/AWAITING_PAYMENT")
    fun approve(@AuthenticationPrincipal actor: Actor?, @PathVariable orderId: Long, @RequestParam(required = false) comment: String?) =
        orders.execute(actor, orderId, OrderAction.APPROVE_PREPAYMENT, comment)
    @PostMapping("/{orderId}/ASSEMBLING")
    fun pay(@AuthenticationPrincipal actor: Actor?, @PathVariable orderId: Long, @RequestParam(required = false) comment: String?,
            @RequestParam(required = false) imageId: Long?) = orders.execute(actor, orderId, OrderAction.PAY, comment, imageId)
    @PostMapping("/{orderId}/ON_THE_WAY")
    fun ship(@AuthenticationPrincipal actor: Actor?, @PathVariable orderId: Long, @RequestParam(required = false) comment: String?,
             @RequestParam deliveryUrl: String) = orders.execute(actor, orderId, OrderAction.SHIP, comment, deliveryUrl = deliveryUrl)
    @PostMapping("/{orderId}/COMPLETED")
    fun complete(@AuthenticationPrincipal actor: Actor?, @PathVariable orderId: Long, @RequestParam(required = false) comment: String?) =
        orders.execute(actor, orderId, OrderAction.COMPLETE, comment)
    @PostMapping("/{orderId}/CANCELLED")
    fun cancel(@AuthenticationPrincipal actor: Actor?, @PathVariable orderId: Long, @RequestBody body: CloseOrderBody) =
        orders.execute(actor, orderId, OrderAction.CANCEL, body.comment)
}
