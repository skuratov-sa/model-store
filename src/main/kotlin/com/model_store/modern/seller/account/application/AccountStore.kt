package com.model_store.modern.seller.account.application

import com.model_store.modern.seller.account.domain.AccountDetails
import com.model_store.modern.seller.account.domain.SellerAccount

interface AccountStore {
    fun lockForCreate(participantId: Long)
    fun byParticipant(participantId: Long): List<SellerAccount>
    fun byId(id: Long): SellerAccount?
    fun create(participantId: Long, details: AccountDetails)
    fun update(id: Long, participantId: Long, details: AccountDetails): SellerAccount
    fun delete(id: Long)
}

interface AccountActorRead {
    fun isAgent(participantId: Long): Boolean
}
