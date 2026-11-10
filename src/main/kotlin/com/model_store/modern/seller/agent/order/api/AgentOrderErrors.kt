package com.model_store.modern.seller.agent.order.api

import com.model_store.modern.ordering.agent.application.*
import com.model_store.modern.ordering.lifecycle.application.LifecycleTransactionRequired
import com.model_store.modern.ordering.lifecycle.domain.*
import com.model_store.modern.identity.participant.application.*
import com.model_store.modern.shared.api.mvcApiError
import org.springframework.context.annotation.Profile
import org.springframework.dao.DataAccessException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.sql.SQLException

@RestControllerAdvice(assignableTypes = [AgentOrderController::class])
@Profile("modern")
class AgentOrderErrors {
    @ExceptionHandler(AgentOrderFailure::class)
    fun failure(error: AgentOrderFailure): ResponseEntity<*> = when (error.problem) {
        AgentOrderProblem.ORDER_NOT_FOUND -> mvcApiError(HttpStatus.NOT_FOUND, "INVALID_REQUEST", "Заказ бота не найден")
        AgentOrderProblem.UNKNOWN_ACTION -> mvcApiError(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Неизвестное действие с заказом")
        AgentOrderProblem.INVALID_PAGE -> mvcApiError(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Некорректная страница или размер страницы")
        AgentOrderProblem.ACCESS_DENIED -> denied()
        AgentOrderProblem.INVALID_ACTOR -> mvcApiError(HttpStatus.UNAUTHORIZED, "TOKEN_INVALID_OR_EXPIRED", "Не удалось распознать пользователя")
        AgentOrderProblem.BOT_CALLER -> ResponseEntity.status(403).build<Void>()
    }

    @ExceptionHandler(AgentProfileFailure.AdminForbidden::class, CurrentAdminForbidden::class)
    fun denied() = mvcApiError(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Доступ запрещён")
    @ExceptionHandler(AgentProfileFailure.AgentNotFound::class)
    fun missing() = mvcApiError(HttpStatus.NOT_FOUND, "PARTICIPANT_NOT_FOUND", "Бот не найден")

    @ExceptionHandler(LifecycleFailure::class)
    fun lifecycle(error: LifecycleFailure) = when (error.problem) {
        LifecycleProblem.INVALID_TRANSITION -> mvcApiError(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Нельзя выполнить операцию с данными условиями")
        LifecycleProblem.STATUS_CHANGED -> mvcApiError(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Статус заказа изменился. Повторите операцию")
        LifecycleProblem.PRODUCT_NOT_FOUND -> mvcApiError(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", "Товар не найден")
        LifecycleProblem.PARTICIPANT_NOT_FOUND -> mvcApiError(HttpStatus.NOT_FOUND, "PARTICIPANT_NOT_FOUND", "Пользователь не найден")
        LifecycleProblem.TRANSFER_NOT_FOUND -> mvcApiError(HttpStatus.NOT_FOUND, "TRANSFER_NOT_FOUND", "Способ доставки не найден")
        else -> internal()
    }

    @ExceptionHandler(DataAccessException::class)
    fun database(error: DataAccessException): ResponseEntity<*> {
        val root = generateSequence<Throwable>(error) { it.cause }.last()
        val state = (root as? SQLException)?.sqlState
        if (state in setOf("22001", "22003") || state?.startsWith("23") != true) return internal()
        val message = root.message.orEmpty()
        val reference = message.contains("foreign key constraint")
        return mvcApiError(if (reference) HttpStatus.BAD_REQUEST else HttpStatus.CONFLICT,
            if (reference) "INVALID_REFERENCE" else "DUPLICATE_KEY",
            if (reference) "Указана ссылка на несуществующий объект" else "Нарушено ограничение уникальности",
            mapOf("dbMessage" to message))
    }
    @ExceptionHandler(LifecycleTransactionRequired::class, AgentAccessTransactionRequired::class, CurrentAdminTransactionRequired::class)
    fun internal() = mvcApiError(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Внутренняя ошибка")
}
