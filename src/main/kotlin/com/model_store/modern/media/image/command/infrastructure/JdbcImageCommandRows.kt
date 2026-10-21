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
            if (ids.isEmpty()) throw ImageCommandNotFound()
            // Discovery takes no locks. Collect the entire batch before locking ANY participant:
            // a later image may belong to an owner with a lower ID than the actor/first owner.
            val discovered = images(ids, lock = false).associateBy { it.id }
            val productIds = if (tag == StorageLocation.PRODUCT)
                discovered.values.filter { it.tag == tag.name }.mapNotNull { it.entityId }.distinct().sorted()
                else emptyList()
            val context = lockProductsAndParticipants(actorId, productIds, lock = true)
            val actor = requireActor(context, actorId)
            val images = images(ids, lock = true)
            if (images.size != ids.size || images.any { it.tag != tag.name || it.status != "ACTIVE" })
                throw ImageCommandNotFound()
            // Never follow a newly discovered association after the lock frontier has advanced.
            // A normal denial rolls back the whole batch; no retry inside an aborted transaction.
            if (images.any { discovered[it.id]?.entityId != it.entityId || discovered[it.id]?.tag != it.tag })
                throw ImageCommandDenied()
            if (tag == StorageLocation.PARTICIPANT) {
                if (images.any { it.entityId != actorId }) throw ImageCommandDenied()
            } else {
                for (entityId in images.map { it.entityId }.distinct()) {
                    if (entityId == null) throw ImageCommandDenied()
                    requireProduct(entityId, actorId, actor, context)
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
        val productIds = if (target.tag == StorageLocation.PRODUCT) listOfNotNull(target.entityId) else emptyList()
        val context = lockProductsAndParticipants(target.actorId, productIds, lock)
        val actor = requireActor(context, target.actorId)
        when (target.tag) {
            StorageLocation.PARTICIPANT -> {
                if (target.entityId != target.actorId) throw ImageCommandDenied()
            }
            StorageLocation.SYSTEM -> {
                if (actor.role != "ADMIN") throw ImageCommandDenied()
            }
            StorageLocation.PRODUCT -> {
                val id = target.entityId ?: return // Unassigned upload, claimed later by product command.
                requireProduct(id, target.actorId, actor, context)
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

    private fun lockProductsAndParticipants(actorId: Long, productIds: List<Long>, lock: Boolean): Context {
        val discovered = productIds.associateWith { product(it, lock = false) }
        val participantIds = (listOf(actorId) + discovered.values.mapNotNull { it?.ownerId }).distinct().sorted()
        // Separate statements make acquisition order independent of joins/query plans. JDBC also
        // avoids returning an old managed JPA snapshot after waiting for a participant lock.
        val participants = participantIds.associateWith { id ->
            jdbc.query(
                """SELECT role::text AS role, status::text AS status, is_agent FROM participant
                   WHERE id = :id ${if (lock) "FOR UPDATE" else ""}""", mapOf("id" to id),
            ) { rs, _ -> ActorRow(rs.getString("role"), rs.getString("status"), rs.getBoolean("is_agent")) }
                .firstOrNull()
        }
        val products = if (lock) productIds.sorted().associateWith { product(it, lock = true) } else discovered
        if (products.any { (id, row) -> row?.ownerId != discovered[id]?.ownerId }) throw ImageCommandDenied()
        return Context(participants, products)
    }

    private fun requireActor(context: Context, id: Long): ActorRow =
        context.participants[id]?.takeIf { it.status == "ACTIVE" } ?: throw ImageCommandDenied()

    private fun requireProduct(id: Long, actorId: Long, actor: ActorRow, context: Context) {
        val product = context.products[id] ?: throw ImageCommandNotFound("Entity not found")
        val owner = context.participants[product.ownerId] ?: throw ImageCommandNotFound("Entity not found")
        val permitted = product.status != "DELETED" &&
            if (product.ownerId == actorId)
                actor.role == "ADMIN" || (product.status == "ACTIVE" && product.availability != "GIVEAWAY")
            else actor.role == "ADMIN" && owner.isAgent
        if (!permitted) throw ImageCommandDenied()
    }

    private fun product(id: Long, lock: Boolean): ProductRow? = jdbc.query(
        """SELECT participant_id, status::text AS status, availability::text AS availability FROM product
           WHERE id = :id ${if (lock) "FOR UPDATE" else ""}""", mapOf("id" to id),
    ) { rs, _ -> ProductRow(rs.getLong("participant_id"), rs.getString("status"), rs.getString("availability")) }
        .firstOrNull()

    private fun images(ids: List<Long>, lock: Boolean): List<LockedImage> = jdbc.query(
        """SELECT id, tag::text AS tag, status::text AS status, entity_id
           FROM image WHERE id IN (:ids) ORDER BY id ${if (lock) "FOR UPDATE" else ""}""",
        mapOf("ids" to ids),
    ) { rs, _ -> LockedImage(rs.getLong("id"), rs.getString("tag"), rs.getString("status"),
        rs.getLong("entity_id").takeUnless { rs.wasNull() }) }

    private data class Context(val participants: Map<Long, ActorRow?>, val products: Map<Long, ProductRow?>)
    private data class ActorRow(val role: String, val status: String, val isAgent: Boolean)
    private data class ProductRow(val ownerId: Long, val status: String, val availability: String)
    private data class OrderRow(val customerId: Long, val sellerId: Long, val status: String)
    private data class LockedImage(val id: Long, val tag: String, val status: String, val entityId: Long?)
}
