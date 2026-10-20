package com.model_store.modern.catalog.giveaway.admin.application

import com.model_store.modern.catalog.giveaway.admin.domain.GiveawayCaller
import com.model_store.modern.catalog.giveaway.admin.domain.GiveawayCreate
import com.model_store.modern.catalog.product.command.application.ProductCommandStore
import com.model_store.modern.catalog.product.command.application.ProductImageLink
import com.model_store.modern.catalog.product.command.application.SellerEligibilityPort
import com.model_store.modern.catalog.product.command.domain.ProductAvailability
import com.model_store.modern.catalog.product.command.domain.ProductRecord
import com.model_store.modern.catalog.product.command.domain.ProductState
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.temporal.ChronoUnit

/** The legacy owner product route can edit an existing giveaway or enter/leave the subtype. */
@Service
@Profile("modern")
class UpdateGiveawayProduct(
    private val giveaways: AdminGiveawayStore,
    private val products: ProductCommandStore,
    private val seller: SellerEligibilityPort,
    private val images: ProductImageLink,
    @Value("\${app.product-expiration-days}") private val expirationDays: Long,
) {
    fun handles(id: Long, requested: ProductAvailability?): Boolean =
        requested == ProductAvailability.GIVEAWAY || giveaways.isGiveaway(id)

    @Transactional
    fun update(id: Long, caller: GiveawayCaller, requested: ProductAvailability?, changes: GiveawayCreate) {
        val ownerId = caller.participantId?.takeIf { it > 0 && caller.role == "ADMIN" && !caller.agentAccess }
            ?: throw GiveawayFailure(GiveawayFailureKind.FORBIDDEN, "ACCESS_DENIED", "Доступ запрещён")
        if (seller.isAgent(ownerId))
            throw GiveawayFailure(GiveawayFailureKind.FORBIDDEN, "ACCESS_DENIED",
                "Товары бота редактирует администратор")
        giveaways.lockActivation()
        val original = giveaways.lockProduct(id)?.takeIf { it.ownerId == ownerId }
            ?: throw GiveawayFailure(GiveawayFailureKind.NOT_FOUND, "PRODUCT_NOT_FOUND",
                "Не удалось выполнить операцию: не достаточно прав или его не существует")
        val availability = requested ?: ProductAvailability.valueOf(original.availability)
        val entering = original.availability != "GIVEAWAY" && availability == ProductAvailability.GIVEAWAY
        val leaving = original.availability == "GIVEAWAY" && availability != ProductAvailability.GIVEAWAY
        if (!entering && !leaving && availability != ProductAvailability.GIVEAWAY)
            throw GiveawayFailure(GiveawayFailureKind.CONFLICT, "INVALID_REQUEST",
                "Тип товара изменился во время обновления")

        val next = original.copy(
            name = changes.name ?: original.name,
            description = changes.description ?: original.description,
            price = changes.price ?: original.price,
            currency = changes.currency ?: original.currency,
            originality = changes.originality ?: original.originality,
            used = changes.used ?: original.used,
            externalUrl = changes.externalUrl ?: original.externalUrl,
            count = if (availability == ProductAvailability.PURCHASABLE) changes.count ?: original.count else null,
            prepaymentAmount = if (availability == ProductAvailability.PURCHASABLE) null
                else changes.prepaymentAmount ?: original.prepaymentAmount,
            availability = availability.name,
        )
        if (!next.price.isFinite()) invalid("Укажите цену товара")
        val record = if (availability == ProductAvailability.GIVEAWAY) {
            if (entering && giveaways.hasActiveOrders(id))
                invalid("Товар с незавершёнными заказами нельзя перевести в розыгрыш")
            val configured = AdminGiveaways.applySettings(next.copy(count = null, prepaymentAmount = null),
                changes.settings, entering)
            if (configured.enabled && giveaways.overlaps(id, configured.startAt!!, configured.endAt!!))
                throw GiveawayFailure(GiveawayFailureKind.BAD_REQUEST, "GIVEAWAY_WINDOW_OVERLAP",
                    "Период розыгрыша пересекается с другим запланированным розыгрышем")
            // Settings must be complete before the product command can enable the subtype.
            giveaways.saveSettings(configured)
            configured.record()
        } else {
            if (changes.settings != null)
                invalid("Настройки розыгрыша допустимы только для GIVEAWAY")
            if (leaving && next.price <= 0) invalid("Укажите цену товара при возврате в продажу")
            if (availability == ProductAvailability.EXTERNAL_PRODUCT && next.externalUrl.isNullOrBlank())
                invalid("Укажите ссылку на внешний товар")
            if (next.prepaymentAmount != null && (!next.prepaymentAmount.isFinite() ||
                    next.prepaymentAmount < 0 || next.prepaymentAmount > next.price))
                invalid("Предоплата указана неверно")
            val status = if (leaving && original.status in listOf("TIME_EXPIRED", "AWAITING_GIVEAWAY"))
                ProductState.ACTIVE else ProductState.valueOf(original.status)
            next.copy(enabled = if (leaving) false else original.enabled,
                status = status.name,
                expirationDate = if (leaving && !original.expirationDate.isAfter(Instant.now()))
                    Instant.now().plus(expirationDays, ChronoUnit.DAYS) else original.expirationDate).record()
        }
        products.update(record)
        changes.imageIds?.takeIf { it.isNotEmpty() }?.let { images.claim(id, ownerId, it) }
        changes.categoryIds?.let { products.replaceCategories(id, it) }
    }

    private fun com.model_store.modern.catalog.giveaway.admin.domain.AdminGiveaway.record() = ProductRecord(
        id = id, ownerId = ownerId, name = name, description = description,
        price = price, prepaymentAmount = prepaymentAmount, count = count,
        currency = currency, originality = originality, status = ProductState.valueOf(status),
        expirationDate = expirationDate, availability = ProductAvailability.valueOf(availability),
        used = used ?: false, externalUrl = externalUrl, giveawayEnabled = enabled,
    )

    private fun invalid(message: String): Nothing =
        throw GiveawayFailure(GiveawayFailureKind.BAD_REQUEST, "INVALID_REQUEST", message)
}
