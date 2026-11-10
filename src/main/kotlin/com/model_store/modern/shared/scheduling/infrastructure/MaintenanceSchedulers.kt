package com.model_store.modern.shared.scheduling.infrastructure

import com.model_store.modern.catalog.product.maintenance.application.ProductMaintenance
import com.model_store.modern.media.image.maintenance.application.ImageMaintenance
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** No business SQL/entities here. Empty zone retains the legacy scheduler's default time zone. */
@Component
@Profile("modern")
class MaintenanceSchedulers(private val products: ProductMaintenance, private val images: ImageMaintenance) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "0 */5 * * * *")
    fun expireProducts() = runJob("product expiration") { log.info("Expired products: {}", products.expireDueOrdinary()) }

    @Scheduled(cron = "0 0 0 * * *")
    fun cleanupImages() = runJob("image cleanup") {
        val ceiling = images.lastCandidateId()
        var cursor = 0L
        do {
            val ids = images.candidateIds(cursor, 200, ceiling)
            for (id in ids) {
                try { images.cleanupOne(id) }
                catch (failure: Exception) { log.error("Image cleanup failed for id={}; retained for next run", id, failure) }
            }
            if (ids.isNotEmpty()) cursor = ids.last()
        } while (ids.size == 200)
    }

    @Scheduled(cron = "0 0 2 * * *")
    fun cleanupProducts() = runJob("product cleanup") {
        val ceiling = products.lastCandidateId()
        var cursor = 0L
        do {
            val batch = products.cleanupDeletedBatch(cursor, 200, ceiling)
            log.info("Product cleanup selected={}, deleted={}, failed={}", batch.selected, batch.deleted, batch.failed)
            batch.lastId?.let { cursor = it }
        } while (batch.selected == 200)
    }

    private fun runJob(name: String, action: () -> Unit) {
        try { action() }
        catch (failure: Exception) { log.error("Failed {}", name, failure) }
    }
}
