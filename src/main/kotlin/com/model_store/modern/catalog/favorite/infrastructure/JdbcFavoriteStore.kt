package com.model_store.modern.catalog.favorite.infrastructure

import com.model_store.modern.catalog.favorite.application.FavoriteStore
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class JdbcFavoriteStore(private val jdbc: JdbcTemplate) : FavoriteStore {
    override fun lockOwner(ownerId: Long) {
        // The schema has no unique(owner, product) constraint. An owner lock serializes
        // concurrent modern commands without locking another participant's favorites.
        jdbc.queryForList("SELECT pg_advisory_xact_lock(?)", ownerId)
    }

    override fun addIfAbsent(ownerId: Long, productId: Long) {
        jdbc.update(
            """INSERT INTO product_favorite(participant_id, product_id)
               SELECT ?, ? WHERE NOT EXISTS (
                   SELECT 1 FROM product_favorite WHERE participant_id = ? AND product_id = ?
               )""",
            ownerId, productId, ownerId, productId,
        )
    }

    override fun remove(ownerId: Long, productId: Long) {
        jdbc.update("DELETE FROM product_favorite WHERE participant_id = ? AND product_id = ?", ownerId, productId)
    }
}
