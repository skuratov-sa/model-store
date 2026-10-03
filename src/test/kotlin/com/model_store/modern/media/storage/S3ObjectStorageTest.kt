package com.model_store.modern.media.storage

import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.model.GetObjectRequest
import com.amazonaws.services.s3.model.PutObjectRequest
import com.amazonaws.services.s3.model.S3Object
import com.amazonaws.services.s3.model.S3ObjectInputStream
import com.model_store.modern.media.storage.domain.DefaultImageKey
import com.model_store.modern.media.storage.domain.ImageVariant
import com.model_store.modern.media.storage.domain.ImageVariantKey
import com.model_store.modern.media.storage.domain.StorageLocation
import com.model_store.modern.media.storage.infrastructure.S3ObjectStorage
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

class S3ObjectStorageTest {
    private val s3 = mock(AmazonS3::class.java)
    private val storage = S3ObjectStorage(s3, "participant-bucket", "product-bucket", "order-bucket", "system-bucket")

    @Test
    fun `reads a legacy variant from its original bucket and closes its stream`() {
        val key = ImageVariantKey.of("abcdef1234.jpg", ImageVariant.ORIGINAL)
        val content = "image bytes".toByteArray()
        var closed = false
        val source = object : ByteArrayInputStream(content) {
            override fun close() {
                closed = true
                super.close()
            }
        }
        val stream = S3ObjectInputStream(source, null)
        val oldObject = S3Object().apply {
            this.key = key.value
            objectContent = stream
        }
        `when`(s3.getObject(org.mockito.ArgumentMatchers.any(GetObjectRequest::class.java))).thenReturn(oldObject)

        val result = storage.read(StorageLocation.PRODUCT, key)

        assertThat(result.content).isEqualTo(content)
        assertThat(result.contentType).isEqualTo("image/jpeg")
        assertThat(closed).isTrue()
        val request = ArgumentCaptor.forClass(GetObjectRequest::class.java)
        verify(s3).getObject(request.capture())
        assertThat(request.value.bucketName).isEqualTo("product-bucket")
        assertThat(request.value.key).isEqualTo("original/abcdef1234.jpg")
    }

    @Test
    fun `writes to the existing bucket with exact key and content metadata`() {
        val bytes = byteArrayOf(1, 2, 3)
        storage.write(StorageLocation.ORDER, ImageVariantKey.of("photo.jpg", ImageVariant.THUMBNAIL), bytes, "image/jpeg")

        val request = ArgumentCaptor.forClass(PutObjectRequest::class.java)
        verify(s3).putObject(request.capture())
        assertThat(request.value.bucketName).isEqualTo("order-bucket")
        assertThat(request.value.key).isEqualTo("thumbnail/photo.jpg")
        assertThat(request.value.metadata.contentLength).isEqualTo(3)
        assertThat(request.value.metadata.contentType).isEqualTo("image/jpeg")
    }

    @Test
    fun `reads a legacy default image stored at bucket root`() {
        val oldObject = S3Object().apply {
            key = DefaultImageKey.value
            objectContent = S3ObjectInputStream(ByteArrayInputStream(byteArrayOf(9)), null)
        }
        `when`(s3.getObject(org.mockito.ArgumentMatchers.any(GetObjectRequest::class.java))).thenReturn(oldObject)

        assertThat(storage.read(StorageLocation.SYSTEM, DefaultImageKey).contentType).isEqualTo("image/jpeg")
        val request = ArgumentCaptor.forClass(GetObjectRequest::class.java)
        verify(s3).getObject(request.capture())
        assertThat(request.value.bucketName).isEqualTo("system-bucket")
        assertThat(request.value.key).isEqualTo("not_found.jpeg")
    }

    @Test
    fun `deletes the supplied legacy variant key`() {
        storage.delete(StorageLocation.PARTICIPANT, ImageVariantKey.of("photo.jpg", ImageVariant.MEDIUM))
        verify(s3).deleteObject("participant-bucket", "medium/photo.jpg")
    }

    @Test
    fun `rejects malformed image basenames`() {
        assertThatThrownBy { ImageVariantKey.of("../elsewhere.jpg", ImageVariant.ORIGINAL) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `cannot read the default image from another bucket`() {
        assertThatThrownBy { storage.read(StorageLocation.ORDER, DefaultImageKey) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `closes the object stream when reading fails`() {
        var closed = false
        val failingSource = object : InputStream() {
            override fun read(): Int = throw IOException("failed to read object")
            override fun close() {
                closed = true
            }
        }
        val oldObject = S3Object().apply {
            key = "original/photo.jpg"
            objectContent = S3ObjectInputStream(failingSource, null)
        }
        `when`(s3.getObject(org.mockito.ArgumentMatchers.any(GetObjectRequest::class.java))).thenReturn(oldObject)

        assertThatThrownBy { storage.read(StorageLocation.PRODUCT, ImageVariantKey.of("photo.jpg", ImageVariant.ORIGINAL)) }
            .isInstanceOf(IOException::class.java)
        assertThat(closed).isTrue()
    }
}
