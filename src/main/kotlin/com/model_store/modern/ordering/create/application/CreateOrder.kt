package com.model_store.modern.ordering.create.application

import com.model_store.modern.catalog.basket.application.BasketStore
import com.model_store.modern.catalog.basket.application.BasketUseCases
import com.model_store.modern.catalog.product.reservation.application.*
import com.model_store.modern.ordering.create.domain.*
import com.model_store.modern.shared.domain.Actor
import com.model_store.modern.shared.domain.TokenType
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class OrderAddress(val id: Long, val country: String?, val city: String?, val street: String?,
                        val houseNumber: String?, val apartmentNumber: String?, val index: Int?, val status: String) {
    val fullAddress: String get() = "$country г. $city ул. $street $houseNumber кв. $apartmentNumber, индекс: $index"
}

data class OrderTransfer(val id: Long, val sending: String, val price: Int, val currency: String,
                         val participantId: Long, val status: String)

data class OrderPreparation(val addresses: List<OrderAddress>, val sellerTransfers: List<OrderTransfer>)

/** Read models contain scalars only; writes to each owner's tables stay in that context. */
interface OrderCreationRead {
    fun productOwners(productIds: List<Long>): List<Long>
    fun isAgent(participantId: Long): Boolean
    fun actualProductOwner(productId: Long): Long?
    fun addresses(customerId: Long): List<OrderAddress>
    fun transfers(sellerId: Long): List<OrderTransfer>
    fun validAddress(customerId: Long, addressId: Long): Boolean
    /** Locks seller's active methods in ID order through the end of booking. */
    fun activeTransferIds(sellerId: Long): Set<Long>
}

/** Writes only the order aggregate; PostgreSQL triggers own the initial history row. */
interface OrderCreationWrite {
    fun insert(draft: BookingDraft): Long
}

@Service
@Profile("modern")
class CreateOrder(
    private val read: OrderCreationRead,
    private val write: OrderCreationWrite,
    private val basketStore: BasketStore,
    private val basket: BasketUseCases,
    private val reservation: ProductReservation,
) {
    @Transactional(transactionManager = "transactionManager", readOnly = true)
    fun prepare(actor: Actor?, productId: Long?): OrderPreparation {
        val customerId = customer(actor)
        val id = productId ?: throw OrderFailure(OrderProblem.PRODUCT_NOT_FOUND)
        val sellerId = read.actualProductOwner(id)
            ?: throw OrderFailure(OrderProblem.PRODUCT_NOT_FOUND)
        val transfers = read.transfers(sellerId)
        if (transfers.isEmpty()) throw OrderFailure(OrderProblem.TRANSFER_NOT_FOUND)
        return OrderPreparation(read.addresses(customerId), transfers)
    }

    @Transactional(transactionManager = "transactionManager")
    fun book(actor: Actor?, requests: List<OrderLine>?): List<Long> {
        val customerId = customer(actor)
        val lines = OrderBatch.merge(requests ?: emptyList())
        if (lines.isEmpty()) return emptyList()

        val sellers = read.productOwners(lines.map { requireNotNull(it.productId) })
        if (customerId in sellers) throw OrderFailure(OrderProblem.OWN_PRODUCT_ORDER_FORBIDDEN)
        // Agent-owned items are rejected before participant locks. Agent profile commands lock
        // administrator then agent; booking never needs that pair for a known agent product.
        if (sellers.any(read::isAgent)) throw OrderFailure(OrderProblem.AGENT_PRODUCT)
        // Basket commands lock participant first; FK checks on basket insertion can then touch product.
        // Lock seller rows too, before product, so reciprocal buyers do not deadlock on order FKs.
        val participants = (sellers + customerId).distinct().sorted()
        participants.forEach { id -> basketStore.lockParticipant(id) }
        val snapshots = try {
            reservation.reserve(lines.map { ProductQuantity(requireNotNull(it.productId), requireNotNull(it.count)) })
        } catch (failure: ProductReservationFailure) {
            throw reservationError(failure)
        }.associateBy { it.id }

        // Validate the entire batch before inserting the first order. Reservation changes roll back on any error.
        if (lines.any { it.addressId == null }) throw OrderFailure(OrderProblem.INVALID_ADDRESS)
        lines.map { requireNotNull(it.addressId) }.distinct().sorted().forEach { addressId ->
            if (!read.validAddress(customerId, addressId)) throw OrderFailure(OrderProblem.INVALID_ADDRESS)
        }
        val sellerTransfers = mutableMapOf<Long, Set<Long>>()
        lines.forEach { line ->
            val snapshot = requireNotNull(snapshots[line.productId])
            if (snapshot.ownerId == customerId) throw OrderFailure(OrderProblem.OWN_PRODUCT_ORDER_FORBIDDEN)
            if (snapshot.ownerId !in participants)
                throw OrderFailure(OrderProblem.SELLER_CHANGED)
            if (read.isAgent(snapshot.ownerId)) throw OrderFailure(OrderProblem.AGENT_PRODUCT)
            val activeTransfers = sellerTransfers.getOrPut(snapshot.ownerId) {
                read.activeTransferIds(snapshot.ownerId)
            }
            if (activeTransfers.isEmpty()) throw OrderFailure(OrderProblem.TRANSFER_NOT_FOUND)
            if (line.transferId == null || line.transferId !in activeTransfers)
                throw OrderFailure(OrderProblem.INVALID_TRANSFER)
        }
        return lines.map { line ->
            val snapshot = requireNotNull(snapshots[line.productId])
            val draft = BookingDraft.from(customerId, line, ProductTerms(snapshot.id, snapshot.ownerId,
                snapshot.name, snapshot.unitPrice, snapshot.prepaymentAmount, snapshot.currency,
                snapshot.availability))
            val id = write.insert(draft)
            basket.remove(customerId, requireNotNull(line.productId))
            id
        }
    }

    private fun customer(actor: Actor?): Long {
        if (actor?.tokenType != TokenType.ACCESS || actor.role != "USER" && actor.role != "ADMIN")
            throw OrderFailure(OrderProblem.ACCESS_DENIED)
        return actor.participantId?.takeIf { it > 0 }
            ?: throw OrderFailure(OrderProblem.TOKEN_INVALID_OR_EXPIRED)
    }

    private fun reservationError(failure: ProductReservationFailure) = when (failure.problem) {
        ProductReservationProblem.PRODUCT_NOT_FOUND, ProductReservationProblem.INVALID_PRODUCT_ID ->
            OrderFailure(OrderProblem.PRODUCT_NOT_FOUND)
        ProductReservationProblem.INVALID_QUANTITY, ProductReservationProblem.COUNT_OVERFLOW ->
            OrderFailure(OrderProblem.COUNT_INVALID)
        ProductReservationProblem.OUT_OF_STOCK ->
            OrderFailure(OrderProblem.OUT_OF_STOCK)
        ProductReservationProblem.DUPLICATE_IDS, ProductReservationProblem.RESERVATION_CONFLICT ->
            OrderFailure(OrderProblem.RESERVATION_CONFLICT)
        ProductReservationProblem.TRANSACTION_REQUIRED -> failure
    }
}
