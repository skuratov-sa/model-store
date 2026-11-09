package com.model_store.modern.ordering.lifecycle.infrastructure

import com.model_store.modern.ordering.lifecycle.application.LifecycleTransaction
import com.model_store.modern.ordering.lifecycle.application.LifecycleTransactionRequired
import jakarta.persistence.EntityManagerFactory
import org.springframework.context.annotation.Profile
import org.springframework.orm.jpa.EntityManagerHolder
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronizationManager
import javax.sql.DataSource

/** Guard only: joins the caller's resources without starting, replacing or committing a transaction. */
@Component
@Profile("modern")
class JpaLifecycleTransaction(private val entityManagerFactory: EntityManagerFactory, private val dataSource: DataSource) : LifecycleTransaction {
    override fun requireWritableJpa() {
        val holder = TransactionSynchronizationManager.getResource(entityManagerFactory) as? EntityManagerHolder
        if (!TransactionSynchronizationManager.isActualTransactionActive() ||
            TransactionSynchronizationManager.isCurrentTransactionReadOnly() ||
            holder?.entityManager?.isJoinedToTransaction != true ||
            !TransactionSynchronizationManager.hasResource(dataSource)) throw LifecycleTransactionRequired()
    }
}
