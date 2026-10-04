package com.model_store.modern.seller.social.api

import com.model_store.modern.seller.social.application.SocialNetworkUseCases
import com.model_store.modern.seller.social.domain.*
import com.model_store.modern.shared.domain.Actor
import org.springframework.context.annotation.Profile
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

data class SocialNetworkRequest(val type: SocialNetworkType? = null, val login: String? = null) {
    fun fields() = SocialNetworkFields(type, login)
}

data class SocialNetworkResponse(val id: Long, val type: SocialNetworkType, val login: String?, val participantId: Long) {
    constructor(network: SocialNetwork) : this(network.id, network.type, network.login, network.participantId)
}

@RestController
@RequestMapping("/social-networks")
@Profile("modern")
class SocialNetworkController(private val cases: SocialNetworkUseCases) {
    @GetMapping
    fun owned(@AuthenticationPrincipal actor: Actor?): List<SocialNetworkResponse> = cases.owned(actor.owner()).map(::SocialNetworkResponse)

    @PostMapping
    fun create(@AuthenticationPrincipal actor: Actor?, @RequestBody request: SocialNetworkRequest) =
        cases.create(actor.owner(), request.fields())

    @PutMapping("/{id}")
    fun update(@AuthenticationPrincipal actor: Actor?, @PathVariable id: Long, @RequestBody request: SocialNetworkRequest): SocialNetworkResponse =
        SocialNetworkResponse(cases.update(actor.owner(), id, request.fields()))

    @DeleteMapping("/{id}")
    fun delete(@AuthenticationPrincipal actor: Actor?, @PathVariable id: Long) = cases.delete(actor.owner(), id)

    private fun Actor?.owner() = this?.participantId ?: throw SocialNetworkAccessDenied()
}
