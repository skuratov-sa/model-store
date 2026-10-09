package com.model_store.modern.identity.auth.api

import com.model_store.modern.identity.auth.domain.AuthFailure
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.time.OffsetDateTime

data class AuthApiError(val code: String, val message: String, val status: Int, val timestamp: String, val details: Any? = null)

@RestControllerAdvice(assignableTypes = [AuthController::class])
@Profile("modern")
class AuthErrorHandler {
    @ExceptionHandler(BadCredentialsException::class)
    fun credentials(): ResponseEntity<AuthApiError> = error(HttpStatus.UNAUTHORIZED, "BAD_CREDENTIALS", "Неверный логин или пароль")

    @ExceptionHandler(AuthFailure.WaitingVerify::class)
    fun waiting(ex: AuthFailure.WaitingVerify) = error(HttpStatus.UNAUTHORIZED, "WAITING_VERIFY", ex.message.orEmpty())

    @ExceptionHandler(AuthFailure.Blocked::class)
    fun locked() = error(HttpStatus.FORBIDDEN, "ACCOUNT_LOCKED", "Учетная запись заблокирована")

    @ExceptionHandler(AuthFailure.InvalidRefresh::class)
    fun invalidRefresh(ex: AuthFailure.InvalidRefresh) =
        error(HttpStatus.UNAUTHORIZED, "TOKEN_INVALID_OR_EXPIRED", ex.message.orEmpty())

    private fun error(status: HttpStatus, code: String, message: String) = ResponseEntity.status(status).body(
        AuthApiError(code, message, status.value(), OffsetDateTime.now().toString()),
    )
}
