package com.model_store.modern.seller.social.infrastructure

import com.model_store.modern.seller.social.application.SocialNetworkStore
import com.model_store.modern.seller.social.domain.*
import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.springframework.stereotype.Repository

@Entity
@Table(name = "social_network")
class SocialNetworkRow(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null,
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(columnDefinition = "social_network_type") var type: SocialNetworkType = SocialNetworkType.TELEGRAM,
    var login: String? = null,
    @Column(name = "participant_id") var participantId: Long = 0,
) {
    fun domain() = SocialNetwork(requireNotNull(id), type, login, participantId)
}

@Repository
class JpaSocialNetworkStore(private val em: EntityManager) : SocialNetworkStore {
    override fun lockOwner(ownerId: Long) {
        em.createNativeQuery("SELECT id FROM participant WHERE id = :id FOR UPDATE")
            .setParameter("id", ownerId).resultList.singleOrNull() ?: throw SocialNetworkNotFound()
    }

    override fun existsType(ownerId: Long, type: SocialNetworkType): Boolean =
        em.createQuery("select count(s) from SocialNetworkRow s where s.participantId = :owner and s.type = :type", java.lang.Long::class.java)
            .setParameter("owner", ownerId).setParameter("type", type).singleResult > 0

    override fun list(ownerId: Long): List<SocialNetwork> = em.createQuery(
        "select s from SocialNetworkRow s where s.participantId = :owner", SocialNetworkRow::class.java,
    ).setParameter("owner", ownerId).resultList.map(SocialNetworkRow::domain)

    override fun lockOwned(ownerId: Long, id: Long): SocialNetwork? = em.createQuery(
        "select s from SocialNetworkRow s where s.id = :id and s.participantId = :owner", SocialNetworkRow::class.java,
    ).setParameter("id", id).setParameter("owner", ownerId).setLockMode(LockModeType.PESSIMISTIC_WRITE)
        .resultList.firstOrNull()?.domain()

    override fun create(ownerId: Long, fields: SocialNetworkFields) {
        em.persist(SocialNetworkRow(type = requireNotNull(fields.type), login = fields.login, participantId = ownerId))
    }

    override fun update(id: Long, fields: SocialNetworkFields): SocialNetwork =
        requireNotNull(em.find(SocialNetworkRow::class.java, id)).apply {
            type = requireNotNull(fields.type)
            login = fields.login
        }.domain()

    override fun delete(id: Long) {
        em.remove(requireNotNull(em.find(SocialNetworkRow::class.java, id)))
    }
}
