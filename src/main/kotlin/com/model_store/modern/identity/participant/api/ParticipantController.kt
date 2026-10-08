package com.model_store.modern.identity.participant.api

import com.model_store.modern.identity.participant.application.*
import com.model_store.modern.shared.domain.Actor
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import java.time.OffsetDateTime

data class RegisterParticipantRequest(
    val mail: String? = null,
    val password: String? = null,
    @field:NotNull(message = "Возраст обязателен")
    @field:Min(value = 0, message = "Возраст не может быть отрицательным")
    @field:Max(value = 150, message = "Некорректный возраст")
    val age: Int? = null,
)

data class UpdateParticipantRequest(
    val login: String? = null,
    val fullName: String? = null,
    val phoneNumber: String? = null,
    val deadlineSending: Int? = null,
    val deadlinePayment: Int? = null,
    val imageId: Long? = null,
)

data class FindParticipantRequest(val id: Long? = null, val name: String? = null)

@RestController
@Profile("modern")
class ParticipantController(
    private val register: RegisterParticipant,
    private val registrationMail: ParticipantRegistrationMail,
    private val read: ReadParticipant,
    private val update: UpdateParticipant,
    private val delete: DeleteParticipant,
    private val block: BlockParticipant,
    private val changePassword: ChangeParticipantPassword,
) {
    @GetMapping("/participant")
    fun current(@AuthenticationPrincipal actor: Actor?): FullProfile? = read.current(actor.requireId())

    @PostMapping("/participants/find")
    fun search(@RequestBody request: FindParticipantRequest): List<ParticipantSearchResult> =
        read.search(request.id, request.name)

    @PostMapping("/participant")
    @Transactional(transactionManager = "transactionManager", propagation = Propagation.NOT_SUPPORTED)
    fun register(@Valid @RequestBody request: RegisterParticipantRequest): Long {
        val id = register.execute(request.mail, request.password, request.age)
        try {
            registrationMail.send(id)
        } catch (failure: RuntimeException) {
            throw RegistrationMailFailure(failure)
        }
        return id
    }

    @ExceptionHandler(RegistrationMailFailure::class)
    fun registrationMailFailure(): ResponseEntity<ParticipantError> = ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(ParticipantError("INTERNAL_ERROR", "Внутренняя ошибка", 500, OffsetDateTime.now().toString()))

    @PutMapping("/participant")
    fun update(@AuthenticationPrincipal actor: Actor?, @RequestBody request: UpdateParticipantRequest): Long =
        update.execute(actor.requireId(), request.login, request.fullName, request.phoneNumber,
            request.deadlineSending, request.deadlinePayment, request.imageId)

    @DeleteMapping("/participant")
    fun delete(@AuthenticationPrincipal actor: Actor?) = delete.execute(actor.requireId())

    @PutMapping("/participant/password")
    fun password(@AuthenticationPrincipal actor: Actor?, @RequestParam oldPassword: String,
                 @RequestParam newPassword: String): Long =
        changePassword.execute(actor.requireId(), oldPassword, newPassword)

    @PutMapping("/admin/actions/participants/{participantId}/status")
    fun block(@AuthenticationPrincipal actor: Actor?, @PathVariable participantId: Long) {
        block.execute(actor ?: throw ParticipantAccessDenied(), participantId)
    }

    private fun Actor?.requireId() = this?.participantId ?: throw ParticipantAccessDenied()
}

class ParticipantAccessDenied : RuntimeException("Доступ запрещён")
private class RegistrationMailFailure(cause: RuntimeException) : RuntimeException("Registration mail failed", cause)
