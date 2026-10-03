package com.model_store.modern.seller.address.domain

enum class AddressStatus { ACTIVE, DELETED }

data class DeliveryAddress(
    val id: Long,
    val country: String?,
    val city: String?,
    val street: String?,
    val houseNumber: String?,
    val apartmentNumber: String?,
    val index: Int?,
    val status: AddressStatus,
) {
    val fullAddress: String
        get() = "$country г. $city ул. $street $houseNumber кв. $apartmentNumber, индекс: $index"
}

data class AddressFields(
    val country: String?,
    val city: String?,
    val street: String?,
    val houseNumber: String?,
    val apartmentNumber: String?,
    val index: Int?,
) {
    fun validate() {
        require(country == null || country.length <= 255) { "Слишком длинное название страны" }
        require(city == null || city.length <= 255) { "Слишком длинное название города" }
        require(street == null || street.length <= 255) { "Слишком длинное название улицы" }
        require(houseNumber == null || houseNumber.length <= 20) { "Слишком длинный номер дома" }
        require(apartmentNumber == null || apartmentNumber.length <= 20) { "Слишком длинный номер квартиры" }
    }
}

class AddressNotFound : RuntimeException("Адрес доставки не найден")
class AddressAccessDenied : RuntimeException("Доступ запрещён")
