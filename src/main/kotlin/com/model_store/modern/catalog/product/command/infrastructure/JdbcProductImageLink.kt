package com.model_store.modern.catalog.product.command.infrastructure

import com.model_store.modern.catalog.product.command.application.*
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.SQLException

/** Temporary adapter until media exposes a product attachment port from its own package. */
@Repository
@Profile("modern")
class JdbcProductImageLink(
    private val jdbc: NamedParameterJdbcTemplate,
    @Value("\${app.max-product-images}") private val maxImages: Int,
) : ProductImageLink {
    override fun claim(productId: Long, ownerId: Long, imageIds: List<Long>) {
        if (imageIds.size != imageIds.toSet().size)
            throw ProductCommandFailure(FailureKind.BAD_REQUEST, "INVALID_REQUEST", "Повторяющиеся ID изображений")
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
        if (rows.size != imageIds.size)
            throw ProductCommandFailure(FailureKind.NOT_FOUND, "IMAGE_NOT_FOUND", "Не удалось найти изображение")
        if (rows.any { it.tag != "PRODUCT" || it.status == "DELETE" ||
                it.status == "TEMPORARY" && it.uploadedBy != ownerId ||
                it.entityId != null && it.entityId != productId && it.uploadedBy != ownerId ||
                it.entityId == null && it.status != "TEMPORARY" })
            throw ProductCommandFailure(FailureKind.FORBIDDEN, "ACCESS_DENIED", "Доступ к изображению запрещён")
        if (rows.any { it.entityId != null && it.entityId != productId })
            throw ProductCommandFailure(FailureKind.CONFLICT, "INVALID_REQUEST", "Изображение уже связано с другим товаром")
        val attached = jdbc.queryForObject(
            "SELECT count(*) FROM image WHERE tag='PRODUCT' AND entity_id=:id AND status='ACTIVE'",
            mapOf("id" to productId), Int::class.java) ?: 0
        if (attached + rows.count { it.status != "ACTIVE" } > maxImages)
            throw ProductCommandFailure(FailureKind.BAD_REQUEST, "INVALID_REQUEST", "Слишком много изображений")
        val changed = jdbc.update(
            """UPDATE image SET status='ACTIVE', entity_id=:product
               WHERE id IN (:ids) AND tag='PRODUCT' AND status <> 'DELETE'
                 AND (entity_id=:product OR (entity_id IS NULL AND status='TEMPORARY' AND uploaded_by=:owner))""",
            mapOf("product" to productId, "owner" to ownerId, "ids" to imageIds),
        )
        if (changed != imageIds.size)
            throw ProductCommandFailure(FailureKind.CONFLICT, "INVALID_REQUEST", "Не удалось связать изображение с товаром")
    }

    override fun deleteProductImages(productId: Long) {
        // Media deletes lock image before product. NOWAIT makes the opposing order fail and roll back
        // instead of allowing a deadlock while this command owns the product row lock.
        imageLock {
            jdbc.query("""SELECT id FROM image WHERE tag='PRODUCT' AND entity_id=:id
                           ORDER BY id FOR UPDATE NOWAIT""", mapOf("id" to productId)) { rs, _ -> rs.getLong(1) }
        }
        jdbc.update("UPDATE image SET status='DELETE' WHERE tag='PRODUCT' AND entity_id=:id",
            mapOf("id" to productId))
    }

    private fun <T> imageLock(block: () -> T): T = try {
        block()
    } catch (error: DataAccessException) {
        var cause: Throwable? = error
        while (cause != null) {
            if (cause is SQLException && cause.sqlState == "55P03")
                throw ProductCommandFailure(FailureKind.CONFLICT, "INVALID_REQUEST", "Изображение занято другой операцией")
            cause = cause.cause
        }
        throw error
    }

    private data class ImageRow(val id: Long, val tag: String, val status: String,
                                val entityId: Long?, val uploadedBy: Long?)
}
