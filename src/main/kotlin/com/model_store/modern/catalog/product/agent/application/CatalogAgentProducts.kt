package com.model_store.modern.catalog.product.agent.application

import com.model_store.modern.catalog.product.agent.domain.AgentProductPolicy
import com.model_store.modern.catalog.product.agent.domain.InvalidAgentProduct
import com.model_store.modern.catalog.product.command.application.*
import com.model_store.modern.catalog.product.command.domain.*
import com.model_store.modern.catalog.giveaway.admin.application.*
import com.model_store.modern.catalog.giveaway.admin.domain.GiveawaySettings
import com.model_store.modern.identity.participant.application.AgentAccessLock
import com.model_store.modern.identity.participant.application.AgentProfileCommands
import com.model_store.modern.identity.participant.application.AgentDirectoryQuery
import com.model_store.modern.identity.agent.application.ActiveAgentLock
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.temporal.ChronoUnit

@Service
@Profile("modern")
class CatalogAgentProducts(
    private val products: ProductCommandStore,
    private val reads: AgentProductReadStore,
    private val giveaways: AdminGiveawayStore,
    private val images: ProductImageLink,
    private val access: AgentAccessLock,
    private val profiles: AgentProfileCommands,
    private val activeAgent: ActiveAgentLock,
    private val directory: AgentDirectoryQuery,
    @Value("\${app.product-expiration-days}") private val expirationDays: Long,
) : AgentProducts {
    @Transactional("transactionManager")
    override fun create(caller: AgentProductCaller, patch: AgentProductPatch): Long {
        if (caller.id <= 0 || !caller.agentAccess) throw AgentProductDenied()
        activeAgent.lockActiveAgent(caller.id)
        policy { AgentProductPolicy.externalUrl(patch.externalUrl) }
        val price = patch.price ?: missingColumn()
        policy { AgentProductPolicy.price(price) }
        val record = ProductRecord(ownerId = caller.id, name = patch.name, description = patch.description,
            price = price, prepaymentAmount = null, count = null, currency = patch.currency ?: missingColumn(),
            originality = patch.originality, status = ProductState.ACTIVE, expirationDate = expiration(),
            availability = ProductAvailability.EXTERNAL_PRODUCT, used = patch.used ?: false, externalUrl = patch.externalUrl)
        val id = products.create(record)
        patch.imageIds?.takeIf { it.isNotEmpty() }?.let { images.claim(id, caller.id, it) }
        patch.categoryIds?.takeIf { it.isNotEmpty() }?.let { products.replaceCategories(id, it) }
        return id
    }

    @Transactional("transactionManager", readOnly = true)
    override fun products(caller: AgentProductCaller, agentId: Long): List<AgentProductSnapshot> {
        requireAdmin(caller)
        profiles.read(caller.id, agentId)
        return reads.products(agentId)
    }

    @Transactional("transactionManager", readOnly = true)
    override fun product(caller: AgentProductCaller, productId: Long): AgentProductSnapshot {
        requireAdmin(caller)
        directory.requireActiveAdmin(caller.id)
        return reads.product(productId) ?: throw ProductCommandFailure(FailureKind.NOT_FOUND,
            "PRODUCT_NOT_FOUND", "Товар не найден")
    }

    @Transactional("transactionManager")
    override fun update(caller: AgentProductCaller, agentId: Long, productId: Long, patch: AgentProductPatch) {
        requireAdmin(caller)
        access.lockActiveAdminAndAgent(caller.id, agentId)
        policy { AgentProductPolicy.updateType(patch.availability) }
        // Same order as 22 and reactive admin writes; never take a product before this lock.
        giveaways.lockActivation()
        val original = giveaways.lockProduct(productId)?.takeIf { it.ownerId == agentId } ?: missing()
        val availability = patch.availability ?: original.availability
        val entering = original.availability != "GIVEAWAY" && availability == "GIVEAWAY"
        val leaving = original.availability == "GIVEAWAY" && availability != "GIVEAWAY"
        val next = original.copy(name = patch.name ?: original.name,
            description = patch.description ?: original.description, price = patch.price ?: original.price,
            currency = patch.currency ?: original.currency, originality = patch.originality ?: original.originality,
            availability = availability, used = patch.used ?: original.used,
            externalUrl = patch.externalUrl ?: original.externalUrl,
            count = if (availability == "PURCHASABLE") patch.count ?: original.count else null,
            prepaymentAmount = if (availability == "PURCHASABLE") null else patch.prepaymentAmount ?: original.prepaymentAmount)
        policy { AgentProductPolicy.price(next.price) }
        val configured = if (availability == "GIVEAWAY") {
            val settings = patch.giveaway?.let { GiveawaySettings(it.enabled, it.telegramUrl, it.startAt,
                it.endAt, it.winnersCount, it.rules, it.homeText) }
            val configured = AdminGiveaways.applySettings(next.copy(count = null, prepaymentAmount = null), settings, entering)
            if (entering && giveaways.hasActiveOrders(productId))
                invalid("Товар с незавершёнными заказами нельзя перевести в розыгрыш")
            if (configured.enabled && giveaways.overlaps(productId, configured.startAt!!, configured.endAt!!))
                throw GiveawayFailure(GiveawayFailureKind.BAD_REQUEST, "GIVEAWAY_WINDOW_OVERLAP",
                    "Период розыгрыша пересекается с другим запланированным розыгрышем")
            giveaways.saveSettings(configured)
            configured
        } else {
            if (leaving && next.price <= 0) invalid("Укажите цену товара при возврате в продажу")
            if (!leaving && patch.giveaway != null) invalid("Настройки розыгрыша допустимы только для GIVEAWAY")
            policy {
                if (availability == "EXTERNAL_PRODUCT") AgentProductPolicy.externalUrl(next.externalUrl)
                AgentProductPolicy.prepayment(next.prepaymentAmount, next.price)
            }
            next.copy(enabled = if (leaving) false else next.enabled,
                status = if (leaving && next.status in listOf("TIME_EXPIRED", "AWAITING_GIVEAWAY")) "ACTIVE" else next.status,
                expirationDate = if (leaving && !next.expirationDate.isAfter(Instant.now())) expiration() else next.expirationDate)
        }
        products.update(ProductRecord(productId, agentId, configured.name, configured.description, configured.price,
            configured.prepaymentAmount, configured.count, configured.currency, configured.originality,
            ProductState.valueOf(configured.status), configured.expirationDate,
            ProductAvailability.valueOf(configured.availability), configured.used ?: false,
            configured.externalUrl, configured.enabled))
        patch.imageIds?.takeIf { it.isNotEmpty() }?.let { images.claim(productId, agentId, it) }
        patch.categoryIds?.let { products.replaceCategories(productId, it) }
    }

    @Transactional("transactionManager")
    override fun extend(caller: AgentProductCaller, agentId: Long, productId: Long) {
        requireAdmin(caller)
        access.lockActiveAdminAndAgent(caller.id, agentId)
        val original = products.lockProduct(productId, LockMode.EXTENDABLE)?.takeIf { it.ownerId == agentId }
            ?: missing()
        products.update(original.copy(status = ProductState.ACTIVE, expirationDate = expiration()))
    }

    private fun requireAdmin(caller: AgentProductCaller) {
        if (caller.id <= 0 || caller.role != "ADMIN" || caller.agentAccess) throw AgentProductDenied()
    }

    private fun missing(): Nothing = throw ProductCommandFailure(FailureKind.NOT_FOUND, "PRODUCT_NOT_FOUND",
        "Не удалось выполнить операцию: не достаточно прав или его не существует")
    private fun invalid(message: String): Nothing = throw ProductCommandFailure(FailureKind.BAD_REQUEST, "INVALID_REQUEST", message)
    private fun policy(action: () -> Unit) {
        try { action() } catch (error: InvalidAgentProduct) { invalid(error.message.orEmpty()) }
    }
    private fun missingColumn(): Nothing = throw ProductCommandFailure(FailureKind.CONFLICT, "DUPLICATE_KEY", "Нарушено ограничение уникальности")
    private fun expiration(): Instant = Instant.now().plus(expirationDays, ChronoUnit.DAYS)
}
