package com.model_store.modern.media.image.command.api

import com.model_store.modern.media.image.command.application.ImageCommands
import com.model_store.modern.media.image.command.domain.ImageCommandDenied
import com.model_store.modern.media.image.command.domain.ImageLimitExceeded
import com.model_store.modern.media.image.command.domain.IncomingImage
import com.model_store.modern.media.image.command.domain.UploadTarget
import com.model_store.modern.media.storage.domain.StorageLocation
import com.model_store.modern.shared.domain.Actor
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

@RestController
@Profile("modern")
@RequestMapping("/images")
class ImageCommandController(
    private val commands: ImageCommands,
    @Value("\${app.max-participant-images}") private val maxParticipantImages: Int,
    @Value("\${app.max-product-images}") private val maxProductImages: Int,
) {
    @PostMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun upload(
        @RequestParam tag: StorageLocation,
        @RequestParam(required = false) entityId: Long?,
        @RequestPart("files") files: List<MultipartFile>,
        @AuthenticationPrincipal actor: Actor?,
    ): List<Long> {
        val actorId = actor?.participantId ?: throw ImageCommandDenied()
        val maxFiles = when (tag) {
            StorageLocation.PARTICIPANT -> maxParticipantImages
            StorageLocation.PRODUCT -> maxProductImages
            else -> MAX_FILES
        }
        if (files.size > maxFiles) throw ImageLimitExceeded("Слишком много изображений")
        if (files.any { it.size > MAX_BYTES } || files.sumOf { it.size } > MAX_BYTES)
            throw ImageLimitExceeded("Размер запроса превышает 10 МБ")
        return commands.upload(UploadTarget(tag, entityId, actorId), files.map {
            IncomingImage(it.originalFilename.orEmpty(), it.bytes)
        })
    }

    @DeleteMapping
    fun delete(
        @RequestParam ids: List<Long>,
        @RequestParam tag: StorageLocation,
        @AuthenticationPrincipal actor: Actor?,
    ): ResponseEntity<Void> {
        commands.delete(ids, tag, actor?.participantId ?: throw ImageCommandDenied())
        return ResponseEntity.ok().build()
    }

    private companion object {
        const val MAX_BYTES = 10L * 1024 * 1024
        const val MAX_FILES = 20
    }
}
