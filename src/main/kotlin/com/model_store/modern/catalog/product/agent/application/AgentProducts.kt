package com.model_store.modern.catalog.product.agent.application

import java.time.Instant

/** Attributes of the verified principal, never deserialized from a request body. */
data class AgentProductCaller(val id: Long, val role: String?, val agentAccess: Boolean)

data class AgentProductSchedule(val enabled: Boolean? = null, val telegramUrl: String? = null,
    val startAt: Instant? = null, val endAt: Instant? = null, val winnersCount: Int? = null,
    val rules: String? = null, val homeText: String? = null)

/** Public catalog boundary: IDs and scalar values only. */
data class AgentProductPatch(val name: String? = null, val description: String? = null,
    val price: Float? = null, val prepaymentAmount: Float? = null, val count: Int? = null,
    val currency: String? = null, val originality: String? = null, val availability: String? = null,
    val used: Boolean? = null, val externalUrl: String? = null, val categoryIds: List<Long>? = null,
    val imageIds: List<Long>? = null, val giveaway: AgentProductSchedule? = null)

data class AgentProductSnapshot(val id: Long, val name: String?, val description: String?,
    val price: Float, val prepaymentAmount: Float?, val count: Int?, val currency: String,
    val originality: String?, val participantId: Long, val status: String, val expirationDate: Instant,
    val availability: String, val used: Boolean?, val externalUrl: String?, val giveawayEnabled: Boolean,
    val giveawayTelegramUrl: String?, val giveawayStartAt: Instant?, val giveawayEndAt: Instant?,
    val giveawayWinnersCount: Int?, val giveawayRules: String?, val giveawayHomeText: String?,
    val createdAt: Instant?)

interface AgentProducts {
    fun create(caller: AgentProductCaller, patch: AgentProductPatch): Long
    fun products(caller: AgentProductCaller, agentId: Long): List<AgentProductSnapshot>
    fun product(caller: AgentProductCaller, productId: Long): AgentProductSnapshot
    fun update(caller: AgentProductCaller, agentId: Long, productId: Long, patch: AgentProductPatch)
    fun extend(caller: AgentProductCaller, agentId: Long, productId: Long)
}

interface AgentProductReadStore {
    fun products(agentId: Long): List<AgentProductSnapshot>
    fun product(productId: Long): AgentProductSnapshot?
}

class AgentProductDenied : RuntimeException("Доступ запрещён")
