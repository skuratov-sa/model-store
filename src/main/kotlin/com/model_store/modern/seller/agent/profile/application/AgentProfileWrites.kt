package com.model_store.modern.seller.agent.profile.application

import com.model_store.modern.identity.participant.application.AgentAccessLock
import com.model_store.modern.seller.account.application.AccountUseCases
import com.model_store.modern.seller.account.domain.AccountDetails
import com.model_store.modern.seller.address.application.AddressUseCases
import com.model_store.modern.seller.address.domain.AddressFields
import com.model_store.modern.seller.social.application.SocialNetworkUseCases
import com.model_store.modern.seller.social.domain.SocialNetworkFields
import com.model_store.modern.seller.transfer.application.TransferUseCases
import com.model_store.modern.seller.transfer.domain.TransferFields
import com.model_store.modern.shared.domain.Actor
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** Keeps the identity row locks until the owner write and this transaction both finish. */
@Service
@Profile("modern")
class AgentProfileWrites(
    private val access: AgentProfileAccess,
    private val lock: AgentAccessLock,
    private val addresses: AddressUseCases,
    private val accounts: AccountUseCases,
    private val transfers: TransferUseCases,
    private val social: SocialNetworkUseCases,
) {
    @Transactional("transactionManager")
    fun createAddress(actor: Actor?, agentId: Long, fields: AddressFields) =
        authorized(actor, agentId) { addresses.create(agentId, fields) }

    @Transactional("transactionManager")
    fun updateAddress(actor: Actor?, agentId: Long, id: Long, fields: AddressFields) =
        authorized(actor, agentId) { addresses.update(agentId, id, fields) }

    @Transactional("transactionManager")
    fun deleteAddress(actor: Actor?, agentId: Long, id: Long) =
        authorized(actor, agentId) { addresses.delete(agentId, id) }

    @Transactional("transactionManager")
    fun createAccount(actor: Actor?, agentId: Long, details: AccountDetails) =
        authorized(actor, agentId) { accounts.create(agentId, details) }

    @Transactional("transactionManager")
    fun updateAccount(actor: Actor?, agentId: Long, id: Long, details: AccountDetails) =
        authorized(actor, agentId) { accounts.update(agentId, id, details) }

    @Transactional("transactionManager")
    fun deleteAccount(actor: Actor?, agentId: Long, id: Long) =
        authorized(actor, agentId) { accounts.delete(agentId, id) }

    @Transactional("transactionManager")
    fun createTransfer(actor: Actor?, agentId: Long, fields: TransferFields) =
        authorized(actor, agentId) { transfers.create(agentId, fields) }

    @Transactional("transactionManager")
    fun updateTransfer(actor: Actor?, agentId: Long, id: Long, fields: TransferFields) =
        authorized(actor, agentId) { transfers.update(agentId, id, fields) }

    @Transactional("transactionManager")
    fun deleteTransfer(actor: Actor?, agentId: Long, id: Long) =
        authorized(actor, agentId) { transfers.delete(agentId, id) }

    @Transactional("transactionManager")
    fun createSocialNetwork(actor: Actor?, agentId: Long, fields: SocialNetworkFields) =
        authorized(actor, agentId) { social.create(agentId, fields) }

    @Transactional("transactionManager")
    fun updateSocialNetwork(actor: Actor?, agentId: Long, id: Long, fields: SocialNetworkFields) =
        authorized(actor, agentId) { social.update(agentId, id, fields) }

    @Transactional("transactionManager")
    fun deleteSocialNetwork(actor: Actor?, agentId: Long, id: Long) =
        authorized(actor, agentId) { social.delete(agentId, id) }

    private fun <T> authorized(actor: Actor?, agentId: Long, write: () -> T): T {
        lock.lockActiveAdminAndAgent(access.requireAdminActor(actor), agentId)
        return write()
    }
}
