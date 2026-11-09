package com.model_store.modern.ordering.lifecycle.api

import com.model_store.modern.ordering.lifecycle.domain.*
import com.model_store.modern.ordering.lifecycle.application.LifecycleTransactionRequired
import org.springframework.context.annotation.Profile
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.IncorrectResultSizeDataAccessException
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.time.OffsetDateTime
import java.sql.SQLException

data class LifecycleError(val code: String, val message: String, val status: Int,
                          val timestamp: String, val details: Any? = null)

@RestControllerAdvice(assignableTypes = [OrderLifecycleController::class])
@Profile("modern")
class OrderLifecycleErrors {
    @ExceptionHandler(LifecycleTransactionRequired::class)
    fun transactionRequired(): ResponseEntity<LifecycleError> = ResponseEntity.status(500).body(
        LifecycleError("INTERNAL_ERROR", "Внутренняя ошибка", 500, OffsetDateTime.now().toString()))

    @ExceptionHandler(DataIntegrityViolationException::class, IncorrectResultSizeDataAccessException::class)
    fun integrity(error: Exception): ResponseEntity<LifecycleError> {
        val cause = generateSequence<Throwable>(error) { it.cause }.last()
        // R2DBC's legacy length/arithmetic errors reach its generic handler; JDBC classifies
        // these as integrity violations. Preserve the actual HTTP contract across drivers.
        if ((cause as? SQLException)?.sqlState in setOf("22001", "22003"))
            return ResponseEntity.status(500).body(LifecycleError("INTERNAL_ERROR", "Внутренняя ошибка", 500,
                OffsetDateTime.now().toString()))
        val root = cause.message.orEmpty()
        val reference = root.contains("foreign key constraint")
        val status = if (reference) 400 else 409
        return ResponseEntity.status(status).body(LifecycleError(
            if (reference) "INVALID_REFERENCE" else "DUPLICATE_KEY",
            if (reference) "Указана ссылка на несуществующий объект" else "Нарушено ограничение уникальности",
            status, OffsetDateTime.now().toString(), mapOf("dbMessage" to root)))
    }

    @ExceptionHandler(LifecycleFailure::class)
    fun failure(error: LifecycleFailure): ResponseEntity<LifecycleError> {
        val (status, code, message) = when (error.problem) {
            LifecycleProblem.INVALID_TRANSITION -> Triple(400, "BAD_REQUEST", "Нельзя выполнить операцию с данными условиями")
            LifecycleProblem.STATUS_CHANGED -> Triple(400, "BAD_REQUEST", "Статус заказа изменился. Повторите операцию")
            LifecycleProblem.DISPUTE_PAYMENT_UNAVAILABLE -> Triple(400, "BAD_REQUEST", "Операция недоступна для текущего состояния заказа")
            LifecycleProblem.DISPUTE_PAYMENT_CHANGED -> Triple(400, "BAD_REQUEST", "Подтверждение оплаты уже добавлено или спор завершён")
            LifecycleProblem.DISPUTE_EVIDENCE_FAILED -> Triple(400, "BAD_REQUEST", "Не удалось добавить подтверждение к спору")
            LifecycleProblem.ACCESS_DENIED -> Triple(403, "ACCESS_DENIED", "Доступ запрещён")
            LifecycleProblem.BOT_SELLER -> Triple(403, "ACCESS_DENIED", "Заказами бота управляет администратор")
            LifecycleProblem.INVALID_ACTOR -> Triple(401, "TOKEN_INVALID_OR_EXPIRED", "Не удалось распознать пользователя")
            LifecycleProblem.PROOF_NOT_FOUND -> Triple(404, "IMAGE_NOT_FOUND", "Подтверждение оплаты не найдено")
            LifecycleProblem.EVIDENCE_BUSY -> Triple(409, "IMAGE_BUSY", "Изображение занято. Повторите операцию")
            // Legacy stock arithmetic overflow reaches its generic handler as an internal error.
            LifecycleProblem.STOCK_CONFLICT -> Triple(500, "INTERNAL_ERROR", "Внутренняя ошибка")
            LifecycleProblem.PRODUCT_NOT_FOUND -> Triple(404, "PRODUCT_NOT_FOUND", "Товар не найден")
            LifecycleProblem.PARTICIPANT_NOT_FOUND -> Triple(404, "PARTICIPANT_NOT_FOUND", "Пользователь не найден")
            LifecycleProblem.TRANSFER_NOT_FOUND -> Triple(404, "TRANSFER_NOT_FOUND", "Способ доставки не найден")
        }
        return ResponseEntity.status(status).body(LifecycleError(code, message, status, OffsetDateTime.now().toString()))
    }
}
