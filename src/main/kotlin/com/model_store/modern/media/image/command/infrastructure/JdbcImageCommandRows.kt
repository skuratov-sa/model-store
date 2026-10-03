package com.model_store.modern.media.image.command.infrastructure

import com.model_store.modern.media.image.command.application.ImageCommandRows
import com.model_store.modern.media.image.command.domain.ImageCommandDenied
import com.model_store.modern.media.image.command.domain.ImageCommandNotFound
import com.model_store.modern.media.image.command.domain.ImageToInsert
import com.model_store.modern.media.image.command.domain.UploadTarget
import com.model_store.modern.media.storage.domain.StorageLocation
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

@Repository
@Profile("modern")
class JdbcImageCommandRows(
    private val jdbc: NamedParameterJdbcTemplate,
    transactionManager: PlatformTransactionManager,
) : ImageCommandRows {
    private val transactions = TransactionTemplate(transactionManager)

    override fun requireUploadAllowed(target: UploadTarget) = authorizeUpload(target, lock = false)

    override fun insertBatch(target: UploadTarget, images: List<ImageToInsert>): List<Long> =
        requireNotNull(transactions.execute {
            authorizeUpload(target, lock = true)
            images.map { image ->
                requireNotNull(jdbc.queryForObject(
                    """INSERT INTO image (filename, tag, status, entity_id, uploaded_by, content_type, width, height)
                       VALUES (:filename, CAST(:tag AS image_tag), CAST(:status AS image_status),
                               :entityId, :actorId, 'image/jpeg', :width, :height) RETURNING id""",
                    mapOf(
                        "filename" to image.filename,
                        "tag" to target.tag.name,
                        "status" to if (target.tag == StorageLocation.PARTICIPANT) "ACTIVE" else "TEMPORARY",
                        "entityId" to target.entityId,
                        "actorId" to target.actorId,
                        "width" to image.width,
                        "height" to image.height,
                    ), Long::class.java,
                ))
            }
        })

    override fun markDeleted(ids: List<Long>, tag: StorageLocation, actorId: Long) {
        transactions.executeWithoutResult {
            if (tag == StorageLocation.ORDER || tag == StorageLocation.SYSTEM) throw ImageCommandDenied()
            authorizeUpload(UploadTarget(StorageLocation.PARTICIPANT, actorId, actorId), lock = true)
            val images = jdbc.query(
                """SELECT id, tag::text AS tag, status::text AS status, entity_id
                   FROM image WHERE id IN (:ids) FOR UPDATE""",
                mapOf("ids" to ids),
            ) { rs, _ ->
                LockedImage(rs.getLong("id"), rs.getString("tag"), rs.getString("status"),
                    rs.getLong("entity_id").takeUnless { rs.wasNull() })
            }
            if (images.size != ids.size || images.any { it.tag != tag.name || it.status != "ACTIVE" })
                throw ImageCommandNotFound()
            if (tag == StorageLocation.PARTICIPANT) {
                if (images.any { it.entityId != actorId }) throw ImageCommandDenied()
            } else {
                for (entityId in images.map { it.entityId }.distinct()) {
                    if (entityId == null) throw ImageCommandDenied()
                    authorizeUpload(UploadTarget(tag, entityId, actorId), lock = true)
                }
            }
            val changed = jdbc.update(
                "UPDATE image SET status = CAST('DELETE' AS image_status) WHERE id IN (:ids)",
                mapOf("ids" to ids),
            )
            if (changed != ids.size) throw ImageCommandNotFound()
        }
    }

    private fun authorizeUpload(target: UploadTarget, lock: Boolean) {
        val actor = jdbc.query(
            "SELECT role::text AS role, status::text AS status FROM participant WHERE id = :id ${if (lock) "FOR UPDATE" else ""}",
            mapOf("id" to target.actorId),
        ) { rs, _ -> ActorRow(rs.getString("role"), rs.getString("status")) }.firstOrNull()
            ?: throw ImageCommandDenied()
        if (actor.status != "ACTIVE") throw ImageCommandDenied()
        when (target.tag) {
            StorageLocation.PARTICIPANT -> {
                if (target.entityId != target.actorId) throw ImageCommandDenied()
            }
            StorageLocation.SYSTEM -> {
                if (actor.role != "ADMIN") throw ImageCommandDenied()
            }
            StorageLocation.PRODUCT -> {
                val id = target.entityId ?: return // Unassigned upload, claimed later by product command.
                val product = jdbc.query(
                    """SELECT p.participant_id, p.status::text AS status,
                              p.availability::text AS availability, owner.is_agent
                       FROM product p JOIN participant owner ON owner.id = p.participant_id
                       WHERE p.id = :id ${if (lock) "FOR UPDATE OF p, owner" else ""}""",
                    mapOf("id" to id),
                ) { rs, _ ->
                    ProductRow(rs.getLong("participant_id"), rs.getString("status"),
                        rs.getString("availability"), rs.getBoolean("is_agent"))
                }.firstOrNull() ?: throw ImageCommandNotFound("Entity not found")
                val permitted = product.status != "DELETED" &&
                    if (product.ownerId == target.actorId)
                        actor.role == "ADMIN" || (product.status == "ACTIVE" && product.availability != "GIVEAWAY")
                    else actor.role == "ADMIN" && product.ownerIsAgent
                if (!permitted) throw ImageCommandDenied()
            }
            StorageLocation.ORDER -> {
                val id = target.entityId ?: return // Unassigned evidence is TEMPORARY.
                val order = jdbc.query(
                    """SELECT customer_id, seller_id, status::text AS status FROM "order"
                       WHERE id = :id ${if (lock) "FOR UPDATE" else ""}""",
                    mapOf("id" to id),
                ) { rs, _ ->
                    OrderRow(rs.getLong("customer_id"), rs.getLong("seller_id"), rs.getString("status"))
                }.firstOrNull() ?: throw ImageCommandNotFound("Entity not found")
                if (target.actorId != order.customerId && target.actorId != order.sellerId ||
                    order.status == "COMPLETED" || order.status == "FAILED") throw ImageCommandDenied()
            }
        }
    }

    private data class ActorRow(val role: String, val status: String)
    private data class ProductRow(val ownerId: Long, val status: String, val availability: String, val ownerIsAgent: Boolean)
    private data class OrderRow(val customerId: Long, val sellerId: Long, val status: String)
    private data class LockedImage(val id: Long, val tag: String, val status: String, val entityId: Long?)
}
