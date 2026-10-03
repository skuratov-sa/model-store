package com.model_store.modern.shared.domain

/** Identity and role carried by a verified API token. */
data class Actor(
    val participantId: Long?,
    val login: String?,
    val role: String?,
    val tokenType: TokenType = TokenType.ACCESS,
) {
    val isAgentAccess: Boolean get() = tokenType == TokenType.AGENT_ACCESS
}

enum class TokenType(val claim: String) {
    ACCESS("access"),
    AGENT_ACCESS("agent_access");

    companion object {
        fun fromClaim(value: String?): TokenType? = entries.firstOrNull { it.claim == value }
    }
}
