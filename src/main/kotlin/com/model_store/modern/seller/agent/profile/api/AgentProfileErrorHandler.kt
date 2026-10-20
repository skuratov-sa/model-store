package com.model_store.modern.seller.agent.profile.api

import com.model_store.modern.seller.agent.profile.application.AgentProfileDenied
import com.model_store.modern.identity.participant.application.AgentProfileFailure
import com.model_store.modern.identity.participant.domain.ParticipantImageNotFound
import com.model_store.modern.seller.address.domain.AddressNotFound
import com.model_store.modern.seller.account.domain.AccountAlreadyExists
import com.model_store.modern.seller.account.domain.AccountNotFound
import com.model_store.modern.seller.transfer.domain.TransferAlreadyExists
import com.model_store.modern.seller.transfer.domain.TransferNotFound
import com.model_store.modern.seller.social.domain.SocialNetworkAlreadyExists
import com.model_store.modern.seller.social.domain.SocialNetworkNotFound
import org.springframework.context.annotation.Profile
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.time.OffsetDateTime

data class AgentProfileError(val code: String, val message: String, val status: Int,
    val timestamp: String, val details: Any? = null)

@RestControllerAdvice(assignableTypes = [AgentProfileController::class])
@Profile("modern")
class AgentProfileErrorHandler {
    @ExceptionHandler(AgentProfileFailure.AdminForbidden::class)
    fun adminDenied(error: AgentProfileFailure.AdminForbidden) =
        response(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Доступ запрещён")

    @ExceptionHandler(AgentProfileFailure.AgentNotFound::class)
    fun profileMissing(error: AgentProfileFailure.AgentNotFound) =
        response(HttpStatus.NOT_FOUND, "PARTICIPANT_NOT_FOUND", "Бот не найден")

    @ExceptionHandler(AgentProfileFailure.InvalidDeadline::class)
    fun invalidDeadline(error: AgentProfileFailure.InvalidDeadline) =
        response(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Некорректный срок")

    @ExceptionHandler(ParticipantImageNotFound::class)
    fun imageMissing(error: ParticipantImageNotFound) =
        response(HttpStatus.NOT_FOUND, "NOT_FOUND", error.message.orEmpty())

    @ExceptionHandler(DataIntegrityViolationException::class)
    fun integrity(error: DataIntegrityViolationException) =
        response(HttpStatus.CONFLICT, "DUPLICATE_KEY", "Нарушено ограничение данных")

    @ExceptionHandler(AgentProfileDenied::class)
    fun denied(error: AgentProfileDenied) = response(HttpStatus.FORBIDDEN, "ACCESS_DENIED", error.message.orEmpty())

    @ExceptionHandler(AddressNotFound::class)
    fun addressMissing(error: AddressNotFound) = response(HttpStatus.NOT_FOUND, "ADDRESS_NOT_FOUND", error.message.orEmpty())

    @ExceptionHandler(AccountNotFound::class)
    fun accountMissing(error: AccountNotFound) = response(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", error.message.orEmpty())

    @ExceptionHandler(AccountAlreadyExists::class)
    fun accountDuplicate(error: AccountAlreadyExists) = response(HttpStatus.CONFLICT, "ACCOUNT_ALREADY_EXISTS", error.message.orEmpty())

    @ExceptionHandler(TransferNotFound::class)
    fun transferMissing(error: TransferNotFound) = response(HttpStatus.NOT_FOUND, "TRANSFER_NOT_FOUND", error.message.orEmpty())

    @ExceptionHandler(TransferAlreadyExists::class)
    fun transferDuplicate(error: TransferAlreadyExists) = response(HttpStatus.CONFLICT, "TRANSFER_NOT_FOUND", error.message.orEmpty())

    @ExceptionHandler(SocialNetworkNotFound::class)
    fun socialMissing(error: SocialNetworkNotFound) = response(HttpStatus.NOT_FOUND, "SOCIAL_NETWORK_NOT_FOUND", error.message.orEmpty())

    @ExceptionHandler(SocialNetworkAlreadyExists::class)
    fun socialDuplicate(error: SocialNetworkAlreadyExists) = response(HttpStatus.CONFLICT, "SOCIAL_NETWORK_ALREADY_EXISTS", error.message.orEmpty())

    @ExceptionHandler(IllegalArgumentException::class)
    fun invalid(error: IllegalArgumentException) = response(HttpStatus.BAD_REQUEST, "BAD_REQUEST", error.message.orEmpty())

    private fun response(status: HttpStatus, code: String, message: String): ResponseEntity<AgentProfileError> =
        ResponseEntity.status(status).body(AgentProfileError(code, message, status.value(), OffsetDateTime.now().toString()))
}
