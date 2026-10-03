package com.model_store.modern.identity.participant.api

import com.model_store.modern.identity.participant.domain.*
import org.springframework.context.annotation.Profile
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.time.OffsetDateTime

data class ParticipantError(
    val code: String, val message: String, val status: Int, val timestamp: String,
    val details: Any? = null,
)

@RestControllerAdvice(assignableTypes = [ParticipantController::class])
@Profile("modern")
class ParticipantErrorHandler {
    @ExceptionHandler(ParticipantUnavailable::class)
    fun unavailable(ex: ParticipantUnavailable) = error(HttpStatus.NOT_FOUND, "PARTICIPANT_NOT_FOUND", ex.message.orEmpty())

    @ExceptionHandler(ParticipantStatusUnavailable::class)
    fun statusUnavailable(ex: ParticipantStatusUnavailable) = error(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.message.orEmpty())

    @ExceptionHandler(ParticipantPasswordNotFound::class)
    fun passwordNotFound(ex: ParticipantPasswordNotFound) = error(HttpStatus.NOT_FOUND, "PARTICIPANT_NOT_FOUND", ex.message.orEmpty())

    @ExceptionHandler(AgentProfileForbidden::class, ParticipantAccessDenied::class, ParticipantAdminForbidden::class)
    fun denied(ex: RuntimeException) = error(HttpStatus.FORBIDDEN, "ACCESS_DENIED", ex.message.orEmpty())

    @ExceptionHandler(MailAlreadyRegistered::class)
    fun mailExists(ex: MailAlreadyRegistered) = error(HttpStatus.NOT_FOUND, "PARTICIPANT_ALREADY_EXISTS", ex.message.orEmpty())

    @ExceptionHandler(LoginAlreadyExists::class)
    fun loginExists(ex: LoginAlreadyExists) = error(HttpStatus.CONFLICT, "DUPLICATE_KEY", ex.message.orEmpty())

    @ExceptionHandler(ParticipantImageNotFound::class)
    fun imageNotFound(ex: ParticipantImageNotFound) = error(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.message.orEmpty())

    @ExceptionHandler(DataIntegrityViolationException::class)
    fun integrity(ex: DataIntegrityViolationException) = error(HttpStatus.CONFLICT, "DUPLICATE_KEY", "Нарушено ограничение уникальности")

    @ExceptionHandler(BadCredentialsException::class)
    fun credentials(ex: BadCredentialsException) = error(HttpStatus.UNAUTHORIZED, "BAD_CREDENTIALS", "Неверный логин или пароль")

    @ExceptionHandler(IllegalArgumentException::class)
    fun invalid(ex: IllegalArgumentException) = error(HttpStatus.BAD_REQUEST, "BAD_REQUEST", ex.message.orEmpty())

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun validation(ex: MethodArgumentNotValidException) = ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
        ParticipantError("VALIDATION_ERROR", "Ошибка валидации", 400, OffsetDateTime.now().toString(),
            mapOf("errors" to ex.bindingResult.fieldErrors.map { "${it.field}: ${it.defaultMessage}" })),
    )

    private fun error(status: HttpStatus, code: String, message: String) =
        ResponseEntity.status(status).body(ParticipantError(code, message, status.value(), OffsetDateTime.now().toString()))
}
