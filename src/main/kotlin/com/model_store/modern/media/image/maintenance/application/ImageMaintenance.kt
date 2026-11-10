package com.model_store.modern.media.image.maintenance.application

interface ImageMaintenance {
    fun lastCandidateId(): Long
    fun candidateIds(afterId: Long, limit: Int, upToId: Long = Long.MAX_VALUE): List<Long>
    /** Commits a durable tombstone before irreversible storage effects. No ambient transaction allowed. */
    fun cleanupOne(id: Long): ImageCleanupOutcome
}

enum class ImageCleanupOutcome { DELETED, SKIPPED, CONFLICT }

class ImageMaintenanceTransactionRequired : RuntimeException("Image maintenance requires its own writable JPA transaction")

/** Only confirmed completion of all variants allows row deletion. A failure may have reached S3. */
interface ImageCleanupObjects {
    fun deleteVariants(tag: String, filename: String)
}
