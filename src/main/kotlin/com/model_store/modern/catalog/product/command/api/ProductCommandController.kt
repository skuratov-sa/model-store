package com.model_store.modern.catalog.product.command.api

import com.model_store.modern.catalog.product.command.application.*
import com.model_store.modern.catalog.product.command.domain.*
import com.model_store.modern.shared.domain.Actor
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

enum class CommandCurrency { USD, EUR, GBP, JPY, CNY, RUB, CREATE }

@JsonIgnoreProperties(ignoreUnknown = true)
data class ProductCommandRequest(
    val name: String? = null,
    val description: String? = null,
    val price: Float? = null,
    val prepaymentAmount: Float? = null,
    val categoryIds: List<Long>? = null,
    val count: Int? = null,
    val currency: CommandCurrency? = null,
    val originality: String? = null,
    val availability: ProductAvailability? = null,
    val used: Boolean? = null,
    val externalUrl: String? = null,
    val giveaway: Any? = null,
    val imageIds: List<Long>? = null,
) {
    fun changes() = ProductChanges(name, description, price, prepaymentAmount, categoryIds, count,
        currency?.name, originality, availability, used, externalUrl, imageIds, giveaway != null)
}

@RestController
@Profile("modern")
class ProductCommandController(private val commands: ProductCommands) {
    @PostMapping("/products")
    fun create(@AuthenticationPrincipal actor: Actor?, @RequestBody request: ProductCommandRequest): Long {
        val ownerId = requireActor(actor)
        return commands.create(ownerId, actor!!.role, request.changes())
    }

    @PutMapping("/product/{id}")
    fun update(@PathVariable id: Long, @AuthenticationPrincipal actor: Actor?,
               @RequestBody request: ProductCommandRequest) {
        val ownerId = requireActor(actor)
        commands.update(id, ownerId, actor!!.role, request.changes())
    }

    @DeleteMapping("/product/{id}")
    fun delete(@PathVariable id: Long, @AuthenticationPrincipal actor: Actor?) =
        commands.delete(id, requireActor(actor))

    @PostMapping("/products/extend/{id}")
    fun extend(@PathVariable id: Long, @AuthenticationPrincipal actor: Actor?) =
        commands.extend(id, requireActor(actor))

    private fun requireActor(actor: Actor?): Long {
        if (actor?.participantId == null) throw ProductCommandFailure(FailureKind.FORBIDDEN,
            "ACCESS_DENIED", "Доступ запрещён")
        if (actor.isAgentAccess || actor.role != "USER" && actor.role != "ADMIN")
            throw ProductCommandFailure(FailureKind.FORBIDDEN, "ACCESS_DENIED", "Доступ запрещён")
        return actor.participantId
    }
}

@RestController
@Profile("modern")
@RequestMapping("/admin/actions")
class AdminProductStatusController(private val commands: ProductCommands) {
    @PutMapping("/product/{id}")
    fun status(@PathVariable id: Long, @RequestParam productStatus: ProductState,
               @AuthenticationPrincipal actor: Actor?) {
        if (actor?.role != "ADMIN" || actor.isAgentAccess || actor.participantId == null)
            throw ProductCommandFailure(FailureKind.FORBIDDEN, "ACCESS_DENIED", "Доступ запрещён")
        commands.changeStatus(id, productStatus)
    }
}
