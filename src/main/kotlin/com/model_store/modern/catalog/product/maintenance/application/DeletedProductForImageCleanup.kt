package com.model_store.modern.catalog.product.maintenance.application

/** Scalar observation is not permission; repeat under the caller's product lock before image locks. */
data class CleanupProduct(val ownerId: Long, val deleted: Boolean)

interface DeletedProductForImageCleanup {
    fun observe(id: Long): CleanupProduct?
    fun lock(id: Long): CleanupProduct?
}

class CleanupProductTransactionRequired : RuntimeException("Writable caller JPA transaction required")
