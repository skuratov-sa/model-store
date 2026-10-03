package com.model_store.modern.seller.address.api

import com.model_store.modern.seller.address.application.AddressUseCases
import com.model_store.modern.seller.address.domain.AddressAccessDenied
import com.model_store.modern.seller.address.domain.AddressFields
import com.model_store.modern.seller.address.domain.DeliveryAddress
import com.model_store.modern.shared.domain.Actor
import org.springframework.context.annotation.Profile
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

data class AddressRequest(
    val country: String? = null,
    val city: String? = null,
    val street: String? = null,
    val houseNumber: String? = null,
    val apartmentNumber: String? = null,
    val index: Int? = null,
) {
    fun fields() = AddressFields(country, city, street, houseNumber, apartmentNumber, index)
}

data class AddressResponse(
    val id: Long, val country: String?, val city: String?, val street: String?,
    val houseNumber: String?, val apartmentNumber: String?, val index: Int?,
    val status: String, val fullAddress: String,
) {
    constructor(address: DeliveryAddress) : this(
        address.id, address.country, address.city, address.street, address.houseNumber,
        address.apartmentNumber, address.index, address.status.name, address.fullAddress,
    )
}

@RestController
@RequestMapping("/address")
@Profile("modern")
class AddressController(private val cases: AddressUseCases) {
    @GetMapping("/regions")
    fun regions(): List<String?> = cases.regions()

    @GetMapping
    fun owned(@AuthenticationPrincipal actor: Actor?): List<AddressResponse> =
        cases.owned(actor.requireId()).map(::AddressResponse)

    @PostMapping
    fun create(@AuthenticationPrincipal actor: Actor?, @RequestBody request: AddressRequest): Long =
        cases.create(actor.requireId(), request.fields())

    @PutMapping("/{addressId}")
    fun update(@AuthenticationPrincipal actor: Actor?, @PathVariable addressId: Long,
               @RequestBody request: AddressRequest): AddressResponse =
        AddressResponse(cases.update(actor.requireId(), addressId, request.fields()))

    @DeleteMapping("/{addressId}")
    fun delete(@AuthenticationPrincipal actor: Actor?, @PathVariable addressId: Long) =
        cases.delete(actor.requireId(), addressId)

    private fun Actor?.requireId(): Long = this?.participantId ?: throw AddressAccessDenied()
}
