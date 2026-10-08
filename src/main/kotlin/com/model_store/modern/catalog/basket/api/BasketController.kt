package com.model_store.modern.catalog.basket.api

import com.model_store.exception.constant.ErrorCode
import com.model_store.modern.catalog.basket.application.BasketFailure
import com.model_store.modern.catalog.basket.application.BasketUseCases
import com.model_store.modern.catalog.product.search.api.ProductSearchDto
import com.model_store.modern.catalog.product.search.api.ProductSearchDtoMapper
import com.model_store.modern.catalog.product.search.api.ProductSearchRequest
import com.model_store.modern.shared.domain.Actor
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

data class BasketProductDto(
    val product: ProductSearchDto,
    val count: Int,
    val availableCount: Int?,
    val enoughStock: Boolean,
)

@RestController
@Profile("modern")
@RequestMapping("/basket")
class ModernBasketController(private val basket: BasketUseCases) {
    @PostMapping("/find")
    fun find(@AuthenticationPrincipal actor: Actor?, @RequestBody request: ProductSearchRequest): List<BasketProductDto> =
        basket.find(owner(actor), request.criteria()).map {
            BasketProductDto(ProductSearchDtoMapper.map(it.product), it.count, it.availableCount, it.enoughStock)
        }

    @PostMapping
    fun add(@AuthenticationPrincipal actor: Actor?, @RequestParam productId: Long, @RequestParam count: Int) =
        basket.add(owner(actor), productId, count)

    @PutMapping
    fun update(@AuthenticationPrincipal actor: Actor?, @RequestParam productId: Long, @RequestParam count: Int) =
        basket.update(owner(actor), productId, count)

    @DeleteMapping
    fun remove(@AuthenticationPrincipal actor: Actor?, @RequestParam productId: Long) =
        basket.remove(owner(actor), productId)

    private fun owner(actor: Actor?): Long = actor?.participantId?.takeIf { it > 0 }
        ?: throw BasketFailure(HttpStatus.UNAUTHORIZED, ErrorCode.TOKEN_INVALID_OR_EXPIRED, "Не удалось распознать пользователя")
}
