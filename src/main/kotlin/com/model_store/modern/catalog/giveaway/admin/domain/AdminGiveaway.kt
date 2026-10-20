package com.model_store.modern.catalog.giveaway.admin.domain

import java.time.Instant

/** Catalog facts only; no persistence or transport types cross this boundary. */
data class AdminGiveaway(
    val id: Long,
    val name: String?,
    val description: String?,
    val count: Int?,
    val price: Float,
    val prepaymentAmount: Float?,
    val currency: String,
    val originality: String?,
    val ownerId: Long,
    val status: String,
    val expirationDate: Instant,
    val availability: String,
    val used: Boolean?,
    val externalUrl: String?,
    val enabled: Boolean,
    val telegramUrl: String?,
    val startAt: Instant?,
    val endAt: Instant?,
    val winnersCount: Int?,
    val rules: String?,
    val homeText: String?,
    val createdAt: Instant?,
)

data class GiveawaySettings(
    val enabled: Boolean? = null,
    val telegramUrl: String? = null,
    val startAt: Instant? = null,
    val endAt: Instant? = null,
    val winnersCount: Int? = null,
    val rules: String? = null,
    val homeText: String? = null,
)

data class GiveawayCategory(val id: Long, val name: String)
data class AdminGiveawayDetails(val product: AdminGiveaway, val imageIds: List<Long>,
                                val categories: List<GiveawayCategory>)

/** Claims from the already verified security principal, without HTTP or Spring types. */
data class GiveawayCaller(val participantId: Long?, val role: String?, val agentAccess: Boolean)
