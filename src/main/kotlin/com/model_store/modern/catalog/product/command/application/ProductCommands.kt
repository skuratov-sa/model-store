package com.model_store.modern.catalog.product.command.application

import com.model_store.modern.catalog.product.command.domain.*
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.temporal.ChronoUnit

enum class FailureKind { BAD_REQUEST, FORBIDDEN, NOT_FOUND, CONFLICT }
class ProductCommandFailure(val kind: FailureKind, val code: String, message: String) : RuntimeException(message)
private fun invalid(message: String): Nothing = throw ProductCommandFailure(FailureKind.BAD_REQUEST, "INVALID_REQUEST", message)
private fun denied(message: String): Nothing = throw ProductCommandFailure(FailureKind.FORBIDDEN, "ACCESS_DENIED", message)
private fun missing(message: String): Nothing = throw ProductCommandFailure(FailureKind.NOT_FOUND, "PRODUCT_NOT_FOUND", message)
// Legacy lets a missing NOT NULL field reach PostgreSQL; its global handler reports 409/DUPLICATE_KEY.
private fun missingRequiredColumn(): Nothing = throw ProductCommandFailure(FailureKind.CONFLICT,
    "DUPLICATE_KEY", "Нарушено ограничение уникальности")

interface ProductCommandStore {
    fun lockProduct(id: Long, mode: LockMode): ProductRecord?
    fun create(record: ProductRecord): Long
    fun update(record: ProductRecord)
    fun replaceCategories(id: Long, categoryIds: List<Long>)
}

interface SellerEligibilityPort {
    fun hasActiveTransfer(ownerId: Long): Boolean
    fun hasSocialNetwork(ownerId: Long): Boolean
    fun isAgent(ownerId: Long): Boolean
}

enum class LockMode { ACTIVE_ORDINARY, EXTENDABLE, NON_DELETED }

/** The media context is addressed by IDs; its rows join the product DB transaction. */
interface ProductImageLink {
    fun claim(productId: Long, ownerId: Long, imageIds: List<Long>)
    fun deleteProductImages(productId: Long)
}

@Service
@Profile("modern")
class ProductCommands(
    private val store: ProductCommandStore,
    private val seller: SellerEligibilityPort,
    private val images: ProductImageLink,
    @Value("\${app.product-expiration-days}") private val expirationDays: Long,
) {
    @Transactional
    fun create(ownerId: Long, role: String?, changes: ProductChanges): Long {
        if (role != "USER" && role != "ADMIN") denied("Доступ запрещён")
        val availability = changes.availability ?: invalid("Укажите тип товара")
        if (role != "ADMIN" && availability !in setOf(ProductAvailability.PURCHASABLE, ProductAvailability.PREORDER))
            denied("Этот тип товара доступен только администратору")
        if (changes.hasGiveawaySettings && availability != ProductAvailability.GIVEAWAY)
            invalid("Настройки розыгрыша допустимы только для GIVEAWAY")
        // Giveaway settings and activation belong to task 22. Never create an incomplete giveaway.
        if (availability == ProductAvailability.GIVEAWAY) invalid("Укажите настройки розыгрыша")
        // The old findByParticipantId ports fail with 404 before hasElements can return false.
        if (!seller.hasActiveTransfer(ownerId)) throw ProductCommandFailure(FailureKind.NOT_FOUND,
            "TRANSFER_NOT_FOUND", "Способ доставки не найден")
        if (!seller.hasSocialNetwork(ownerId)) throw ProductCommandFailure(FailureKind.NOT_FOUND,
            "SOCIAL_NETWORK_NOT_FOUND", "Социальные сети отсутствуют")
        val record = build(ownerId, changes, availability)
        val id = store.create(record)
        changes.imageIds?.takeIf { it.isNotEmpty() }?.let { images.claim(id, ownerId, it) }
        changes.categoryIds?.takeIf { it.isNotEmpty() }?.let { store.replaceCategories(id, it) }
        return id
    }

    @Transactional
    fun update(id: Long, ownerId: Long, role: String?, changes: ProductChanges) {
        if (role != "USER" && role != "ADMIN") denied("Доступ запрещён")
        if (role != "ADMIN" && changes.availability != null &&
            changes.availability !in setOf(ProductAvailability.PURCHASABLE, ProductAvailability.PREORDER))
            denied("Этот тип товара доступен только администратору")
        if (seller.isAgent(ownerId)) denied("Товары бота редактирует администратор")
        val admin = role == "ADMIN"
        val original = store.lockProduct(id, if (admin) LockMode.NON_DELETED else LockMode.ACTIVE_ORDINARY)
            ?.takeIf { it.ownerId == ownerId }
            ?: missing("Не удалось выполнить операцию: не достаточно прав или его не существует")
        if (!admin && changes.hasGiveawaySettings) denied("Настройки розыгрыша меняет администратор")
        val availability = changes.availability ?: original.availability
        if (availability == ProductAvailability.GIVEAWAY || original.availability == ProductAvailability.GIVEAWAY)
            denied("Розыгрыш настраивает администратор")
        if (changes.hasGiveawaySettings) invalid("Настройки розыгрыша допустимы только для GIVEAWAY")
        val next = original.copy(
            name = changes.name ?: original.name,
            description = changes.description ?: original.description,
            price = changes.price ?: original.price,
            prepaymentAmount = if (availability == ProductAvailability.PURCHASABLE) null
                else changes.prepaymentAmount ?: original.prepaymentAmount,
            count = if (availability == ProductAvailability.PURCHASABLE)
                changes.count ?: original.count else null,
            currency = changes.currency ?: original.currency,
            originality = changes.originality ?: original.originality,
            availability = availability,
            used = changes.used ?: original.used,
            externalUrl = changes.externalUrl ?: original.externalUrl,
        )
        validate(next, creation = false)
        store.update(next)
        changes.imageIds?.takeIf { it.isNotEmpty() }?.let { images.claim(id, ownerId, it) }
        changes.categoryIds?.let { store.replaceCategories(id, it) }
    }

    @Transactional
    fun delete(id: Long, ownerId: Long) {
        val product = store.lockProduct(id, LockMode.ACTIVE_ORDINARY)?.takeIf { it.ownerId == ownerId }
            ?: missing("Не удалось обновить товар: не достаточно прав или его не существует")
        store.update(product.copy(status = ProductState.DELETED))
        images.deleteProductImages(id)
    }

    @Transactional
    fun extend(id: Long, ownerId: Long) {
        val product = store.lockProduct(id, LockMode.EXTENDABLE)?.takeIf { it.ownerId == ownerId }
            ?: missing("Не удалось выполнить операцию: не достаточно прав или его не существует")
        store.update(product.copy(status = ProductState.ACTIVE, expirationDate = expiration()))
    }

    @Transactional
    fun changeStatus(id: Long, status: ProductState) {
        if (status == ProductState.AWAITING_GIVEAWAY) invalid("Статус ожидания задаётся датой начала розыгрыша")
        val product = store.lockProduct(id, LockMode.NON_DELETED)
            ?: missing("Не удалось выполнить операцию: не достаточно прав или его не существует")
        store.update(product.copy(status = status,
            expirationDate = if (status == ProductState.ACTIVE && !product.expirationDate.isAfter(Instant.now()))
                expiration() else product.expirationDate,
            giveawayEnabled = if (product.availability == ProductAvailability.GIVEAWAY && status != ProductState.ACTIVE)
                false else product.giveawayEnabled))
    }

    private fun build(ownerId: Long, changes: ProductChanges, availability: ProductAvailability): ProductRecord {
        val record = ProductRecord(ownerId = ownerId, name = changes.name, description = changes.description,
            price = changes.price ?: missingRequiredColumn(),
            prepaymentAmount = if (availability == ProductAvailability.PURCHASABLE) null else changes.prepaymentAmount,
            count = if (availability == ProductAvailability.PURCHASABLE || availability == ProductAvailability.PREORDER)
                changes.count else null,
            currency = changes.currency ?: missingRequiredColumn(), originality = changes.originality,
            status = ProductState.ACTIVE, expirationDate = expiration(), availability = availability,
            used = changes.used ?: false, externalUrl = changes.externalUrl)
        validate(record, creation = true)
        return record
    }

    private fun validate(product: ProductRecord, creation: Boolean) {
        if (!product.price.isFinite()) invalid("Укажите цену товара")
        if (creation && product.count != null && product.count <= 0)
            invalid("Введено некорректное кол-во товаров")
        if (product.availability == ProductAvailability.PREORDER && creation &&
            (product.prepaymentAmount == null || product.prepaymentAmount <= 0)) invalid("Предоплата указана неверно")
        if (product.availability == ProductAvailability.EXTERNAL_PRODUCT && product.externalUrl.isNullOrBlank())
            invalid("Укажите ссылку на внешний товар")
        if (product.prepaymentAmount != null && (!product.prepaymentAmount.isFinite() ||
            product.prepaymentAmount < 0 || product.prepaymentAmount > product.price))
            invalid("Предоплата указана неверно")
    }

    private fun expiration(): Instant = Instant.now().plus(expirationDays, ChronoUnit.DAYS)
}
