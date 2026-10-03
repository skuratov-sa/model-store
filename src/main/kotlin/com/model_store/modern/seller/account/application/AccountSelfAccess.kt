package com.model_store.modern.seller.account.application

import com.model_store.modern.seller.account.domain.AccountAccessDenied
import com.model_store.modern.seller.account.domain.AgentAccountDenied
import com.model_store.modern.shared.domain.Actor
import org.springframework.stereotype.Service

@Service
class AccountSelfAccess(private val actors: AccountActorRead) {
    fun requireRegularOwner(actor: Actor?): Long {
        val id = actor?.participantId ?: throw AccountAccessDenied()
        if (actors.isAgent(id)) throw AgentAccountDenied()
        return id
    }
}
