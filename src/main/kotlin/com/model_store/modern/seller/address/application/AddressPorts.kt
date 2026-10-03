package com.model_store.modern.seller.address.application

import com.model_store.modern.seller.address.domain.AddressFields
import com.model_store.modern.seller.address.domain.DeliveryAddress

/** Read contract for ordering: only an active address owned by this customer is visible. */
interface DeliveryAddressReadPort {
    fun findActiveOwned(customerId: Long, addressId: Long): DeliveryAddress?
}

interface AddressStore : DeliveryAddressReadPort {
    fun lockActiveOwned(ownerId: Long, addressId: Long): DeliveryAddress?
    fun regions(): List<String?>
    fun findByOwner(ownerId: Long): List<DeliveryAddress>
    fun create(ownerId: Long, fields: AddressFields): Long
    fun update(addressId: Long, fields: AddressFields): DeliveryAddress
    fun delete(ownerId: Long, addressId: Long)
}
