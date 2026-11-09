package com.model_store.modern.seller.agent.product.api

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.model_store.modern.catalog.product.agent.application.*
import com.model_store.modern.seller.agent.product.application.AgentProductActions
import com.model_store.modern.shared.domain.Actor
import org.springframework.context.annotation.Profile
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import java.time.Instant

enum class AgentProductCurrency { USD, EUR, GBP, JPY, CNY, RUB, CREATE }
enum class AgentProductAvailability { PURCHASABLE, PREORDER, EXTERNAL_PRODUCT, GIVEAWAY }

@JsonIgnoreProperties(ignoreUnknown = true)
data class AgentProductCreateRequest(val name: String? = null, val description: String? = null,
    val price: Float? = null, val currency: AgentProductCurrency? = null, val originality: String? = null,
    val externalUrl: String? = null, val categoryIds: List<Long>? = null, val used: Boolean? = null,
    val imageIds: List<Long>? = null) {
    fun patch() = AgentProductPatch(name = name, description = description, price = price,
        currency = currency?.name, originality = originality, used = used, externalUrl = externalUrl,
        categoryIds = categoryIds, imageIds = imageIds)
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class AgentGiveawayRequest(val enabled: Boolean? = null, val telegramUrl: String? = null,
    val startAt: Instant? = null, val endAt: Instant? = null, val winnersCount: Int? = null,
    val rules: String? = null, val homeText: String? = null) {
    fun schedule() = AgentProductSchedule(enabled, telegramUrl, startAt, endAt, winnersCount, rules, homeText)
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class AgentProductUpdateRequest(val name: String? = null, val description: String? = null,
    val price: Float? = null, val prepaymentAmount: Float? = null, val categoryIds: List<Long>? = null,
    val count: Int? = null, val currency: AgentProductCurrency? = null, val originality: String? = null,
    val availability: AgentProductAvailability? = null, val used: Boolean? = null, val externalUrl: String? = null,
    val giveaway: AgentGiveawayRequest? = null, val imageIds: List<Long>? = null) {
    fun patch() = AgentProductPatch(name, description, price, prepaymentAmount, count, currency?.name,
        originality, availability?.name, used, externalUrl, categoryIds, imageIds, giveaway?.schedule())
}

data class AgentProductResponse(val id: Long, val name: String?, val description: String?,
    val price: Float, val prepaymentAmount: Float?, val count: Int?, val currency: String,
    val originality: String?, val participantId: Long, val status: String, val expirationDate: Instant,
    val availability: String, val used: Boolean?, val externalUrl: String?, val giveawayEnabled: Boolean,
    val giveawayTelegramUrl: String?, val giveawayStartAt: Instant?, val giveawayEndAt: Instant?,
    val giveawayWinnersCount: Int?, val giveawayRules: String?, val giveawayHomeText: String?, val createdAt: Instant?) {
    constructor(p: AgentProductSnapshot) : this(p.id, p.name, p.description, p.price, p.prepaymentAmount, p.count,
        p.currency, p.originality, p.participantId, p.status, p.expirationDate, p.availability, p.used, p.externalUrl,
        p.giveawayEnabled, p.giveawayTelegramUrl, p.giveawayStartAt, p.giveawayEndAt, p.giveawayWinnersCount,
        p.giveawayRules, p.giveawayHomeText, p.createdAt)
}

@RestController
@Profile("modern")
class AgentProductController(private val actions: AgentProductActions) {
    @PostMapping("/agent/products")
    fun create(@AuthenticationPrincipal actor: Actor?, @RequestBody request: AgentProductCreateRequest): Long =
        actions.create(actor, request.patch())

    @GetMapping("/admin/actions/agents/{agentId}/products")
    fun products(@AuthenticationPrincipal actor: Actor?, @PathVariable agentId: Long) =
        actions.products(actor, agentId).map(::AgentProductResponse)

    @GetMapping("/admin/actions/products/{productId}")
    fun product(@AuthenticationPrincipal actor: Actor?, @PathVariable productId: Long) =
        AgentProductResponse(actions.product(actor, productId))

    @PutMapping("/admin/actions/agents/{agentId}/products/{productId}")
    fun update(@AuthenticationPrincipal actor: Actor?, @PathVariable agentId: Long,
        @PathVariable productId: Long, @RequestBody request: AgentProductUpdateRequest) =
        actions.update(actor, agentId, productId, request.patch())

    @PostMapping("/admin/actions/agents/{agentId}/products/{productId}/extend")
    fun extend(@AuthenticationPrincipal actor: Actor?, @PathVariable agentId: Long, @PathVariable productId: Long) =
        actions.extend(actor, agentId, productId)
}
