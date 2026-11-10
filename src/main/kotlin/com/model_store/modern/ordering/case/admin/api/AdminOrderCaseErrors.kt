package com.model_store.modern.ordering.`case`.admin.api

import com.model_store.modern.identity.participant.application.CurrentAdminForbidden
import com.model_store.modern.identity.participant.application.CurrentAdminTransactionRequired
import com.model_store.modern.catalog.product.reservation.application.ProductReservationFailure
import com.model_store.modern.ordering.`case`.admin.domain.*
import com.model_store.modern.ordering.lifecycle.application.LifecycleTransactionRequired
import com.model_store.modern.shared.api.MvcApiError
import com.model_store.modern.shared.api.mvcApiError
import org.springframework.context.annotation.Profile
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.DataAccessException
import org.springframework.dao.IncorrectResultSizeDataAccessException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.sql.SQLException

@RestControllerAdvice(assignableTypes = [AdminOrderCaseController::class])
@Profile("modern")
class AdminOrderCaseErrors {
    @ExceptionHandler(DataIntegrityViolationException::class, IncorrectResultSizeDataAccessException::class)
    fun integrity(error: Exception): ResponseEntity<MvcApiError> {
        val root = generateSequence<Throwable>(error) { it.cause }.last()
        // Legacy R2DBC length/arithmetic failures are generic internal errors. JDBC
        // classifies them as integrity errors; retain the same safe five-field envelope.
        if ((root as? SQLException)?.sqlState in setOf("22001", "22003"))
            return mvcApiError(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Внутренняя ошибка")
        val message = root.message.orEmpty()
        val reference = message.contains("foreign key constraint")
        return mvcApiError(if (reference) HttpStatus.BAD_REQUEST else HttpStatus.CONFLICT,
            if (reference) "INVALID_REFERENCE" else "DUPLICATE_KEY",
            if (reference) "Указана ссылка на несуществующий объект" else "Нарушено ограничение уникальности",
            mapOf("dbMessage" to message))
    }
    @ExceptionHandler(AdminCaseFailure::class)
    fun failure(error: AdminCaseFailure): ResponseEntity<MvcApiError> {
        if (error.problem == AdminCaseProblem.AGENT_ACCOUNT) return ResponseEntity.status(403).build()
        val (status, code, message) = when (error.problem) {
            AdminCaseProblem.INVALID -> Triple(400, "BAD_REQUEST", invalid)
            AdminCaseProblem.INVALID_TRANSITION -> Triple(400, "ORDER_CASE_INVALID_TRANSITION", invalid)
            AdminCaseProblem.INVALID_OUTCOME -> Triple(400, "ORDER_CASE_INVALID_OUTCOME", "Недопустимый исход обращения")
            AdminCaseProblem.ALREADY_RESOLVED -> Triple(409, "ORDER_CASE_ALREADY_RESOLVED", "Обращение уже завершено")
            AdminCaseProblem.COMMENT_REQUIRED -> Triple(400, "BAD_REQUEST", "Укажите причину обращения")
            AdminCaseProblem.INVALID_URL -> Triple(400, "BAD_REQUEST", "Укажите ссылку https://t.me/...")
            AdminCaseProblem.INVALID_PAGE -> Triple(400, "BAD_REQUEST", "Некорректные параметры списка обращений")
            AdminCaseProblem.ACCESS_DENIED -> Triple(403, "ACCESS_DENIED", "Доступ запрещён")
            AdminCaseProblem.INVALID_ACTOR -> Triple(401, "TOKEN_INVALID_OR_EXPIRED", "Не удалось распознать пользователя")
            AdminCaseProblem.STOCK_CONFLICT -> Triple(500, "INTERNAL_ERROR", "Внутренняя ошибка")
            AdminCaseProblem.AGENT_ACCOUNT -> return ResponseEntity.status(403).build()
        }
        return mvcApiError(HttpStatus.valueOf(status), code, message)
    }
    @ExceptionHandler(CurrentAdminForbidden::class)
    fun forbidden() = mvcApiError(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Доступ запрещён")

    @ExceptionHandler(LifecycleTransactionRequired::class, CurrentAdminTransactionRequired::class)
    fun transaction() = mvcApiError(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Внутренняя ошибка")

    @ExceptionHandler(DataAccessException::class, ProductReservationFailure::class)
    fun persistence() = mvcApiError(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Внутренняя ошибка")

    companion object { private const val invalid = "Операция недоступна для текущего состояния заказа или обращения" }
}
