package com.model_store.modern.reputation.api

import com.model_store.modern.reputation.domain.*
import com.model_store.modern.reputation.application.ReviewTransactionRequired
import org.springframework.context.annotation.Profile
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.time.OffsetDateTime
import org.springframework.dao.DataIntegrityViolationException
import java.sql.SQLException

data class ReviewError(val code: String, val message: String, val status: Int, val timestamp: String, val details: Any? = null)

@RestControllerAdvice(assignableTypes = [ReviewController::class])
@Profile("modern")
class ReviewErrors {
    @ExceptionHandler(org.springframework.web.bind.MethodArgumentNotValidException::class)
    fun validation(error: org.springframework.web.bind.MethodArgumentNotValidException) = response(400,"VALIDATION_ERROR","Ошибка валидации",
        mapOf("errors" to error.bindingResult.fieldErrors.map { "${it.field}: ${it.defaultMessage}" }))

    @ExceptionHandler(ReviewFailure::class)
    fun failure(error: ReviewFailure): ResponseEntity<*> {
        if(error.problem==ReviewProblem.BOT_ACTOR) return ResponseEntity.status(403).build<Void>()
        val (status,code,message) = when(error.problem) {
            ReviewProblem.ALREADY_EXISTS -> Triple(400,"REVIEW_ALREADY_EXIST","Вы уже оставили отзыв для этого заказа")
            ReviewProblem.ORDER_NOT_FOUND -> Triple(404,"ORDER_NOT_FOUND","Нет данных о заказе товара")
            ReviewProblem.ORDER_NOT_TERMINATED -> Triple(400,"ORDER_NOT_TERMINATED","Нельзя оставить отзыв пока заказ еще активен")
            ReviewProblem.BOT_ACTOR -> error("handled above")
            ReviewProblem.INVALID_ACTOR -> Triple(401,"TOKEN_INVALID_OR_EXPIRED","Не удалось распознать пользователя")
        }
        return response(status,code,message)
    }

    @ExceptionHandler(ReviewTransactionRequired::class)
    fun transactionRequired() = response(500,"INTERNAL_ERROR","Внутренняя ошибка")

    @ExceptionHandler(DataIntegrityViolationException::class)
    fun integrity(error: DataIntegrityViolationException): ResponseEntity<ReviewError> {
        val root = generateSequence<Throwable>(error) { it.cause }.last()
        if ((root as? SQLException)?.sqlState in setOf("22001","22003")) return response(500,"INTERNAL_ERROR","Внутренняя ошибка")
        val reference = root.message.orEmpty().contains("foreign key constraint")
        return response(if(reference) 400 else 409,if(reference) "INVALID_REFERENCE" else "DUPLICATE_KEY",
            if(reference) "Указана ссылка на несуществующий объект" else "Нарушено ограничение уникальности",
            mapOf("dbMessage" to root.message.orEmpty()))
    }
    private fun response(status: Int, code: String, message: String, details: Any? = null) = ResponseEntity.status(status)
        .body(ReviewError(code,message,status,OffsetDateTime.now().toString(),details))
}
