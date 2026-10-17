package com.model_store.modern.ordering.create.api

import com.model_store.exception.constant.ErrorCode
import com.model_store.modern.ordering.create.domain.OrderFailure
import com.model_store.modern.ordering.create.domain.OrderProblem
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.time.OffsetDateTime

data class OrderCreateError(val code: String, val message: String, val status: Int,
                            val timestamp: String, val details: Any? = null)

@RestControllerAdvice(assignableTypes = [OrderCreateController::class])
@Profile("modern")
class OrderCreateErrorHandler {
    @ExceptionHandler(OrderFailure::class)
    fun api(error: OrderFailure): ResponseEntity<OrderCreateError> {
        val contract = when (error.problem) {
            OrderProblem.PRODUCT_NOT_FOUND -> Contract(HttpStatus.NOT_FOUND, ErrorCode.PRODUCT_NOT_FOUND, "Товар не найден")
            OrderProblem.COUNT_INVALID -> Contract(HttpStatus.BAD_REQUEST, ErrorCode.COUNT_INVALID,
                "Количество товаров должно быть больше чем 0")
            OrderProblem.OUT_OF_STOCK -> Contract(HttpStatus.BAD_REQUEST, ErrorCode.OUT_OF_STOCK,
                "Недостаточно товара на складе")
            OrderProblem.OWN_PRODUCT_ORDER_FORBIDDEN -> Contract(HttpStatus.BAD_REQUEST,
                ErrorCode.OWN_PRODUCT_ORDER_FORBIDDEN, "Нельзя оформить заказ на собственный товар")
            OrderProblem.ACCESS_DENIED -> Contract(HttpStatus.FORBIDDEN, ErrorCode.ACCESS_DENIED, "Доступ запрещён")
            OrderProblem.AGENT_PRODUCT -> Contract(HttpStatus.FORBIDDEN, ErrorCode.ACCESS_DENIED,
                "Нельзя заказать товар агента")
            OrderProblem.INVALID_ADDRESS -> Contract(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_ADDRESS,
                "Указан неверный адрес доставки")
            OrderProblem.INVALID_TRANSFER -> Contract(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_TRANSFER,
                "Указан неверный способ доставки")
            OrderProblem.TRANSFER_NOT_FOUND -> Contract(HttpStatus.NOT_FOUND, ErrorCode.TRANSFER_NOT_FOUND,
                "Способ доставки не найден")
            OrderProblem.TOKEN_INVALID_OR_EXPIRED -> Contract(HttpStatus.UNAUTHORIZED,
                ErrorCode.TOKEN_INVALID_OR_EXPIRED, "Не удалось распознать пользователя")
            OrderProblem.SELLER_CHANGED -> Contract(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST,
                "Продавец товара изменился")
            OrderProblem.RESERVATION_CONFLICT -> Contract(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST,
                "Не удалось зарезервировать товар")
        }
        return ResponseEntity.status(contract.status).body(OrderCreateError(contract.code.name,
            contract.message, contract.status.value(), OffsetDateTime.now().toString()))
    }

    private data class Contract(val status: HttpStatus, val code: ErrorCode, val message: String)
}
