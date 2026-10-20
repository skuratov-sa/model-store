package com.model_store.modern.catalog.giveaway.admin.application

import com.model_store.modern.catalog.giveaway.admin.domain.*
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.net.URI
import java.time.Instant

enum class GiveawayFailureKind { BAD_REQUEST, FORBIDDEN, NOT_FOUND, CONFLICT }
class GiveawayFailure(val kind: GiveawayFailureKind, val code: String, message: String) : RuntimeException(message)
private fun bad(message: String): Nothing = throw GiveawayFailure(GiveawayFailureKind.BAD_REQUEST, "INVALID_REQUEST", message)
private fun missing(): Nothing = throw GiveawayFailure(GiveawayFailureKind.NOT_FOUND, "PRODUCT_NOT_FOUND", "Розыгрыш не найден")

interface AdminGiveawayStore {
    fun isGiveaway(id: Long): Boolean
    fun lockProduct(id: Long): AdminGiveaway?
    fun history(adminId: Long, limit: Int, offset: Long): List<AdminGiveaway>
    fun find(id: Long, adminId: Long): AdminGiveaway?
    fun details(id: Long): Pair<List<Long>, List<GiveawayCategory>>
    /** Serializes activation and schedule checks across product IDs, including old reactive writers. */
    fun lockActivation()
    fun lockOwned(id: Long, adminId: Long): AdminGiveaway?
    fun overlaps(excludeId: Long, start: Instant, end: Instant): Boolean
    fun hasActiveOrders(id: Long): Boolean
    fun saveSettings(product: AdminGiveaway)
}

@Service
@Profile("modern")
class AdminGiveaways(private val store: AdminGiveawayStore) {
    fun history(caller: GiveawayCaller, page: Int, size: Int): List<AdminGiveaway> {
        val adminId = admin(caller)
        if (page < 0 || size !in 1..100) bad("Некорректная страница истории")
        return store.history(adminId, size, page.toLong() * size).map(::effective)
    }

    fun details(id: Long, caller: GiveawayCaller): AdminGiveawayDetails {
        val adminId = admin(caller)
        val product = store.find(id, adminId) ?: missing()
        val (images, categories) = store.details(id)
        return AdminGiveawayDetails(effective(product), images, categories)
    }

    @Transactional
    fun update(id: Long, caller: GiveawayCaller, settings: GiveawaySettings?) {
        val adminId = admin(caller)
        if (settings == null) bad("Пустые настройки розыгрыша")
        // Match legacy's dictionary-before-product order for schedule writes.
        store.lockActivation()
        val original = store.lockOwned(id, adminId) ?: missing()
        val entering = original.availability != "GIVEAWAY"
        val next = applySettings(original, settings, entering).copy(availability = "GIVEAWAY",
            count = null, prepaymentAmount = null)
        if (entering && store.hasActiveOrders(id))
            bad("Товар с незавершёнными заказами нельзя перевести в розыгрыш")
        if (next.enabled && store.overlaps(id, next.startAt!!, next.endAt!!))
            throw GiveawayFailure(GiveawayFailureKind.BAD_REQUEST, "GIVEAWAY_WINDOW_OVERLAP",
                "Период розыгрыша пересекается с другим запланированным розыгрышем")
        store.saveSettings(next)
    }

    private fun admin(caller: GiveawayCaller): Long = caller.participantId
        ?.takeIf { it > 0 && caller.role == "ADMIN" && !caller.agentAccess }
        ?: throw GiveawayFailure(GiveawayFailureKind.FORBIDDEN, "ACCESS_DENIED",
            "Управление розыгрышами доступно только администратору")

    companion object {
        fun applySettings(original: AdminGiveaway, settings: GiveawaySettings?, entering: Boolean): AdminGiveaway {
            if (settings == null && entering) bad("Укажите настройки розыгрыша")
            val next = original.copy(
                enabled = settings?.enabled ?: if (entering) true else original.enabled,
                telegramUrl = settings?.telegramUrl?.trim() ?: original.telegramUrl,
                startAt = settings?.startAt ?: original.startAt,
                endAt = settings?.endAt ?: original.endAt,
                winnersCount = settings?.winnersCount ?: original.winnersCount,
                rules = settings?.rules ?: original.rules,
                homeText = settings?.homeText ?: original.homeText,
            )
            if (!validTelegram(next.telegramUrl) || next.startAt == null || next.endAt == null ||
                !next.startAt.isBefore(next.endAt) || next.winnersCount == null || next.winnersCount <= 0 ||
                next.rules.isNullOrBlank() || next.homeText.isNullOrBlank()) bad("Некорректные настройки розыгрыша")
            val now = Instant.now()
            return if (next.enabled) {
                if (!next.endAt.isAfter(now)) bad("Дата окончания розыгрыша должна быть в будущем")
                if (next.status == "BLOCKED" || next.status == "DELETED")
                    bad("Заблокированный товар нельзя активировать")
                next.copy(status = if (next.startAt.isAfter(now)) "AWAITING_GIVEAWAY" else "ACTIVE")
            } else if (next.status == "ACTIVE" || next.status == "AWAITING_GIVEAWAY") {
                next.copy(status = "TIME_EXPIRED")
            } else next
        }

        fun effective(product: AdminGiveaway): AdminGiveaway {
            if (product.availability != "GIVEAWAY" || !product.enabled ||
                product.status != "ACTIVE" && product.status != "AWAITING_GIVEAWAY") return product
            val now = Instant.now()
            val end = product.endAt ?: return product
            val start = product.startAt ?: return product
            return product.copy(status = if (!end.isAfter(now)) "TIME_EXPIRED"
                else if (start.isAfter(now)) "AWAITING_GIVEAWAY" else "ACTIVE")
        }

        private fun validTelegram(value: String?): Boolean {
            if (value.isNullOrBlank()) return false
            return try {
                val uri = URI.create(value)
                uri.scheme.equals("https", true) && uri.host != null &&
                    (uri.host.equals("t.me", true) || uri.host.equals("telegram.me", true)) &&
                    uri.rawUserInfo == null && (uri.path?.length ?: 0) > 1
            } catch (_: IllegalArgumentException) { false }
        }
    }
}
