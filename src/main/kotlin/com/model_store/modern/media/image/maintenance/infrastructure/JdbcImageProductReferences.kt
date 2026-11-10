package com.model_store.modern.media.image.maintenance.infrastructure

import com.model_store.modern.media.image.maintenance.application.ImageProductReferences
import com.model_store.modern.media.image.maintenance.application.ImageReferencesTransactionRequired
import jakarta.persistence.EntityManagerFactory
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.orm.jpa.EntityManagerHolder
import org.springframework.stereotype.Repository
import org.springframework.transaction.support.TransactionSynchronizationManager as Tx
import javax.sql.DataSource

@Repository
@Profile("modern")
class JdbcImageProductReferences(private val jdbc: JdbcTemplate,
    private val entityManagerFactory: EntityManagerFactory, private val dataSource: DataSource,
) : ImageProductReferences {
    override fun hasImages(productId: Long): Boolean {
        val holder = Tx.getResource(entityManagerFactory) as? EntityManagerHolder
        if (!(Tx.isActualTransactionActive() && !Tx.isCurrentTransactionReadOnly() &&
            holder?.entityManager?.isJoinedToTransaction == true && Tx.hasResource(dataSource) && jdbc.dataSource === dataSource))
            throw ImageReferencesTransactionRequired()
        return jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM image WHERE tag='PRODUCT' AND entity_id=?)",
            Boolean::class.java, productId) == true
    }
}
