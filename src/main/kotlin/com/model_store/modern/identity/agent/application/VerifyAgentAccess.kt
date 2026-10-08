package com.model_store.modern.identity.agent.application

import com.model_store.modern.identity.agent.domain.AgentTokenFailure
import com.model_store.modern.identity.auth.application.AuthAccounts
import com.model_store.modern.identity.auth.domain.AuthStatus
import com.model_store.modern.shared.domain.Actor
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service

/** Agent scenarios call this after shared security has verified the signature and issuedBy claim. */
@Service
@Profile("modern")
class VerifyAgentAccess(private val accounts: AuthAccounts) {
    fun requireActiveAgent(actor: Actor?): Long {
        if (actor?.isAgentAccess != true) throw AgentTokenFailure.InvalidAgentAccess()
        val id = actor.participantId ?: throw AgentTokenFailure.InvalidAgentAccess()
        val account = accounts.byId(id) ?: throw AgentTokenFailure.InvalidAgentAccess()
        if (!account.isAgent || account.status != AuthStatus.ACTIVE) throw AgentTokenFailure.InvalidAgentAccess()
        return id
    }
}
