package com.model_store.modern.catalog.dictionary.domain

/** Dictionary groups accepted by the public query. */
enum class DictionaryType {
    SOCIAL_NETWORK,
    CURRENCY,
    SHOPPING_METHODS,
    TRANSFER_MONEY,
    DEADLINE_SENDING,
    DEADLINE_PAYMENT,
    SORT_BY,
    PRODUCT_AVAILABILITY,
    ORDER_STATUS,
    CATALOG_FLAGS,
}

data class DictionaryEntry(val type: DictionaryType, val value: String?, val description: String?)
