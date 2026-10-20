package com.model_store.modern.ordering.create.api

import com.model_store.modern.ordering.create.application.CreateOrder
import com.model_store.modern.ordering.create.application.OrderPreparation
import com.model_store.modern.ordering.create.domain.OrderLine
import com.model_store.modern.shared.domain.Actor
import org.springframework.context.annotation.Profile
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RestController
@Profile("modern")
@RequestMapping("/order")
class OrderCreateController(private val orders: CreateOrder) {
    @GetMapping
    fun prepare(@AuthenticationPrincipal actor: Actor?, @RequestParam(required = false) productId: Long?): OrderPreparation =
        orders.prepare(actor, productId)

    @PostMapping("/BOOKED")
    fun book(@AuthenticationPrincipal actor: Actor?, @RequestBody requests: List<OrderLine>): List<Long> =
        orders.book(actor, requests)
}
