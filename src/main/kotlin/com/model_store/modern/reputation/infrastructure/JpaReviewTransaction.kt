package com.model_store.modern.reputation.infrastructure

import com.model_store.modern.reputation.application.ReviewTransaction
import com.model_store.modern.reputation.application.ReviewTransactionRequired
import jakarta.persistence.EntityManagerFactory
import org.springframework.context.annotation.Profile
import org.springframework.orm.jpa.EntityManagerHolder
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronizationManager
import javax.sql.DataSource

@Component
@Profile("modern")
class JpaReviewTransaction(private val entityManagerFactory: EntityManagerFactory,
                           private val dataSource: DataSource) : ReviewTransaction {
    override fun requireWritableJpa() {
        val holder = TransactionSynchronizationManager.getResource(entityManagerFactory) as? EntityManagerHolder
        if (!TransactionSynchronizationManager.isActualTransactionActive() ||
            TransactionSynchronizationManager.isCurrentTransactionReadOnly() ||
            holder?.entityManager?.isJoinedToTransaction != true ||
            !TransactionSynchronizationManager.hasResource(dataSource)) throw ReviewTransactionRequired()
    }
}
