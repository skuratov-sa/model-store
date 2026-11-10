package com.model_store.modern.media.image.maintenance.infrastructure

import com.amazonaws.ClientConfiguration
import com.amazonaws.auth.AWSStaticCredentialsProvider
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.client.builder.AwsClientBuilder
import com.amazonaws.services.s3.AmazonS3ClientBuilder
import com.model_store.modern.catalog.product.maintenance.application.CleanupProduct
import com.model_store.modern.catalog.product.maintenance.application.DeletedProductForImageCleanup
import com.model_store.modern.identity.participant.maintenance.application.MaintenanceParticipantLocks
import com.model_store.modern.media.image.maintenance.application.*
import com.model_store.modern.media.storage.domain.ImageVariant
import com.model_store.modern.media.storage.domain.ImageVariantKey
import com.model_store.modern.media.storage.domain.StorageLocation
import com.model_store.modern.ordering.maintenance.application.ImageOrderMaintenance
import com.model_store.modern.ordering.maintenance.application.ImageOrderParties
import jakarta.annotation.PreDestroy
import jakarta.persistence.EntityManagerFactory
import org.springframework.orm.jpa.EntityManagerHolder
import org.springframework.orm.jpa.JpaTransactionManager
import javax.sql.DataSource
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionSynchronizationManager as Tx
import org.springframework.transaction.support.TransactionTemplate

@Service
@Profile("modern")
class JdbcImageMaintenance(
    private val jdbc: JdbcTemplate,
    @Qualifier("transactionManager") private val manager: PlatformTransactionManager,
    private val entityManagerFactory: EntityManagerFactory,
    private val dataSource: DataSource,
    private val participants: MaintenanceParticipantLocks,
    private val products: DeletedProductForImageCleanup,
    private val orders: ImageOrderMaintenance,
    private val objects: ImageCleanupObjects,
) : ImageMaintenance {
    private val transactions = TransactionTemplate(manager).apply { timeout = 90 }

    override fun lastCandidateId(): Long = jdbc.queryForObject("SELECT coalesce(max(id),0) FROM image", Long::class.java)!!

    override fun candidateIds(afterId: Long, limit: Int, upToId: Long): List<Long> {
        require(afterId >= 0 && upToId >= 0 && limit in 1..200)
        // Catalog owns the deleted-product predicate. Page through PRODUCT candidates without foreign SQL.
        return jdbc.query("""SELECT id FROM image WHERE id>? AND id<=? AND (
            (status IN ('TEMPORARY','DELETE') AND created_at <= CURRENT_TIMESTAMP - interval '24 hours')
            OR tag='PRODUCT') ORDER BY id LIMIT ?""", { rs, _ -> rs.getLong(1) }, afterId, upToId, limit)
    }

    override fun cleanupOne(id: Long): ImageCleanupOutcome {
        if (Tx.isActualTransactionActive() || manager !is JpaTransactionManager ||
            manager.entityManagerFactory !== entityManagerFactory || manager.dataSource !== dataSource ||
            jdbc.dataSource !== dataSource) throw ImageMaintenanceTransactionRequired()
        val observed = image(id, false) ?: return ImageCleanupOutcome.SKIPPED
        val product = if (observed.tag == "PRODUCT") observed.entityId?.let(products::observe) else null
        val order = if (observed.tag == "ORDER") observed.entityId?.let(orders::observeImageOrder) else null
        val participantIds = buildList {
            observed.uploadedBy?.let(::add)
            if (observed.tag == "PARTICIPANT") observed.entityId?.let(::add)
            product?.ownerId?.let(::add)
            order?.let { add(it.customerId); add(it.sellerId) }
        }.distinct().sorted()
        val plan = Plan(observed, product, order, participantIds)
        val prepared = requireNotNull(transactions.execute {
            val locked = lock(plan) ?: return@execute ImageCleanupOutcome.CONFLICT
            if (!eligible(locked.first, locked.second) || !validKey(locked.first.filename) ||
                sharedKey(locked.first) || orders.isImageReferenced(id))
                return@execute ImageCleanupOutcome.SKIPPED
            if (locked.first.status != "DELETE") jdbc.update("UPDATE image SET status='DELETE' WHERE id=?", id)
            ImageCleanupOutcome.DELETED // Prepared; not reported as deleted until the second commit.
        })
        if (prepared != ImageCleanupOutcome.DELETED) return prepared
        // TransactionTemplate returned only after successful commit. A crash here leaves durable DELETE.
        val tombstone = plan.copy(image = observed.copy(status = "DELETE"))
        return requireNotNull(transactions.execute {
            val locked = lock(tombstone) ?: return@execute ImageCleanupOutcome.CONFLICT
            val row = locked.first
            if (row.status != "DELETE" || !eligible(row, locked.second) || !validKey(row.filename) ||
                sharedKey(row) || orders.isImageReferenced(id))
                return@execute ImageCleanupOutcome.SKIPPED
            objects.deleteVariants(row.tag, requireNotNull(row.filename))
            check(jdbc.update("DELETE FROM image WHERE id=? AND status='DELETE'", id) == 1)
            ImageCleanupOutcome.DELETED
        })
    }

    private fun lock(plan: Plan): Pair<ImageRow, CleanupProduct?>? {
        val holder = Tx.getResource(entityManagerFactory) as? EntityManagerHolder
        if (!(Tx.isActualTransactionActive() && !Tx.isCurrentTransactionReadOnly() &&
            holder?.entityManager?.isJoinedToTransaction == true && Tx.hasResource(dataSource)))
            throw ImageMaintenanceTransactionRequired()
        jdbc.execute("SET LOCAL lock_timeout='10s'")
        jdbc.execute("SET LOCAL statement_timeout='30s'")
        if (!participants.lockIds(plan.participants)) return null
        val observed = plan.image
        var product: CleanupProduct? = null
        if (observed.tag == "PRODUCT" && observed.entityId != null) {
            product = products.lock(observed.entityId)
            if (product?.ownerId != plan.product?.ownerId) return null
        }
        if (observed.tag == "ORDER" && observed.entityId != null) {
            if (orders.lockImageOrder(observed.entityId) != plan.order) return null
        }
        val current = image(observed.id, true) ?: return null
        // Never follow a changed association/owner/uploader after the participant lock frontier.
        if (current.copy(old = observed.old) != observed) return null
        return current to product
    }

    private fun eligible(row: ImageRow, product: CleanupProduct?): Boolean =
        row.status in setOf("TEMPORARY", "DELETE") && row.old ||
            row.tag == "PRODUCT" && product?.deleted == true

    private fun validKey(filename: String?): Boolean = filename != null && try {
        ImageVariantKey.of(filename, ImageVariant.ORIGINAL)
        true
    } catch (_: IllegalArgumentException) { false }

    private fun sharedKey(row: ImageRow): Boolean = jdbc.queryForObject(
        // Also protects configurations that alias two tags to the same physical bucket.
        "SELECT EXISTS(SELECT 1 FROM image WHERE id<>? AND filename=?)",
        Boolean::class.java, row.id, row.filename) == true

    private fun image(id: Long, lock: Boolean): ImageRow? = jdbc.query("""SELECT id,filename,tag::text,status::text,
        entity_id,uploaded_by,created_at <= CURRENT_TIMESTAMP - interval '24 hours' AS old
        FROM image WHERE id=? ${if (lock) "FOR UPDATE" else ""}""", { rs, _ ->
        ImageRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
            rs.getLong(5).takeUnless { rs.wasNull() }, rs.getLong(6).takeUnless { rs.wasNull() }, rs.getBoolean(7))
    }, id).firstOrNull()

    private data class ImageRow(val id: Long, val filename: String?, val tag: String, val status: String,
                                val entityId: Long?, val uploadedBy: Long?, val old: Boolean)
    private data class Plan(val image: ImageRow, val product: CleanupProduct?, val order: ImageOrderParties?,
                            val participants: List<Long>)
}

/** Dedicated bounded cleanup client; other accepted S3 clients/settings are untouched. */
@Component
@Profile("modern")
class S3ImageCleanupObjects(
    @Value("\${s3.access-key}") accessKey: String,
    @Value("\${s3.secret-key}") secretKey: String,
    @Value("\${s3.endpoint}") endpoint: String,
    @Value("\${s3.region}") region: String,
    @Value("\${s3.participant-bucket-name}") participantBucket: String,
    @Value("\${s3.product-bucket-name}") productBucket: String,
    @Value("\${s3.order-bucket-name}") orderBucket: String,
    @Value("\${s3.system-bucket-name}") systemBucket: String,
) : ImageCleanupObjects {
    private val buckets = mapOf(StorageLocation.PARTICIPANT to participantBucket, StorageLocation.PRODUCT to productBucket,
        StorageLocation.ORDER to orderBucket, StorageLocation.SYSTEM to systemBucket)
    private val client = AmazonS3ClientBuilder.standard()
        .withCredentials(AWSStaticCredentialsProvider(BasicAWSCredentials(accessKey, secretKey)))
        .withEndpointConfiguration(AwsClientBuilder.EndpointConfiguration(endpoint, region))
        .withPathStyleAccessEnabled(true)
        .withClientConfiguration(ClientConfiguration().apply {
            connectionTimeout = 2_000
            socketTimeout = 5_000
            requestTimeout = 10_000
            clientExecutionTimeout = 15_000
            maxErrorRetry = 1
        }).build()

    override fun deleteVariants(tag: String, filename: String) {
        val location = StorageLocation.valueOf(tag)
        val keys = ImageVariant.entries.map { ImageVariantKey.of(filename, it).value }
        for (key in keys) client.deleteObject(requireNotNull(buckets[location]), key)
    }

    @PreDestroy
    fun close() = client.shutdown()
}
