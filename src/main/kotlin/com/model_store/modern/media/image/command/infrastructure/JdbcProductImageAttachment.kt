package com.model_store.modern.media.image.command.infrastructure

import com.model_store.modern.media.image.command.application.ProductImageAttachment
import com.model_store.modern.media.image.command.application.ProductImageAttachmentFailure
import com.model_store.modern.media.image.command.application.ProductImageAttachmentProblem
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.SQLException

/** Uses the product command's transaction and connection; it never opens or commits one. */
@Repository
@Profile("modern")
class JdbcProductImageAttachment(
    private val jdbc: NamedParameterJdbcTemplate,
    @Value("\${app.max-product-images}") private val maxImages: Int,
) : ProductImageAttachment {
    override fun claim(productId: Long, ownerId: Long, imageIds: List<Long>) {
        requireTransaction()
        if (imageIds.isEmpty()) return
        if (imageIds.size != imageIds.toSet().size) fail(ProductImageAttachmentProblem.DUPLICATE_IDS)
        val rows = imageLock {
            jdbc.query(
                """SELECT id, tag::text AS tag, status::text AS status, entity_id, uploaded_by FROM image
                   WHERE id IN (:ids) ORDER BY id FOR UPDATE NOWAIT""", mapOf("ids" to imageIds),
            ) { rs, _ ->
                ImageRow(rs.getLong("id"), rs.getString("tag"), rs.getString("status"),
                    rs.getLong("entity_id").takeUnless { rs.wasNull() },
                    rs.getLong("uploaded_by").takeUnless { rs.wasNull() })
            }
        }
        if (rows.size != imageIds.size) fail(ProductImageAttachmentProblem.IMAGE_NOT_FOUND)
        if (rows.any { it.tag != "PRODUCT" || it.status == "DELETE" ||
                it.status == "TEMPORARY" && it.uploadedBy != ownerId ||
                it.entityId != null && it.entityId != productId && it.uploadedBy != ownerId ||
                it.entityId == null && it.status != "TEMPORARY" })
            fail(ProductImageAttachmentProblem.ACCESS_DENIED)
        if (rows.any { it.entityId != null && it.entityId != productId })
            fail(ProductImageAttachmentProblem.ALREADY_ATTACHED)
        val attached = jdbc.queryForObject(
            "SELECT count(*) FROM image WHERE tag='PRODUCT' AND entity_id=:id AND status='ACTIVE'",
            mapOf("id" to productId), Int::class.java) ?: 0
        if (attached + rows.count { it.status != "ACTIVE" } > maxImages)
            fail(ProductImageAttachmentProblem.TOO_MANY_IMAGES)
        val changed = jdbc.update(
            """UPDATE image SET status='ACTIVE', entity_id=:product
               WHERE id IN (:ids) AND tag='PRODUCT' AND status <> 'DELETE'
                 AND (entity_id=:product OR (entity_id IS NULL AND status='TEMPORARY' AND uploaded_by=:owner))""",
            mapOf("product" to productId, "owner" to ownerId, "ids" to imageIds),
        )
        if (changed != imageIds.size) fail(ProductImageAttachmentProblem.CLAIM_CONFLICT)
    }

    override fun markProductImagesDeleted(productId: Long) {
        requireTransaction()
        // Media delete locks product before image too; NOWAIT still bounds independent image contention.
        val ids = imageLock {
            jdbc.query("""SELECT id FROM image WHERE tag='PRODUCT' AND entity_id=:id
                           ORDER BY id FOR UPDATE NOWAIT""", mapOf("id" to productId)) { rs, _ -> rs.getLong(1) }
        }
        if (ids.isNotEmpty()) jdbc.update(
            "UPDATE image SET status='DELETE' WHERE id IN (:ids)", mapOf("ids" to ids),
        )
    }

    private fun requireTransaction() {
        check(TransactionSynchronizationManager.isActualTransactionActive()) {
            "Product image attachment requires the caller's transaction"
        }
    }

    private fun <T> imageLock(block: () -> T): T = try {
        block()
    } catch (error: DataAccessException) {
        var cause: Throwable? = error
        while (cause != null) {
            if (cause is SQLException && cause.sqlState == "55P03")
                fail(ProductImageAttachmentProblem.IMAGE_BUSY)
            cause = cause.cause
        }
        throw error
    }

    private fun fail(problem: ProductImageAttachmentProblem): Nothing = throw ProductImageAttachmentFailure(problem)

    private data class ImageRow(val id: Long, val tag: String, val status: String,
                                val entityId: Long?, val uploadedBy: Long?)
}
