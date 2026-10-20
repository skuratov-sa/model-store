package com.model_store.modern.media.image.command.infrastructure

import com.model_store.modern.media.image.command.application.OrderImageEvidence
import com.model_store.modern.media.image.command.application.OrderImageEvidenceFailure
import com.model_store.modern.media.image.command.application.OrderImageEvidenceProblem
import jakarta.persistence.EntityManager
import jakarta.persistence.EntityManagerFactory
import org.springframework.context.annotation.Profile
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.orm.jpa.EntityManagerHolder
import org.springframework.stereotype.Repository
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.SQLException
import javax.sql.DataSource

/** Locks only image rows, in ascending ID order, after the ordering caller's participant/order locks. */
@Repository
@Profile("modern")
class JdbcOrderImageEvidence(
    private val entityManagerFactory: EntityManagerFactory,
    private val dataSource: DataSource,
) : OrderImageEvidence {
    private val jdbc = NamedParameterJdbcTemplate(dataSource)

    override fun activate(orderId: Long?, uploaderId: Long?, imageIds: List<Long?>?): Int {
        val entityManager = requireJpaTransaction()
        try {
            if (orderId == null || orderId <= 0 || uploaderId == null || uploaderId <= 0)
                fail(OrderImageEvidenceProblem.INVALID_INPUT)
            val requested = imageIds ?: emptyList()
            if (requested.size != requested.toSet().size) fail(OrderImageEvidenceProblem.DUPLICATE_IDS)
            if (requested.any { it == null || it <= 0 }) fail(OrderImageEvidenceProblem.IMAGE_NOT_FOUND)
            if (requested.isEmpty()) return 0

            val ids = requested.filterNotNull().sorted()
            // Separate SELECTs make row acquisition order independent of the query planner. NOWAIT
            // bounds conflicts with another claim or a writer holding an image row.
            for (id in ids) {
                val row = try {
                    jdbc.query(
                        """SELECT tag::text AS tag, status::text AS status, entity_id, uploaded_by
                           FROM image WHERE id=:id FOR UPDATE NOWAIT""",
                        mapOf("id" to id),
                    ) { rs, _ ->
                        ImageRow(rs.getString("tag"), rs.getString("status"),
                            rs.getLong("entity_id").takeUnless { rs.wasNull() },
                            rs.getLong("uploaded_by").takeUnless { rs.wasNull() })
                    }.singleOrNull()
                } catch (error: DataAccessException) {
                    if (error.hasSqlState("55P03")) fail(OrderImageEvidenceProblem.IMAGE_BUSY)
                    throw error
                }
                if (row == null || row.tag != "ORDER" || row.status != "TEMPORARY" ||
                    row.uploadedBy != uploaderId || row.entityId != null && row.entityId != orderId)
                    fail(OrderImageEvidenceProblem.IMAGE_NOT_FOUND)
            }
            // Every row was checked under a lock. The predicate repeats the legacy UPDATE contract.
            val changed = jdbc.update(
                """UPDATE image SET status='ACTIVE', entity_id=:orderId
                   WHERE id IN (:ids) AND tag='ORDER' AND status='TEMPORARY'
                     AND uploaded_by=:uploaderId AND (entity_id IS NULL OR entity_id=:orderId)""",
                mapOf("ids" to ids, "orderId" to orderId, "uploaderId" to uploaderId),
            )
            if (changed != ids.size) fail(OrderImageEvidenceProblem.IMAGE_NOT_FOUND)
            return changed
        } catch (failure: RuntimeException) {
            // Protect ordering writes even if a caller accidentally catches a typed failure.
            // This only marks the existing transaction; there is no transaction boundary here.
            try {
                entityManager.transaction.setRollbackOnly()
            } catch (rollbackFailure: RuntimeException) {
                failure.addSuppressed(rollbackFailure)
            }
            throw failure
        }
    }

    private fun requireJpaTransaction(): EntityManager {
        val holder = TransactionSynchronizationManager.getResource(entityManagerFactory) as? EntityManagerHolder
        if (!TransactionSynchronizationManager.isActualTransactionActive() ||
            TransactionSynchronizationManager.isCurrentTransactionReadOnly() ||
            holder?.entityManager?.isJoinedToTransaction != true ||
            !TransactionSynchronizationManager.hasResource(dataSource))
            fail(OrderImageEvidenceProblem.TRANSACTION_REQUIRED)
        return holder.entityManager
    }

    private fun DataAccessException.hasSqlState(expected: String): Boolean {
        var cause: Throwable? = this
        while (cause != null) {
            if (cause is SQLException && cause.sqlState == expected) return true
            cause = cause.cause
        }
        return false
    }

    private fun fail(problem: OrderImageEvidenceProblem): Nothing = throw OrderImageEvidenceFailure(problem)

    private data class ImageRow(val tag: String, val status: String, val entityId: Long?, val uploadedBy: Long?)
}
