package com.model_store.modern.identity.agent.application

import com.model_store.modern.identity.agent.domain.AgentTokenFailure
import com.model_store.modern.identity.agent.domain.AgentTokenLifetime
import com.model_store.modern.identity.auth.application.AuthAccounts
import com.model_store.modern.identity.auth.domain.AuthAccount
import com.model_store.modern.identity.auth.domain.AuthStatus
import com.model_store.modern.shared.domain.Actor
import com.model_store.modern.shared.domain.TokenType
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service
import java.time.Instant

data class AgentTokens(
    val accessToken: String,
    val accessTokenExpiresAt: Instant,
    val refreshToken: String,
    val refreshTokenExpiresAt: Instant,
)

interface AgentTokenSigner {
    fun issue(account: AuthAccount, lifetime: AgentTokenLifetime): AgentTokens
}

@Service
@Profile("modern")
class IssueAgentTokens(private val accounts: AuthAccounts, private val signer: AgentTokenSigner) {
    fun issue(actor: Actor?, participantId: Long, accessMinutes: Int, refreshDays: Int): AgentTokens {
        if (actor?.tokenType != TokenType.ACCESS || actor.role != "ADMIN") throw AgentTokenFailure.AccessDenied()
        val caller = actor.participantId?.let(accounts::byId) ?: throw AgentTokenFailure.AccessDenied()
        if (caller.role != "ADMIN" || caller.status != AuthStatus.ACTIVE || caller.isAgent) {
            throw AgentTokenFailure.AccessDenied()
        }
        val lifetime = AgentTokenLifetime.from(accessMinutes, refreshDays)
        val account = accounts.byId(participantId)?.takeIf { it.isAgent }
            ?: throw AgentTokenFailure.AgentNotFound()
        if (account.status != AuthStatus.ACTIVE) throw AgentTokenFailure.AgentInactive()
        return signer.issue(account, lifetime)
    }
}
