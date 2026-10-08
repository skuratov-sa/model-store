package com.model_store.modern.catalog.product.command.domain

import java.time.Instant

enum class ProductAvailability { PURCHASABLE, PREORDER, EXTERNAL_PRODUCT, GIVEAWAY }
enum class ProductState { ACTIVE, AWAITING_GIVEAWAY, TIME_EXPIRED, BLOCKED, DELETED }

data class ProductChanges(
    val name: String? = null,
    val description: String? = null,
    val price: Float? = null,
    val prepaymentAmount: Float? = null,
    val categoryIds: List<Long>? = null,
    val count: Int? = null,
    val currency: String? = null,
    val originality: String? = null,
    val availability: ProductAvailability? = null,
    val used: Boolean? = null,
    val externalUrl: String? = null,
    val imageIds: List<Long>? = null,
    val hasGiveawaySettings: Boolean = false,
)

data class ProductRecord(
    val id: Long? = null,
    val ownerId: Long,
    val name: String?,
    val description: String?,
    val price: Float,
    val prepaymentAmount: Float?,
    val count: Int?,
    val currency: String,
    val originality: String?,
    val status: ProductState,
    val expirationDate: Instant,
    val availability: ProductAvailability,
    val used: Boolean,
    val externalUrl: String?,
    val giveawayEnabled: Boolean = false,
)
