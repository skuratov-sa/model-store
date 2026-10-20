package com.model_store.modern.catalog.giveaway.admin.domain

data class GiveawayCreate(
    val name: String?, val description: String?, val price: Float?, val prepaymentAmount: Float?,
    val count: Int?, val currency: String?, val originality: String?, val used: Boolean?,
    val externalUrl: String?, val categoryIds: List<Long>?, val imageIds: List<Long>?,
    val settings: GiveawaySettings?,
)
