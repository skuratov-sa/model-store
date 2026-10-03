package com.model_store.modern.identity.participant.application

import com.model_store.modern.identity.participant.domain.*
import com.model_store.modern.shared.domain.Actor
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class RegisterParticipant(private val store: ParticipantStore, private val passwords: ParticipantPasswords) {

    @Transactional("transactionManager")
    fun execute(mail: String?, password: String?, age: Int?): Long {
        require(age != null && age in 0..150) { "Некорректный возраст" }
        require(!password.isNullOrEmpty()) { "Пароль обязателен" }
        if (mail != null && store.mailExists(mail))
            throw MailAlreadyRegistered()
        return store.create(mail, passwords.hash(requireNotNull(password)), age).id
    }
}

@Service
class ReadParticipant(private val store: ParticipantStore, private val profiles: ParticipantProfileRead) {
    @Transactional("transactionManager", readOnly = true)
    fun current(id: Long): FullProfile? = store.findActive(id)?.let(profiles::fullProfile)

    @Transactional("transactionManager", readOnly = true)
    fun search(id: Long?, name: String?): List<ParticipantSearchResult> = profiles.search(id, name)
}

@Service
class UpdateParticipant(private val store: ParticipantStore, private val images: ParticipantImagePort) {
    @Transactional("transactionManager")
    fun execute(id: Long, login: String?, fullName: String?, phoneNumber: String?,
                deadlineSending: Int?, deadlinePayment: Int?, imageId: Long?): Long {
        val participant = store.findForUpdate(id) ?: throw ParticipantUnavailable()
        participant.requireEditable()
        if (login != null && store.loginExistsForAnother(login, id))
            throw LoginAlreadyExists()
        if (imageId != null) images.replace(id, imageId)
        return store.save(participant.withProfile(login, fullName, phoneNumber, deadlineSending, deadlinePayment)).id
    }
}

@Service
class DeleteParticipant(private val store: ParticipantStore) {
    @Transactional("transactionManager")
    fun execute(id: Long) {
        store.save((store.findForUpdate(id) ?: throw ParticipantStatusUnavailable(id)).deleted())
    }
}

@Service
class BlockParticipant(private val store: ParticipantStore) {
    @Transactional("transactionManager")
    fun execute(actor: Actor, id: Long) {
        if (actor.role != "ADMIN") throw ParticipantAdminForbidden()
        store.save((store.findForUpdate(id) ?: throw ParticipantStatusUnavailable(id)).blocked())
    }
}

@Service
class ChangeParticipantPassword(private val store: ParticipantStore, private val passwords: ParticipantPasswords) {

    @Transactional("transactionManager")
    fun execute(id: Long, oldPassword: String, newPassword: String): Long {
        val participant = store.findForUpdate(id) ?: throw ParticipantPasswordNotFound()
        if (!passwords.matches(oldPassword, participant.passwordHash)) throw BadCredentialsException(id.toString())
        require(newPassword.isNotEmpty()) { "Новый пароль обязателен" }
        store.save(participant.copy(passwordHash = passwords.hash(newPassword)))
        return id
    }
}
