package com.model_store.modern.identity.verification.api

import com.model_store.modern.identity.verification.application.VerificationUseCases
import com.model_store.modern.identity.verification.application.VerificationTokenIssuer
import com.model_store.modern.identity.verification.domain.VerificationMailNotFound
import com.model_store.modern.identity.verification.domain.VerificationParticipantNotFound
import com.model_store.modern.identity.verification.domain.VerificationRateLimited
import com.model_store.modern.identity.verification.domain.VerificationCodeInvalid
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.bind.annotation.*
import java.time.OffsetDateTime

@RestController
@Profile("modern")
@RequestMapping("/auth")
class VerificationController(private val useCases: VerificationUseCases) {
    @PostMapping("/verification/resend")
    fun resend(@RequestParam email: String): Long = useCases.resend(email)

    @PostMapping("/password/reset")
    fun resetPassword(@RequestParam email: String) = useCases.resetPassword(email)
}

data class VerifyCodeRequest(val userId: Long, val code: String) {
    override fun toString(): String = "VerifyCodeRequest(userId=$userId, code=[REDACTED])"
}

@RestController
@Profile("modern")
class VerifyCodeController(
    private val useCases: VerificationUseCases,
    private val tokens: ObjectProvider<VerificationTokenIssuer>,
) {
    @PostMapping("/auth/verify-code")
    fun verify(@RequestBody request: VerifyCodeRequest): ResponseEntity<Map<String, String>> {
        val issuer = tokens.ifAvailable
            ?: throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Сервис временно недоступен")
        try {
            useCases.verify(request.userId, request.code)
        } catch (_: VerificationCodeInvalid) {
            return ResponseEntity.badRequest().body(mapOf("error" to "Неверный код подтверждения"))
        }
        return ResponseEntity.ok(issuer.issue(request.userId))
    }
}

data class VerificationApiError(
    val code: String, val message: String, val status: Int, val timestamp: String, val details: Any? = null,
)

@RestControllerAdvice(assignableTypes = [VerificationController::class, VerifyCodeController::class])
@Profile("modern")
class VerificationErrorHandler {
    @ExceptionHandler(VerificationMailNotFound::class)
    fun missing(ex: VerificationMailNotFound) = error(HttpStatus.BAD_REQUEST, "BAD_REQUEST", ex.message.orEmpty())

    @ExceptionHandler(VerificationParticipantNotFound::class)
    fun participantMissing(ex: VerificationParticipantNotFound) =
        error(HttpStatus.NOT_FOUND, "PARTICIPANT_NOT_FOUND", ex.message.orEmpty())

    @ExceptionHandler(VerificationRateLimited::class)
    fun limited(ex: VerificationRateLimited) = error(HttpStatus.TOO_MANY_REQUESTS, ex.code, ex.message.orEmpty())

    @ExceptionHandler(ResponseStatusException::class)
    fun unavailable() = error(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", "Сервис временно недоступен")

    @ExceptionHandler(Exception::class)
    fun failure() = error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Внутренняя ошибка")

    private fun error(status: HttpStatus, code: String, message: String) = ResponseEntity.status(status).body(
        VerificationApiError(code, message, status.value(), OffsetDateTime.now().toString()),
    )
}
