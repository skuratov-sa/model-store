package com.model_store.modern.catalog.product.maintenance.application

/** Background commands own their transactions and must be invoked without an ambient transaction. */
interface ProductMaintenance {
    fun lastCandidateId(): Long
    fun expireDueOrdinary(): Int
    fun cleanupDeletedBatch(afterId: Long, limit: Int, upToId: Long = Long.MAX_VALUE): ProductCleanupBatch
}

class ProductMaintenanceTransactionRequired : RuntimeException("Product maintenance requires its own writable JPA transaction")

/** Cursor advances over skipped/failed rows as well, so protected rows cannot cause an endless batch. */
data class ProductCleanupBatch(val selected: Int, val deleted: Int, val failed: Int, val lastId: Long?)
