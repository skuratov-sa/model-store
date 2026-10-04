package com.model_store.modern.seller.transfer.domain

enum class ShippingMethod { PRODUCT_PICKUP, TRANSPORT_COMPANY, RUSSIAN_POST, FREE_POST }
// CREATE exists in the legacy HTTP enum, although PostgreSQL's currency type omits it.
enum class TransferCurrency { USD, EUR, GBP, JPY, CNY, RUB, CREATE }
enum class TransferStatus { ACTIVE, DELETED }

data class TransferFields(val sending: ShippingMethod?, val price: Int?, val currency: TransferCurrency?) {
    fun validate() {
        requireNotNull(sending) { "Не указан способ доставки" }
        requireNotNull(price) { "Не указана цена доставки" }
        requireNotNull(currency) { "Не указана валюта" }
        require(currency != TransferCurrency.CREATE) { "Валюта CREATE не поддерживается" }
    }
}

data class TransferMethod(
    val id: Long, val sending: ShippingMethod, val price: Int,
    val currency: TransferCurrency, val participantId: Long, val status: TransferStatus,
)

class TransferNotFound(message: String = "Способ доставки не найден") : RuntimeException(message)
class TransferAlreadyExists : RuntimeException("Такой способ доставки уже добавлен")
class TransferAccessDenied : RuntimeException("Доступ запрещён")
