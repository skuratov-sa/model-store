package com.model_store.modern.media.image.read.api

import com.model_store.modern.media.image.read.application.ReadImages
import com.model_store.modern.media.storage.domain.StorageLocation
import com.model_store.modern.shared.domain.Actor
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.util.UriUtils
import java.nio.charset.StandardCharsets

data class ImageResponse(val fileName: String, val contentType: String, val imageData: ByteArray)
data class ImageMetadataDto(
    val id: Long,
    val originalUrl: String,
    val mediumUrl: String,
    val thumbnailUrl: String,
    val width: Int?,
    val height: Int?,
    val contentType: String?,
)

@RestController
@Profile("modern")
@RequestMapping("/images")
class ImageReadController(
    private val readImages: ReadImages,
    @Value("\${app.cdn-base-url}") private val cdnBaseUrl: String,
    @Value("\${s3.participant-bucket-name}") private val participantBucket: String,
    @Value("\${s3.product-bucket-name}") private val productBucket: String,
    @Value("\${s3.system-bucket-name}") private val systemBucket: String,
) {
    @GetMapping
    fun images(@RequestParam ids: List<Long>, @AuthenticationPrincipal actor: Actor?): List<ImageResponse> =
        readImages.byIds(ids, actor?.participantId).map { ImageResponse(it.fileName, it.contentType, it.imageData) }

    @GetMapping("/default")
    fun default(): ImageResponse = readImages.default().let { ImageResponse(it.fileName, it.contentType, it.imageData) }

    @GetMapping("/metadata")
    fun metadata(@RequestParam ids: List<Long>): List<ImageMetadataDto> = readImages.activeImages(ids).map { image ->
        val bucket = when (image.location) {
            StorageLocation.PARTICIPANT -> participantBucket
            StorageLocation.PRODUCT -> productBucket
            StorageLocation.SYSTEM -> systemBucket
            StorageLocation.ORDER -> error("Order images cannot expose CDN metadata")
        }
        val safeFilename = UriUtils.encodePathSegment(image.filename, StandardCharsets.UTF_8)
        fun url(variant: String) = "$cdnBaseUrl/$bucket/$variant/$safeFilename"
        ImageMetadataDto(image.id, url("original"), url("medium"), url("thumbnail"),
            image.width, image.height, image.contentType)
    }
}
