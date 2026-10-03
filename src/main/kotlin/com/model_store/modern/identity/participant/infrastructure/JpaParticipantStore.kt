package com.model_store.modern.identity.participant.infrastructure

import com.model_store.modern.identity.participant.application.ParticipantStore
import com.model_store.modern.identity.participant.domain.Participant
import com.model_store.modern.identity.participant.domain.ParticipantStatus
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

interface ParticipantJpaRepository : JpaRepository<ParticipantEntity, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM ParticipantEntity p WHERE p.id = :id")
    fun findForUpdate(@Param("id") id: Long): ParticipantEntity?

    fun findByIdAndStatus(id: Long, status: ParticipantStatus): ParticipantEntity?
    fun existsByMail(mail: String): Boolean
    fun existsByLoginAndIdNot(login: String, id: Long): Boolean
}

@Repository
class JpaParticipantStore(private val repository: ParticipantJpaRepository, private val entityManager: EntityManager) : ParticipantStore {
    override fun find(id: Long): Participant? = repository.findById(id).orElse(null)?.toDomain()
    override fun findForUpdate(id: Long): Participant? = repository.findForUpdate(id)?.toDomain()
    override fun findActive(id: Long): Participant? = repository.findByIdAndStatus(id, ParticipantStatus.ACTIVE)?.toDomain()
    override fun mailExists(mail: String) = repository.existsByMail(mail)
    override fun loginExistsForAnother(login: String, id: Long) = repository.existsByLoginAndIdNot(login, id)

    override fun create(mail: String?, passwordHash: String, age: Int): Participant {
        val entity = repository.saveAndFlush(ParticipantEntity(mail = mail, password = passwordHash, age = age.toShort()))
        entity.login = "user${requireNotNull(entity.id)}"
        repository.flush()
        entityManager.refresh(entity)
        return entity.toDomain()
    }

    override fun save(participant: Participant): Participant {
        val entity = repository.findById(participant.id).orElseThrow()
        entity.apply(participant)
        return repository.saveAndFlush(entity).toDomain()
    }
}
