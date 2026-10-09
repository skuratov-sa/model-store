package com.model_store.modern.identity.auth.domain

enum class AuthStatus { ACTIVE, WAITING_VERIFY, BLOCKED, DELETED }

data class AuthAccount(
    val id: Long,
    val login: String?,
    val mail: String?,
    val fullName: String?,
    val passwordHash: String,
    val role: String,
    val status: AuthStatus,
    val imageId: Long?,
    val isAgent: Boolean,
) {
    override fun toString(): String = "AuthAccount(id=$id, status=$status, passwordHash=[REDACTED])"
}

sealed class AuthFailure(message: String) : RuntimeException(message) {
    class WaitingVerify : AuthFailure("Необходимо подтвердить почту")
    class Blocked : AuthFailure("Учетная запись заблокирована")
    class InvalidRefresh : AuthFailure("Token недействителен или срок его действия истек")
}
