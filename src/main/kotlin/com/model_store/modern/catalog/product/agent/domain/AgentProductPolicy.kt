package com.model_store.modern.catalog.product.agent.domain

class InvalidAgentProduct(message: String) : RuntimeException(message)

/** Agent creation has no transfer/social prerequisite, stock or prepayment. */
object AgentProductPolicy {
    fun externalUrl(value: String?) {
        // Kotlin isBlank also includes nonbreaking spaces, which Java String.isBlank accepts.
        if (value == null || value.codePoints().allMatch { Character.isWhitespace(it) })
            throw InvalidAgentProduct("Укажите ссылку на внешний товар")
    }

    fun updateType(value: String?) {
        if (value != null && value != "EXTERNAL_PRODUCT" && value != "GIVEAWAY")
            throw InvalidAgentProduct("Товар бота может быть EXTERNAL_PRODUCT или GIVEAWAY")
    }

    fun price(value: Float) {
        if (!value.isFinite()) throw InvalidAgentProduct("Укажите цену товара")
    }

    fun prepayment(value: Float?, price: Float) {
        if (value != null && (!value.isFinite() || value < 0 || value > price))
            throw InvalidAgentProduct("Предоплата указана неверно")
    }
}
