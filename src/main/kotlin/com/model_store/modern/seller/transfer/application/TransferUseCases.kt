package com.model_store.modern.seller.transfer.application

import com.model_store.modern.seller.transfer.domain.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

interface TransferStore {
    fun lockOwner(ownerId: Long)
    fun existsActiveType(ownerId: Long, sending: ShippingMethod): Boolean
    fun list(ownerId: Long): List<TransferMethod>
    fun lockOwned(ownerId: Long, id: Long): TransferMethod?
    fun create(ownerId: Long, fields: TransferFields): TransferMethod
    fun update(id: Long, fields: TransferFields): TransferMethod
    fun softDelete(id: Long)
}

@Service
class TransferUseCases(private val store: TransferStore) {
    @Transactional(readOnly = true)
    fun owned(ownerId: Long): List<TransferMethod> = store.list(ownerId).filter { it.status == TransferStatus.ACTIVE }
        .ifEmpty { throw TransferNotFound("Способ доставки не найден") }

    @Transactional
    fun create(ownerId: Long, fields: TransferFields) {
        fields.validate()
        store.lockOwner(ownerId)
        if (store.existsActiveType(ownerId, requireNotNull(fields.sending))) throw TransferAlreadyExists()
        store.create(ownerId, fields)
    }

    @Transactional
    fun update(ownerId: Long, id: Long, fields: TransferFields): TransferMethod {
        store.lockOwned(ownerId, id)?.takeIf { it.status == TransferStatus.ACTIVE } ?: throw TransferNotFound()
        fields.validate()
        return store.update(id, fields)
    }

    @Transactional
    fun delete(ownerId: Long, id: Long) {
        // Legacy deletion accepts an already deleted row owned by the same participant.
        store.lockOwned(ownerId, id) ?: throw TransferNotFound()
        store.softDelete(id)
    }
}
