package com.model_store.modern.seller.transfer.api

import com.model_store.modern.seller.transfer.application.TransferUseCases
import com.model_store.modern.seller.transfer.domain.*
import com.model_store.modern.shared.domain.Actor
import org.springframework.context.annotation.Profile
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

data class TransferRequest(val sending: ShippingMethod? = null, val price: Int? = null, val currency: TransferCurrency? = null) {
    fun fields() = TransferFields(sending, price, currency)
}

data class TransferResponse(val id: Long, val sending: ShippingMethod, val price: Int,
                            val currency: TransferCurrency, val participantId: Long, val status: TransferStatus) {
    constructor(method: TransferMethod) : this(method.id, method.sending, method.price, method.currency, method.participantId, method.status)
}

@RestController
@RequestMapping("/transfer")
@Profile("modern")
class TransferController(private val cases: TransferUseCases) {
    @GetMapping
    fun owned(@AuthenticationPrincipal actor: Actor?): List<TransferResponse> = cases.owned(actor.owner()).map(::TransferResponse)

    @PostMapping
    fun create(@AuthenticationPrincipal actor: Actor?, @RequestBody request: TransferRequest) =
        cases.create(actor.owner(), request.fields())

    @PutMapping("/{id}")
    fun update(@AuthenticationPrincipal actor: Actor?, @PathVariable id: Long, @RequestBody request: TransferRequest): TransferResponse =
        TransferResponse(cases.update(actor.owner(), id, request.fields()))

    @DeleteMapping("/{id}")
    fun delete(@AuthenticationPrincipal actor: Actor?, @PathVariable id: Long) = cases.delete(actor.owner(), id)

    private fun Actor?.owner() = this?.participantId ?: throw TransferAccessDenied()
}
