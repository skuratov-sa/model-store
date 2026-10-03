package com.model_store.modern.seller.address.application

import com.model_store.modern.seller.address.domain.AddressFields
import com.model_store.modern.seller.address.domain.AddressNotFound
import com.model_store.modern.seller.address.domain.DeliveryAddress
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AddressUseCases(private val store: AddressStore) {
    @Transactional(readOnly = true)
    fun regions(): List<String?> = store.regions()

    @Transactional(readOnly = true)
    fun owned(ownerId: Long): List<DeliveryAddress> = store.findByOwner(ownerId)

    @Transactional
    fun create(ownerId: Long, fields: AddressFields): Long {
        fields.validate()
        return store.create(ownerId, fields)
    }

    @Transactional
    fun update(ownerId: Long, addressId: Long, fields: AddressFields): DeliveryAddress {
        store.lockActiveOwned(ownerId, addressId) ?: throw AddressNotFound()
        fields.validate()
        return store.update(addressId, fields)
    }

    @Transactional
    fun delete(ownerId: Long, addressId: Long) {
        store.lockActiveOwned(ownerId, addressId) ?: throw AddressNotFound()
        store.delete(ownerId, addressId)
    }
}
