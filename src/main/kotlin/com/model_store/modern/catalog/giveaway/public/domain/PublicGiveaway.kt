package com.model_store.modern.catalog.giveaway.`public`.domain

import java.time.Instant

/** The public projection deliberately contains IDs rather than cross-context entities. */
data class PublicGiveaway(
    val productId: Long,
    val name: String?,
    val description: String?,
    val imageIds: List<Long>,
    val telegramUrl: String?,
    val startAt: Instant,
    val endAt: Instant,
    val winnersCount: Int?,
    val rules: String?,
    val homeText: String?,
    val status: String,
)
