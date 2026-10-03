package com.model_store.modern.identity.participant.domain

import java.time.Instant

enum class ParticipantStatus { ACTIVE, WAITING_VERIFY, BLOCKED, DELETED }
enum class ParticipantRole { USER, ADMIN }
enum class SellerStatus { DEFAULT, VIP, PRO }

data class Participant(
    val id: Long,
    val login: String?,
    val mail: String?,
    val fullName: String?,
    val phoneNumber: String?,
    val status: ParticipantStatus,
    val role: ParticipantRole,
    val passwordHash: String,
    val deadlineSending: Int,
    val deadlinePayment: Int,
    val sellerStatus: SellerStatus?,
    val createdAt: Instant,
    val age: Int?,
    val isAgent: Boolean,
) {
    fun requireEditable() {
        if (status != ParticipantStatus.ACTIVE) throw ParticipantUnavailable()
        if (isAgent) throw AgentProfileForbidden()
    }

    fun withProfile(login: String?, fullName: String?, phoneNumber: String?, sending: Int?, payment: Int?): Participant {
        require(sending == null || sending in Short.MIN_VALUE..Short.MAX_VALUE) { "Некорректный срок отправки" }
        require(payment == null || payment in Short.MIN_VALUE..Short.MAX_VALUE) { "Некорректный срок оплаты" }
        return copy(login = login, fullName = fullName, phoneNumber = phoneNumber,
            deadlineSending = sending ?: deadlineSending, deadlinePayment = payment ?: deadlinePayment)
    }

    fun deleted(): Participant {
        if (status != ParticipantStatus.ACTIVE) throw ParticipantStatusUnavailable(id)
        return copy(status = ParticipantStatus.DELETED)
    }

    fun blocked(): Participant {
        if (status != ParticipantStatus.ACTIVE) throw ParticipantStatusUnavailable(id)
        return copy(status = ParticipantStatus.BLOCKED)
    }
}

class ParticipantUnavailable : RuntimeException("Такого пользователя не существует или он был заблокирован")
class ParticipantStatusUnavailable(id: Long) : RuntimeException("No participant found with id: $id")
class ParticipantPasswordNotFound : RuntimeException("Такого пользователя не существует")
class AgentProfileForbidden : RuntimeException("Профиль бота редактирует администратор")
class MailAlreadyRegistered : RuntimeException("Пользователь с таким email уже зарегистрирован")
class LoginAlreadyExists : RuntimeException("Нарушено ограничение уникальности")
class ParticipantImageNotFound(id: Long) : RuntimeException("Фотография с id $id; Не найдена или не активна")
class ParticipantAdminForbidden : RuntimeException("Доступ запрещён")
