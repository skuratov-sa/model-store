package com.model_store.modern.seller.transfer.infrastructure

import com.model_store.modern.seller.transfer.application.TransferStore
import com.model_store.modern.seller.transfer.domain.*
import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.springframework.stereotype.Repository

@Entity
@Table(name = "transfer")
class TransferRow(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null,
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(columnDefinition = "shipping_methods_type") var sending: ShippingMethod = ShippingMethod.PRODUCT_PICKUP,
    var price: Int = 0,
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(columnDefinition = "currency") var currency: TransferCurrency = TransferCurrency.RUB,
    @Column(name = "participant_id") var participantId: Long = 0,
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(columnDefinition = "transfer_status") var status: TransferStatus = TransferStatus.ACTIVE,
) {
    fun domain() = TransferMethod(requireNotNull(id), sending, price, currency, participantId, status)
}

@Repository
class JpaTransferStore(private val em: EntityManager) : TransferStore {
    override fun lockOwner(ownerId: Long) {
        em.createNativeQuery("SELECT id FROM participant WHERE id = :id FOR UPDATE")
            .setParameter("id", ownerId).resultList.singleOrNull() ?: throw TransferNotFound()
    }

    override fun existsActiveType(ownerId: Long, sending: ShippingMethod): Boolean =
        em.createQuery("select count(t) from TransferRow t where t.participantId = :owner and t.sending = :sending and t.status = :status", java.lang.Long::class.java)
            .setParameter("owner", ownerId).setParameter("sending", sending)
            .setParameter("status", TransferStatus.ACTIVE).singleResult > 0

    override fun list(ownerId: Long): List<TransferMethod> = em.createQuery(
        "select t from TransferRow t where t.participantId = :owner", TransferRow::class.java,
    ).setParameter("owner", ownerId).resultList.map(TransferRow::domain)

    override fun lockOwned(ownerId: Long, id: Long): TransferMethod? = em.createQuery(
        "select t from TransferRow t where t.id = :id and t.participantId = :owner", TransferRow::class.java,
    ).setParameter("id", id).setParameter("owner", ownerId).setLockMode(LockModeType.PESSIMISTIC_WRITE)
        .resultList.firstOrNull()?.domain()

    override fun create(ownerId: Long, fields: TransferFields): TransferMethod = TransferRow(
        sending = requireNotNull(fields.sending), price = requireNotNull(fields.price),
        currency = requireNotNull(fields.currency), participantId = ownerId,
    ).also(em::persist).domain()

    override fun update(id: Long, fields: TransferFields): TransferMethod = requireNotNull(em.find(TransferRow::class.java, id)).apply {
        sending = requireNotNull(fields.sending)
        price = requireNotNull(fields.price)
        currency = requireNotNull(fields.currency)
    }.domain()

    override fun softDelete(id: Long) {
        requireNotNull(em.find(TransferRow::class.java, id)).status = TransferStatus.DELETED
    }
}
