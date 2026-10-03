package com.model_store.modern.seller.account.infrastructure

import com.model_store.modern.seller.account.application.AccountStore
import com.model_store.modern.seller.account.application.AccountActorRead
import com.model_store.modern.identity.participant.application.ParticipantStore
import com.model_store.modern.seller.account.domain.*
import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.springframework.stereotype.Repository

@Entity
@Table(name = "account")
class AccountRow(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null,
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "transfer_money", nullable = false, columnDefinition = "transfer_money_type")
    var transferMoney: TransferMoney? = null,
    @Column(length = 100) var username: String? = null,
    @Column(name = "entity_value", length = 100) var entityValue: String? = null,
    @Column(length = 255) var comment: String? = null,
    @Column(name = "participant_id", nullable = false) var participantId: Long = 0,
) {
    fun domain() = SellerAccount(requireNotNull(id), requireNotNull(transferMoney), username, entityValue, comment, participantId)
}

@Repository
class JpaAccountStore(private val entityManager: EntityManager) : AccountStore {
    override fun lockForCreate(participantId: Long) {
        // Transaction-scoped lock: two modern creates for one owner must check duplicates in order.
        entityManager.createNativeQuery("select pg_advisory_xact_lock(:key)")
            .setParameter("key", participantId xor Long.MIN_VALUE).singleResult
    }

    override fun byParticipant(participantId: Long): List<SellerAccount> = entityManager.createQuery(
        "select a from AccountRow a where a.participantId = :participantId order by a.id", AccountRow::class.java,
    ).setParameter("participantId", participantId).resultList.map(AccountRow::domain)

    override fun byId(id: Long): SellerAccount? = entityManager.find(AccountRow::class.java, id)?.domain()

    override fun create(participantId: Long, details: AccountDetails) {
        entityManager.persist(AccountRow(transferMoney = details.transferMoney, username = details.username,
            entityValue = details.entityValue, comment = details.comment, participantId = participantId))
    }

    override fun update(id: Long, participantId: Long, details: AccountDetails): SellerAccount {
        val row = entityManager.find(AccountRow::class.java, id) ?: throw AccountNotFound()
        if (row.participantId != participantId) throw AccountNotFound()
        row.transferMoney = details.transferMoney
        row.username = details.username
        row.entityValue = details.entityValue
        row.comment = details.comment
        entityManager.flush()
        return row.domain()
    }

    override fun delete(id: Long) {
        entityManager.find(AccountRow::class.java, id)?.let {
            entityManager.remove(it)
            entityManager.flush()
        }
    }
}

@Repository
class ParticipantAccountActorRead(private val participants: ParticipantStore) : AccountActorRead {
    override fun isAgent(participantId: Long): Boolean = participants.find(participantId)?.isAgent == true
}
