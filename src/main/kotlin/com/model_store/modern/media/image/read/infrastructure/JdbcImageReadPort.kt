package com.model_store.modern.media.image.read.infrastructure

import com.model_store.modern.media.image.read.application.ImageReadPort
import com.model_store.modern.media.image.read.domain.OrderParties
import com.model_store.modern.media.image.read.domain.ReadableImage
import com.model_store.modern.media.storage.domain.StorageLocation
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class JdbcImageReadPort(private val jdbc: NamedParameterJdbcTemplate) : ImageReadPort {
    override fun findActive(ids: Collection<Long>): List<ReadableImage> {
        if (ids.isEmpty()) return emptyList()
        return jdbc.query(
            """SELECT id, filename, tag::text AS tag, entity_id, width, height, content_type
               FROM image WHERE id IN (:ids) AND status = 'ACTIVE'""",
            mapOf("ids" to ids),
        ) { rs, _ ->
            ReadableImage(
                rs.getLong("id"), rs.getString("filename") ?: "",
                StorageLocation.valueOf(rs.getString("tag")),
                rs.getLong("entity_id").takeUnless { rs.wasNull() },
                rs.getInt("width").takeUnless { rs.wasNull() },
                rs.getInt("height").takeUnless { rs.wasNull() },
                rs.getString("content_type"),
            )
        }
    }

    override fun viewerIsAdmin(viewerId: Long): Boolean? = jdbc.query(
        "SELECT role::text AS role FROM participant WHERE id = :id",
        mapOf("id" to viewerId),
    ) { rs, _ -> rs.getString("role") == "ADMIN" }.firstOrNull()

    override fun findOrderParties(ids: Collection<Long>): Map<Long, OrderParties> {
        if (ids.isEmpty()) return emptyMap()
        return jdbc.query(
            "SELECT id, customer_id, seller_id FROM \"order\" WHERE id IN (:ids)",
            MapSqlParameterSource("ids", ids),
        ) { rs, _ ->
            rs.getLong("id") to OrderParties(rs.getLong("customer_id"), rs.getLong("seller_id"))
        }.toMap()
    }
}
