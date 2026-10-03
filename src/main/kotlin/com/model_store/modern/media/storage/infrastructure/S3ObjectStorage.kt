package com.model_store.modern.media.storage.infrastructure

import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.model.GetObjectRequest
import com.amazonaws.services.s3.model.ObjectMetadata
import com.amazonaws.services.s3.model.PutObjectRequest
import com.model_store.modern.media.storage.application.ObjectStorage
import com.model_store.modern.media.storage.application.StoredObject
import com.model_store.modern.media.storage.domain.DefaultImageKey
import com.model_store.modern.media.storage.domain.ImageVariantKey
import com.model_store.modern.media.storage.domain.StorageLocation
import com.model_store.modern.media.storage.domain.StorageKey
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.ByteArrayInputStream

@Component
class S3ObjectStorage(
    private val s3: AmazonS3,
    @Value("\${s3.participant-bucket-name}") private val participantBucket: String,
    @Value("\${s3.product-bucket-name}") private val productBucket: String,
    @Value("\${s3.order-bucket-name}") private val orderBucket: String,
    @Value("\${s3.system-bucket-name}") private val systemBucket: String,
) : ObjectStorage {
    override fun read(location: StorageLocation, key: StorageKey): StoredObject {
        require(key !== DefaultImageKey || location == StorageLocation.SYSTEM) {
            "Default image belongs to the system bucket"
        }
        val s3Object = s3.getObject(GetObjectRequest(bucket(location), key.value))
        return s3Object.use { obj ->
            StoredObject(
                obj.objectContent.use { it.readAllBytes() },
                obj.objectMetadata.contentType ?: contentTypeFor(obj.key),
            )
        }
    }

    override fun write(location: StorageLocation, key: ImageVariantKey, content: ByteArray, contentType: String) {
        require(contentType.isNotBlank()) { "Content type must not be blank" }
        val metadata = ObjectMetadata().apply {
            contentLength = content.size.toLong()
            this.contentType = contentType
        }
        ByteArrayInputStream(content).use { stream ->
            s3.putObject(PutObjectRequest(bucket(location), key.value, stream, metadata))
        }
    }

    override fun delete(location: StorageLocation, key: ImageVariantKey) {
        s3.deleteObject(bucket(location), key.value)
    }

    private fun bucket(location: StorageLocation): String = when (location) {
        StorageLocation.PARTICIPANT -> participantBucket
        StorageLocation.PRODUCT -> productBucket
        StorageLocation.ORDER -> orderBucket
        StorageLocation.SYSTEM -> systemBucket
    }

    private fun contentTypeFor(key: String): String = when {
        key.endsWith(".jpg") || key.endsWith(".jpeg") -> "image/jpeg"
        key.endsWith(".png") -> "image/png"
        key.endsWith(".gif") -> "image/gif"
        key.endsWith(".bmp") -> "image/bmp"
        key.endsWith(".webp") -> "image/webp"
        else -> "application/octet-stream"
    }
}
