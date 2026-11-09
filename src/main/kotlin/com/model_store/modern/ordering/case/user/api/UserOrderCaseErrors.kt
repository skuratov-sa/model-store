package com.model_store.modern.ordering.`case`.user.api

import com.model_store.modern.ordering.`case`.user.domain.*
import com.model_store.modern.ordering.lifecycle.application.LifecycleTransactionRequired
import org.springframework.context.annotation.Profile
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.sql.SQLException
import java.time.OffsetDateTime

data class UserCaseError(val code: String, val message: String, val status: Int, val timestamp: String, val details: Any? = null)

@RestControllerAdvice(assignableTypes = [UserOrderCaseController::class])
@Profile("modern")
class UserOrderCaseErrors {
    @ExceptionHandler(CaseFailure::class)
    fun failure(error: CaseFailure): ResponseEntity<UserCaseError> {
        if (error.problem == CaseProblem.AGENT_ACCOUNT) return ResponseEntity.status(403).build()
        val (status, code, message) = when (error.problem) {
            CaseProblem.COMMENT_REQUIRED -> Triple(400,"BAD_REQUEST","Укажите причину обращения")
            CaseProblem.INVALID -> Triple(400,"BAD_REQUEST","Операция недоступна для текущего состояния заказа или обращения")
            CaseProblem.INVALID_TRANSITION -> Triple(400,"ORDER_CASE_INVALID_TRANSITION","Операция недоступна для текущего состояния заказа или обращения")
            CaseProblem.ALREADY_OPEN -> Triple(409,"ORDER_CASE_ALREADY_OPEN","По заказу уже открыто обращение")
            CaseProblem.APPEAL_EXISTS -> Triple(409,"PAYMENT_APPEAL_ALREADY_EXISTS","Обращение по оплате уже создано")
            CaseProblem.ACCESS_DENIED -> Triple(403,"ACCESS_DENIED","Доступ запрещён")
            CaseProblem.INVALID_ACTOR -> Triple(401,"TOKEN_INVALID_OR_EXPIRED","Не удалось распознать пользователя")
            CaseProblem.IMAGE_BUSY -> Triple(409,"IMAGE_BUSY","Изображение занято. Повторите операцию")
            CaseProblem.AGENT_ACCOUNT -> return ResponseEntity.status(403).build()
        }
        return response(status,code,message)
    }
    @ExceptionHandler(LifecycleTransactionRequired::class)
    fun transaction() = response(500,"INTERNAL_ERROR","Внутренняя ошибка")

    @ExceptionHandler(DataIntegrityViolationException::class)
    fun integrity(error: DataIntegrityViolationException): ResponseEntity<UserCaseError> {
        val root = generateSequence<Throwable>(error) { it.cause }.last()
        if ((root as? SQLException)?.sqlState in setOf("22001","22003")) return response(500,"INTERNAL_ERROR","Внутренняя ошибка")
        val reference = root.message.orEmpty().contains("foreign key constraint")
        return response(if(reference) 400 else 409, if(reference) "INVALID_REFERENCE" else "DUPLICATE_KEY",
            if(reference) "Указана ссылка на несуществующий объект" else "Нарушено ограничение уникальности",mapOf("dbMessage" to root.message.orEmpty()))
    }
    private fun response(status: Int, code: String, message: String, details: Any? = null) = ResponseEntity.status(status).body(
        UserCaseError(code,message,status,OffsetDateTime.now().toString(),details))
}
