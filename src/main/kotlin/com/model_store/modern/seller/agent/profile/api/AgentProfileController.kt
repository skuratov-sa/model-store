package com.model_store.modern.seller.agent.profile.api

import com.model_store.modern.seller.agent.profile.application.AgentProfileAccess
import com.model_store.modern.seller.agent.profile.application.AgentProfileWrites
import com.model_store.modern.identity.participant.application.AgentProfileCommands
import com.model_store.modern.identity.participant.application.AgentProfilePatch
import com.model_store.modern.identity.participant.application.AgentProfileSnapshot
import com.model_store.modern.seller.address.application.AddressUseCases
import com.model_store.modern.seller.address.domain.AddressFields
import com.model_store.modern.seller.account.application.AccountUseCases
import com.model_store.modern.seller.account.domain.AccountDetails
import com.model_store.modern.seller.account.domain.TransferMoney
import com.model_store.modern.seller.transfer.application.TransferUseCases
import com.model_store.modern.seller.transfer.domain.TransferCurrency
import com.model_store.modern.seller.transfer.domain.TransferFields
import com.model_store.modern.seller.transfer.domain.ShippingMethod
import com.model_store.modern.seller.social.application.SocialNetworkUseCases
import com.model_store.modern.seller.social.domain.SocialNetworkFields
import com.model_store.modern.seller.social.domain.SocialNetworkType
import com.model_store.modern.shared.domain.Actor
import org.springframework.context.annotation.Profile
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

data class AgentAddressRequest(val country: String? = null, val city: String? = null, val street: String? = null,
    val houseNumber: String? = null, val apartmentNumber: String? = null, val index: Int? = null) {
    fun fields() = AddressFields(country, city, street, houseNumber, apartmentNumber, index)
}
data class AgentAccountRequest(val transferMoney: TransferMoney? = null, val username: String? = null,
    val entityValue: String? = null, val comment: String? = null) {
    fun details() = AccountDetails(transferMoney, username, entityValue, comment)
}
data class AgentTransferRequest(val sending: ShippingMethod? = null, val price: Int? = null,
    val currency: TransferCurrency? = null) {
    fun fields() = TransferFields(sending, price, currency)
}
data class AgentSocialRequest(val type: SocialNetworkType? = null, val login: String? = null) {
    fun fields() = SocialNetworkFields(type, login)
}
data class AgentProfileRequest(val fullName: String? = null, val phoneNumber: String? = null,
    val deadlineSending: Int? = null, val deadlinePayment: Int? = null, val imageId: Long? = null) {
    fun patch() = AgentProfilePatch(fullName, phoneNumber, deadlineSending, deadlinePayment, imageId)
}
data class AgentProfileResponse(val id: Long, val login: String?, val fullName: String?, val phoneNumber: String?,
    val imageId: Long?, val deadlineSending: Int, val deadlinePayment: Int, val status: String) {
    constructor(snapshot: AgentProfileSnapshot) : this(snapshot.id, snapshot.login, snapshot.fullName,
        snapshot.phoneNumber, snapshot.imageId, snapshot.deadlineSending, snapshot.deadlinePayment, snapshot.status)
}
data class AgentAddressResponse(val id: Long, val country: String?, val city: String?, val street: String?,
    val houseNumber: String?, val apartmentNumber: String?, val index: Int?, val status: String, val fullAddress: String)
data class AgentAccountResponse(val id: Long, val transferMoney: TransferMoney, val username: String?,
    val entityValue: String?, val comment: String?, val participantId: Long)
data class AgentTransferResponse(val id: Long, val sending: ShippingMethod, val price: Int,
    val currency: TransferCurrency, val participantId: Long, val status: String)
data class AgentSocialResponse(val id: Long, val type: SocialNetworkType, val login: String?, val participantId: Long)

@RestController
@Profile("modern")
@RequestMapping("/admin/actions/agents/{agentId}")
class AgentProfileController(
    private val access: AgentProfileAccess,
    private val profiles: AgentProfileCommands,
    private val addresses: AddressUseCases,
    private val accounts: AccountUseCases,
    private val transfers: TransferUseCases,
    private val social: SocialNetworkUseCases,
    private val writes: AgentProfileWrites,
) {
    @GetMapping("/profile")
    fun profile(@AuthenticationPrincipal actor: Actor?, @PathVariable agentId: Long) =
        AgentProfileResponse(profiles.read(access.requireAdminActor(actor), agentId))

    @PutMapping("/profile")
    fun updateProfile(@AuthenticationPrincipal actor: Actor?, @PathVariable agentId: Long,
        @RequestBody request: AgentProfileRequest) =
        AgentProfileResponse(profiles.update(access.requireAdminActor(actor), agentId, request.patch()))

    @GetMapping("/addresses")
    fun addresses(@AuthenticationPrincipal actor: Actor?, @PathVariable agentId: Long) =
        addresses.owned(access.requireTarget(actor, agentId)).map { address ->
            AgentAddressResponse(address.id, address.country, address.city, address.street,
                address.houseNumber, address.apartmentNumber, address.index, address.status.name, address.fullAddress)
        }

    @PostMapping("/addresses")
    fun createAddress(@AuthenticationPrincipal actor: Actor?, @PathVariable agentId: Long,
        @RequestBody request: AgentAddressRequest) = writes.createAddress(actor, agentId, request.fields())

    @PutMapping("/addresses/{id}")
    fun updateAddress(@AuthenticationPrincipal actor: Actor?, @PathVariable agentId: Long, @PathVariable id: Long,
        @RequestBody request: AgentAddressRequest) = writes.updateAddress(actor, agentId, id, request.fields()).let { address ->
        AgentAddressResponse(address.id, address.country, address.city, address.street,
            address.houseNumber, address.apartmentNumber, address.index, address.status.name, address.fullAddress)
    }

    @DeleteMapping("/addresses/{id}")
    fun deleteAddress(@AuthenticationPrincipal actor: Actor?, @PathVariable agentId: Long, @PathVariable id: Long) =
        writes.deleteAddress(actor, agentId, id)

    @GetMapping("/accounts")
    fun accounts(@AuthenticationPrincipal actor: Actor?, @PathVariable agentId: Long) =
        accounts.list(access.requireTarget(actor, agentId)).map { account ->
            AgentAccountResponse(account.id, account.transferMoney, account.username, account.entityValue,
                account.comment, account.participantId)
        }

    @PostMapping("/accounts")
    fun createAccount(@AuthenticationPrincipal actor: Actor?, @PathVariable agentId: Long,
        @RequestBody request: AgentAccountRequest) = writes.createAccount(actor, agentId, request.details())

    @PutMapping("/accounts/{id}")
    fun updateAccount(@AuthenticationPrincipal actor: Actor?, @PathVariable agentId: Long, @PathVariable id: Long,
        @RequestBody request: AgentAccountRequest) = writes.updateAccount(actor, agentId, id, request.details()).let { account ->
        AgentAccountResponse(account.id, account.transferMoney, account.username, account.entityValue,
            account.comment, account.participantId)
    }

    @DeleteMapping("/accounts/{id}")
    fun deleteAccount(@AuthenticationPrincipal actor: Actor?, @PathVariable agentId: Long, @PathVariable id: Long) =
        writes.deleteAccount(actor, agentId, id)

    @GetMapping("/transfers")
    fun transfers(@AuthenticationPrincipal actor: Actor?, @PathVariable agentId: Long) =
        transfers.owned(access.requireTarget(actor, agentId)).map { transfer ->
            AgentTransferResponse(transfer.id, transfer.sending, transfer.price,
                transfer.currency, transfer.participantId, transfer.status.name)
        }

    @PostMapping("/transfers")
    fun createTransfer(@AuthenticationPrincipal actor: Actor?, @PathVariable agentId: Long,
        @RequestBody request: AgentTransferRequest) = writes.createTransfer(actor, agentId, request.fields())

    @PutMapping("/transfers/{id}")
    fun updateTransfer(@AuthenticationPrincipal actor: Actor?, @PathVariable agentId: Long, @PathVariable id: Long,
        @RequestBody request: AgentTransferRequest) = writes.updateTransfer(actor, agentId, id, request.fields()).let { transfer ->
        AgentTransferResponse(transfer.id, transfer.sending, transfer.price,
            transfer.currency, transfer.participantId, transfer.status.name)
    }

    @DeleteMapping("/transfers/{id}")
    fun deleteTransfer(@AuthenticationPrincipal actor: Actor?, @PathVariable agentId: Long, @PathVariable id: Long) =
        writes.deleteTransfer(actor, agentId, id)

    @GetMapping("/social-networks")
    fun socialNetworks(@AuthenticationPrincipal actor: Actor?, @PathVariable agentId: Long) =
        social.owned(access.requireTarget(actor, agentId)).map { network ->
            AgentSocialResponse(network.id, network.type, network.login, network.participantId)
        }

    @PostMapping("/social-networks")
    fun createSocialNetwork(@AuthenticationPrincipal actor: Actor?, @PathVariable agentId: Long,
        @RequestBody request: AgentSocialRequest) = writes.createSocialNetwork(actor, agentId, request.fields())

    @PutMapping("/social-networks/{id}")
    fun updateSocialNetwork(@AuthenticationPrincipal actor: Actor?, @PathVariable agentId: Long, @PathVariable id: Long,
        @RequestBody request: AgentSocialRequest) = writes.updateSocialNetwork(actor, agentId, id, request.fields()).let { network ->
        AgentSocialResponse(network.id, network.type, network.login, network.participantId)
    }

    @DeleteMapping("/social-networks/{id}")
    fun deleteSocialNetwork(@AuthenticationPrincipal actor: Actor?, @PathVariable agentId: Long, @PathVariable id: Long) =
        writes.deleteSocialNetwork(actor, agentId, id)
}
