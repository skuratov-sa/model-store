package com.model_store.modern.seller.account.api

import com.model_store.modern.seller.account.application.AccountUseCases
import com.model_store.modern.seller.account.application.AccountSelfAccess
import com.model_store.modern.seller.account.domain.*
import com.model_store.modern.shared.domain.Actor
import org.springframework.context.annotation.Profile
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

data class AccountRequest(
    val transferMoney: TransferMoney? = null,
    val username: String? = null,
    val entityValue: String? = null,
    val comment: String? = null,
) {
    fun details() = AccountDetails(transferMoney, username, entityValue, comment)
}

@RestController
@Profile("modern")
@RequestMapping("/accounts")
class ModernAccountController(private val accounts: AccountUseCases, private val selfAccess: AccountSelfAccess) {
    @PostMapping
    fun create(@AuthenticationPrincipal actor: Actor?, @RequestBody request: AccountRequest) =
        accounts.create(selfAccess.requireRegularOwner(actor), request.details())

    @PutMapping("/{id}")
    fun update(@AuthenticationPrincipal actor: Actor?, @PathVariable id: Long, @RequestBody request: AccountRequest) =
        accounts.update(selfAccess.requireRegularOwner(actor), id, request.details())

    @GetMapping
    fun mine(@AuthenticationPrincipal actor: Actor?) = accounts.list(selfAccess.requireRegularOwner(actor))

    @GetMapping("/participant/{participantId}")
    fun byParticipant(@AuthenticationPrincipal actor: Actor?, @PathVariable participantId: Long): List<SellerAccount> {
        if (selfAccess.requireRegularOwner(actor) != participantId) throw AccountAccessDenied()
        return accounts.list(participantId)
    }

    @DeleteMapping("/{id}")
    fun delete(@AuthenticationPrincipal actor: Actor?, @PathVariable id: Long) =
        accounts.delete(selfAccess.requireRegularOwner(actor), id)
}
