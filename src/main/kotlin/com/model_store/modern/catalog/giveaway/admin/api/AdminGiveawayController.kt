package com.model_store.modern.catalog.giveaway.admin.api

import com.model_store.modern.catalog.giveaway.admin.application.*
import com.model_store.modern.catalog.giveaway.admin.domain.*
import com.model_store.modern.shared.api.MvcApiError
import com.model_store.modern.shared.api.mvcApiError
import com.model_store.modern.shared.domain.Actor
import com.model_store.modern.shared.domain.TokenType
import com.model_store.modern.catalog.product.command.api.ProductCommandController
import com.model_store.modern.catalog.product.command.application.FailureKind
import com.model_store.modern.catalog.product.command.application.ProductCommandFailure
import com.model_store.modern.identity.participant.application.AgentDirectoryQuery
import com.model_store.modern.identity.participant.application.AgentProfileFailure
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Configuration
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import org.springframework.context.annotation.Profile
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import java.time.Instant

@JsonIgnoreProperties(ignoreUnknown = true)
data class GiveawaySettingsRequest(
    val enabled: Boolean? = null,
    val telegramUrl: String? = null,
    val startAt: Instant? = null,
    val endAt: Instant? = null,
    val winnersCount: Int? = null,
    val rules: String? = null,
    val homeText: String? = null,
) {
    fun settings() = GiveawaySettings(enabled, telegramUrl, startAt, endAt, winnersCount, rules, homeText)
}

/** Keeps the legacy Product JSON shape, including explicit nulls and effective status. */
data class AdminGiveawayProductResponse(
    val id: Long,
    val name: String?,
    val description: String?,
    val price: Float,
    val prepaymentAmount: Float?,
    val count: Int?,
    val currency: String,
    val originality: String?,
    val participantId: Long,
    val status: String,
    val expirationDate: Instant,
    val availability: String,
    val used: Boolean?,
    val externalUrl: String?,
    val giveawayEnabled: Boolean,
    val giveawayTelegramUrl: String?,
    val giveawayStartAt: Instant?,
    val giveawayEndAt: Instant?,
    val giveawayWinnersCount: Int?,
    val giveawayRules: String?,
    val giveawayHomeText: String?,
    val createdAt: Instant?,
)

data class AdminGiveawayDetailsResponse(val product: AdminGiveawayProductResponse,
    val imageIds: List<Long>, val categories: List<GiveawayCategory>)

fun AdminGiveaway.response() = AdminGiveawayProductResponse(id, name, description, price,
    prepaymentAmount, count, currency, originality, ownerId, status, expirationDate,
    availability, used, externalUrl, enabled, telegramUrl, startAt, endAt, winnersCount,
    rules, homeText, createdAt)

@RestController
@Profile("modern")
@RequestMapping("/admin/actions/giveaways")
class AdminGiveawayController(private val giveaways: AdminGiveaways) {
    @GetMapping("/history")
    fun history(@AuthenticationPrincipal actor: Actor?,
                @RequestParam(defaultValue = "0") page: Int,
                @RequestParam(defaultValue = "50") size: Int): List<AdminGiveawayProductResponse> =
        giveaways.history(caller(actor), page, size).map { it.response() }

    @GetMapping("/{productId}")
    fun details(@PathVariable productId: Long, @AuthenticationPrincipal actor: Actor?): AdminGiveawayDetailsResponse {
        val details = giveaways.details(productId, caller(actor))
        return AdminGiveawayDetailsResponse(details.product.response(), details.imageIds, details.categories)
    }

    @PutMapping("/{productId}")
    fun update(@PathVariable productId: Long, @AuthenticationPrincipal actor: Actor?,
               @RequestBody request: GiveawaySettingsRequest?) =
        giveaways.update(productId, caller(actor), request?.settings())

    private fun caller(actor: Actor?) = GiveawayCaller(actor?.participantId, actor?.role,
        actor?.tokenType != TokenType.ACCESS)
}

/** Reject obsolete ADMIN claims before binding paths, query parameters or JSON.
 * This snapshot is only an early gate: each operation still takes CurrentAdminLock.
 */
@Configuration
@Profile("modern")
class GiveawayAdminPrebinding(private val directory: AgentDirectoryQuery) : WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(object : HandlerInterceptor {
            override fun preHandle(request: HttpServletRequest, response: HttpServletResponse,
                                   handler: Any): Boolean {
                val method = handler as? HandlerMethod ?: return true
                val actor = SecurityContextHolder.getContext().authentication?.principal as? Actor
                val giveaway = method.beanType == AdminGiveawayController::class.java
                val productAdmin = method.beanType == ProductCommandController::class.java &&
                    method.method.name in setOf("create", "update") && actor?.role == "ADMIN"
                if (!giveaway && !productAdmin) return true
                fun denied(): Nothing {
                    if (giveaway) throw GiveawayFailure(GiveawayFailureKind.FORBIDDEN,
                        "ACCESS_DENIED", "Доступ запрещён")
                    throw ProductCommandFailure(FailureKind.FORBIDDEN, "ACCESS_DENIED", "Доступ запрещён")
                }
                val principal = actor ?: denied()
                val id = principal.participantId?.takeIf { it > 0 && principal.role == "ADMIN" &&
                    principal.tokenType == TokenType.ACCESS } ?: denied()
                try {
                    directory.requireActiveAdmin(id)
                } catch (_: AgentProfileFailure.AdminForbidden) {
                    denied()
                }
                return true
            }
        })
    }
}

@RestControllerAdvice(assignableTypes = [AdminGiveawayController::class])
@Profile("modern")
class AdminGiveawayErrorHandler {
    @ExceptionHandler(GiveawayFailure::class)
    fun failure(error: GiveawayFailure): ResponseEntity<MvcApiError> = mvcApiError(when (error.kind) {
        GiveawayFailureKind.BAD_REQUEST -> HttpStatus.BAD_REQUEST
        GiveawayFailureKind.FORBIDDEN -> HttpStatus.FORBIDDEN
        GiveawayFailureKind.NOT_FOUND -> HttpStatus.NOT_FOUND
        GiveawayFailureKind.CONFLICT -> HttpStatus.CONFLICT
    }, error.code, error.message)

    @ExceptionHandler(DataIntegrityViolationException::class)
    fun integrity(error: DataIntegrityViolationException): ResponseEntity<MvcApiError> {
        var root: Throwable = error
        while (root.cause != null) root = root.cause!!
        val message = root.message.orEmpty()
        return if ("foreign key constraint" in message)
            mvcApiError(HttpStatus.BAD_REQUEST, "INVALID_REFERENCE", "Указана ссылка на несуществующий объект")
        else mvcApiError(HttpStatus.CONFLICT, "DUPLICATE_KEY", "Нарушено ограничение уникальности")
    }
}
