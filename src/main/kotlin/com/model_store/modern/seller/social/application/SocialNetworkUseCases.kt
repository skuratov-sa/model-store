package com.model_store.modern.seller.social.application

import com.model_store.modern.seller.social.domain.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

interface SocialNetworkStore {
    fun lockOwner(ownerId: Long)
    fun existsType(ownerId: Long, type: SocialNetworkType): Boolean
    fun list(ownerId: Long): List<SocialNetwork>
    fun lockOwned(ownerId: Long, id: Long): SocialNetwork?
    fun create(ownerId: Long, fields: SocialNetworkFields)
    fun update(id: Long, fields: SocialNetworkFields): SocialNetwork
    fun delete(id: Long)
}

@Service
class SocialNetworkUseCases(private val store: SocialNetworkStore) {
    @Transactional(readOnly = true)
    fun owned(ownerId: Long): List<SocialNetwork> = store.list(ownerId)
        .ifEmpty { throw SocialNetworkNotFound("Социальные сети отсутствуют") }

    @Transactional
    fun create(ownerId: Long, fields: SocialNetworkFields) {
        fields.validate()
        store.lockOwner(ownerId)
        if (store.existsType(ownerId, requireNotNull(fields.type))) throw SocialNetworkAlreadyExists()
        store.create(ownerId, fields)
    }

    @Transactional
    fun update(ownerId: Long, id: Long, fields: SocialNetworkFields): SocialNetwork {
        store.lockOwned(ownerId, id) ?: throw SocialNetworkNotFound()
        fields.validate()
        return store.update(id, fields)
    }

    @Transactional
    fun delete(ownerId: Long, id: Long) {
        store.lockOwned(ownerId, id) ?: throw SocialNetworkNotFound()
        store.delete(id)
    }
}
