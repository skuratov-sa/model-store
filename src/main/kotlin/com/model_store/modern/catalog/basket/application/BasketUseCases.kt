package com.model_store.modern.catalog.basket.application

import com.model_store.exception.constant.ErrorCode
import com.model_store.modern.catalog.product.search.domain.ProductSearchCriteria
import com.model_store.modern.catalog.product.search.application.ProductSearchRow
import org.springframework.stereotype.Service
import org.springframework.http.HttpStatus
import org.springframework.transaction.annotation.Transactional

data class BasketLine(val productId: Long, val count: Int)
data class BasketProduct(val product: ProductSearchRow, val count: Int) {
    val availableCount: Int? get() = product.count
    val enoughStock: Boolean get() = availableCount?.let { count <= it } ?: true
}
data class BasketProductState(val availableCount: Int?)

class BasketFailure(val status: HttpStatus, val code: ErrorCode, message: String) : RuntimeException(message)

private fun invalidCount() = BasketFailure(HttpStatus.BAD_REQUEST, ErrorCode.COUNT_INVALID, "Количество должно быть > 0")
private fun missingProduct() = BasketFailure(HttpStatus.NOT_FOUND, ErrorCode.PRODUCT_NOT_FOUND, "Не удалось найти товар")

/** ID-based snapshot for order creation. The caller must recheck product and stock while booking. */
interface BasketItemsReadPort {
    fun items(participantId: Long): List<BasketLine>
}

interface BasketStore : BasketItemsReadPort {
    /** Locks the participant row, serializing all mutations of that participant's basket. */
    fun lockParticipant(participantId: Long): String?
    fun adultAge(participantId: Long): Int?
    fun actualProduct(productId: Long): BasketProductState?
    fun item(participantId: Long, productId: Long): BasketLine?
    fun insert(participantId: Long, productId: Long, count: Int)
    fun update(participantId: Long, productId: Long, count: Int): Int
    fun delete(participantId: Long, productId: Long)
    fun find(participantId: Long, criteria: ProductSearchCriteria, includeAdult: Boolean): List<BasketProduct>
}

@Service
class BasketUseCases(private val store: BasketStore) {
    @Transactional(transactionManager = "transactionManager", readOnly = true)
    fun find(participantId: Long, criteria: ProductSearchCriteria): List<BasketProduct> =
        store.find(participantId, criteria, (store.adultAge(participantId) ?: 0) >= 18)

    @Transactional(transactionManager = "transactionManager")
    fun add(participantId: Long, productId: Long, count: Int?) {
        val qty = quantity(count)
        requireParticipant(participantId)
        requireStock(productId, qty, "Нельзя добавить столько товаров")
        if (store.item(participantId, productId) != null) {
            throw BasketFailure(HttpStatus.CONFLICT, ErrorCode.PRODUCT_ALREADY_IN_BASKET, "Товар уже в корзине")
        }
        store.insert(participantId, productId, qty)
    }

    @Transactional(transactionManager = "transactionManager")
    fun update(participantId: Long, productId: Long, count: Int?) {
        val qty = quantity(count)
        requireParticipant(participantId)
        requireStock(productId, qty, "Нельзя поставить столько товаров")
        if (store.item(participantId, productId) == null) {
            throw BasketFailure(HttpStatus.NOT_FOUND, ErrorCode.BASKET_ITEM_NOT_FOUND, "Товара нет в корзине")
        }
        if (store.update(participantId, productId, qty) != 1) {
            throw BasketFailure(HttpStatus.BAD_REQUEST, ErrorCode.BASKET_UPDATE_FAILED, "Не удалось обновить корзину")
        }
    }

    @Transactional(transactionManager = "transactionManager")
    fun remove(participantId: Long, productId: Long) {
        // Deletion was idempotent in the legacy endpoint, even for an absent item.
        // The owner lock also serializes remove with add and update.
        store.lockParticipant(participantId)
        store.delete(participantId, productId)
    }

    private fun quantity(count: Int?): Int = count?.takeIf { it > 0 } ?: throw invalidCount()

    private fun requireParticipant(participantId: Long) {
        if (store.lockParticipant(participantId) != "ACTIVE") {
            throw BasketFailure(HttpStatus.NOT_FOUND, ErrorCode.PARTICIPANT_NOT_FOUND, "Не удалось распознать пользователя")
        }
    }

    private fun requireStock(productId: Long, qty: Int, message: String) {
        val product = store.actualProduct(productId)
            ?: throw missingProduct()
        if (product.availableCount != null && product.availableCount < qty) {
            throw BasketFailure(HttpStatus.BAD_REQUEST, ErrorCode.NOT_ENOUGH_STOCK, message)
        }
    }
}
