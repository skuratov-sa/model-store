package com.model_store.modern.seller.agent.order.api

import com.model_store.modern.ordering.lifecycle.api.OrderResponse
import com.model_store.modern.ordering.lifecycle.domain.OrderStatus
import com.model_store.modern.seller.agent.order.application.AgentOrderActions
import com.model_store.modern.shared.domain.Actor
import org.springframework.context.annotation.Profile
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RestController
@Profile("modern")
@RequestMapping("/admin/actions")
class AgentOrderController(private val actions: AgentOrderActions) {
    @ModelAttribute
    fun authorize(@AuthenticationPrincipal actor: Actor?) = actions.authorize(actor)

    @GetMapping("/agent-orders")
    fun all(@AuthenticationPrincipal actor: Actor?, @RequestParam(required = false) status: OrderStatus?,
        @RequestParam(defaultValue = "0") page: Int, @RequestParam(defaultValue = "50") size: Int) =
        actions.queue(actor, null, status?.name, page, size).map { OrderResponse.from(it.order).copy(sellerLogin = it.sellerLogin) }

    @GetMapping("/agents/{agentId}/orders")
    fun agent(@AuthenticationPrincipal actor: Actor?, @PathVariable agentId: Long,
        @RequestParam(required = false) status: OrderStatus?, @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "50") size: Int) =
        actions.queue(actor, agentId, status?.name, page, size).map { OrderResponse.from(it.order).copy(sellerLogin = it.sellerLogin) }

    @PostMapping("/agents/{agentId}/orders/{orderId}/{action}")
    fun execute(@AuthenticationPrincipal actor: Actor?, @PathVariable agentId: Long, @PathVariable orderId: Long,
        @PathVariable action: String, @RequestParam(required = false) comment: String?,
        @RequestParam(required = false) deliveryUrl: String?) = actions.execute(actor, agentId, orderId, action, comment, deliveryUrl)
}
