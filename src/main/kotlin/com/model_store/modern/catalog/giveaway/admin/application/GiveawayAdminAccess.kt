package com.model_store.modern.catalog.giveaway.admin.application

import com.model_store.modern.catalog.giveaway.admin.domain.GiveawayCaller
import com.model_store.modern.identity.participant.application.CurrentAdminForbidden
import com.model_store.modern.identity.participant.application.CurrentAdminLock
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service

/** Identity owns current-state authorization and the lock; catalog owns its error contract. */
@Service
@Profile("modern")
class GiveawayAdminAccess(private val currentAdmin: CurrentAdminLock) {
    fun lock(caller: GiveawayCaller,
             deniedMessage: String = "Управление розыгрышами доступно только администратору"): Long {
        val id = caller.participantId?.takeIf { it > 0 && caller.role == "ADMIN" && !caller.agentAccess }
            ?: denied(deniedMessage)
        try {
            currentAdmin.lockActiveAdmin(id)
        } catch (_: CurrentAdminForbidden) {
            denied(deniedMessage)
        }
        return id
    }

    private fun denied(message: String): Nothing = throw GiveawayFailure(GiveawayFailureKind.FORBIDDEN,
        "ACCESS_DENIED", message)
}
