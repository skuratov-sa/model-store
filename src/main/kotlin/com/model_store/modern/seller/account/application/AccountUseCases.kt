package com.model_store.modern.seller.account.application

import com.model_store.modern.seller.account.domain.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AccountUseCases(private val store: AccountStore) {
    @Transactional("transactionManager")
    fun create(participantId: Long, details: AccountDetails) {
        store.lockForCreate(participantId)
        // Legacy compares entity_value, including a null value, rather than the payment type.
        if (store.byParticipant(participantId).any { it.entityValue == details.entityValue })
            throw AccountAlreadyExists()
        store.create(participantId, details)
    }

    @Transactional("transactionManager", readOnly = true)
    fun list(participantId: Long): List<SellerAccount> =
        store.byParticipant(participantId).ifEmpty { throw AccountNotFound() }

    @Transactional("transactionManager")
    fun update(participantId: Long, id: Long, details: AccountDetails): SellerAccount {
        val current = store.byId(id)?.takeIf { it.participantId == participantId } ?: throw AccountNotFound()
        return store.update(current.id, participantId, details)
    }

    @Transactional("transactionManager")
    fun delete(participantId: Long, id: Long) {
        val current = store.byId(id)?.takeIf { it.participantId == participantId } ?: throw AccountNotFound()
        store.delete(current.id)
    }
}
