package com.model_store.modern.catalog.giveaway.admin.application

import com.model_store.modern.catalog.giveaway.admin.domain.*
import com.model_store.modern.catalog.product.command.application.ProductCommandStore
import com.model_store.modern.catalog.product.command.application.ProductImageLink
import com.model_store.modern.catalog.product.command.domain.ProductAvailability
import com.model_store.modern.catalog.product.command.domain.ProductRecord
import com.model_store.modern.catalog.product.command.domain.ProductState
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.temporal.ChronoUnit

@Service
@Profile("modern")
class CreateGiveaway(
    private val giveaways: AdminGiveawayStore,
    private val products: ProductCommandStore,
    private val images: ProductImageLink,
    @Value("\${app.product-expiration-days}") private val expirationDays: Long,
) {
    @Transactional
    fun create(caller: GiveawayCaller, request: GiveawayCreate): Long {
        if (caller.role != "ADMIN" || caller.agentAccess || caller.participantId == null || caller.participantId <= 0)
            throw GiveawayFailure(GiveawayFailureKind.FORBIDDEN, "ACCESS_DENIED",
            "Этот тип товара доступен только администратору")
        val ownerId = caller.participantId
        if (request.count != null && request.count <= 0) badRequest("Введено некорректное кол-во товаров")
        if (request.prepaymentAmount != null && (request.prepaymentAmount < 0 || request.price == null ||
                    request.prepaymentAmount > request.price)) badRequest("Предоплата указана неверно")
        if (request.price != null && !request.price.isFinite()) badRequest("Укажите цену товара")
        // A single lock coordinates interval checks with legacy giveaway writers.
        giveaways.lockActivation()
        val baseline = AdminGiveaway(-1, request.name, request.description, null,
            request.price ?: 0f, null, request.currency ?: "RUB", request.originality,
            ownerId, "ACTIVE", Instant.now().plus(expirationDays, ChronoUnit.DAYS),
            "GIVEAWAY", request.used ?: false, request.externalUrl, false,
            null, null, null, null, null, null, null)
        val valid = AdminGiveaways.applySettings(baseline, request.settings, entering = true)
        if (valid.enabled && giveaways.overlaps(-1, valid.startAt!!, valid.endAt!!))
            throw GiveawayFailure(GiveawayFailureKind.BAD_REQUEST, "GIVEAWAY_WINDOW_OVERLAP",
                "Период розыгрыша пересекается с другим запланированным розыгрышем")
        val id = products.create(ProductRecord(ownerId = ownerId, name = valid.name,
            description = valid.description, price = valid.price, prepaymentAmount = null,
            count = null, currency = valid.currency, originality = valid.originality,
            status = ProductState.valueOf(valid.status), expirationDate = valid.expirationDate,
            availability = ProductAvailability.GIVEAWAY, used = valid.used ?: false,
            externalUrl = valid.externalUrl))
        giveaways.saveSettings(valid.copy(id = id))
        request.imageIds?.takeIf { it.isNotEmpty() }?.let { images.claim(id, ownerId, it) }
        request.categoryIds?.takeIf { it.isNotEmpty() }?.let { products.replaceCategories(id, it) }
        return id
    }

    private fun badRequest(message: String): Nothing =
        throw GiveawayFailure(GiveawayFailureKind.BAD_REQUEST, "INVALID_REQUEST", message)
}
