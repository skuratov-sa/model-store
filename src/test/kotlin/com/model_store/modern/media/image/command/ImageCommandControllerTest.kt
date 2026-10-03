package com.model_store.modern.media.image.command

import com.model_store.modern.media.image.command.api.ImageCommandController
import com.model_store.modern.media.image.command.api.ImageCommandErrorHandler
import com.model_store.modern.media.image.command.application.ImageCommandRows
import com.model_store.modern.media.image.command.application.ImageCommands
import com.model_store.modern.media.image.command.domain.ImageToInsert
import com.model_store.modern.media.image.command.domain.UploadTarget
import com.model_store.modern.media.image.command.infrastructure.JpegImageEncoder
import com.model_store.modern.media.storage.application.ObjectStorage
import com.model_store.modern.media.storage.application.StoredObject
import com.model_store.modern.media.storage.domain.ImageVariantKey
import com.model_store.modern.media.storage.domain.StorageKey
import com.model_store.modern.media.storage.domain.StorageLocation
import com.model_store.modern.shared.domain.Actor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.core.MethodParameter
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

class ImageCommandControllerTest {
    @Test
    fun `MVC multipart files tag entityId and delete ids retain route contract`() {
        val rows = FakeRows()
        val storage = FakeStorage()
        val controller = ImageCommandController(ImageCommands(rows, storage, JpegImageEncoder()), 1, 4)
        val mvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(ImageCommandErrorHandler())
            .setCustomArgumentResolvers(ActorResolver())
            .build()
        val png = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()

        mvc.perform(multipart("/images")
            .file(MockMultipartFile("files", "first.png", "image/png", png))
            .file(MockMultipartFile("files", "second.png", "image/png", png))
            .param("tag", "PRODUCT").param("entityId", "42"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0]").value(101))
            .andExpect(jsonPath("$[1]").value(102))
        assertEquals(UploadTarget(StorageLocation.PRODUCT, 42, 1), rows.uploadTarget)
        assertEquals(6, storage.writes)

        mvc.perform(delete("/images").param("ids", "101,102").param("tag", "PRODUCT"))
            .andExpect(status().isOk)
        assertEquals(listOf(101L, 102L), rows.deleted)

        mvc.perform(multipart("/images")
            .file(MockMultipartFile("files", "../../secret.txt", MediaType.TEXT_PLAIN_VALUE, "not an image".toByteArray()))
            .param("tag", "PRODUCT"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.message").value("Невозможно прочитать изображение"))
        assertEquals(6, storage.writes)

        val tooMany = multipart("/images").param("tag", "PRODUCT")
        repeat(5) { tooMany.file(MockMultipartFile("files", "a.png", "image/png", png)) }
        mvc.perform(tooMany).andExpect(status().isContentTooLarge)
        assertEquals(6, storage.writes)
    }

    private class ActorResolver : HandlerMethodArgumentResolver {
        override fun supportsParameter(parameter: MethodParameter): Boolean =
            parameter.hasParameterAnnotation(AuthenticationPrincipal::class.java) && parameter.parameterType == Actor::class.java

        override fun resolveArgument(parameter: MethodParameter, mavContainer: ModelAndViewContainer?,
                                     webRequest: NativeWebRequest, binderFactory: WebDataBinderFactory?): Any =
            Actor(1, "owner", "USER")
    }

    private class FakeRows : ImageCommandRows {
        var uploadTarget: UploadTarget? = null
        var deleted: List<Long>? = null
        override fun requireUploadAllowed(target: UploadTarget) { uploadTarget = target }
        override fun insertBatch(target: UploadTarget, images: List<ImageToInsert>): List<Long> =
            images.indices.map { 101L + it }
        override fun markDeleted(ids: List<Long>, tag: StorageLocation, actorId: Long) { deleted = ids }
    }

    private class FakeStorage : ObjectStorage {
        var writes = 0
        override fun read(location: StorageLocation, key: StorageKey): StoredObject = error("unused")
        override fun write(location: StorageLocation, key: ImageVariantKey, content: ByteArray, contentType: String) { writes++ }
        override fun delete(location: StorageLocation, key: ImageVariantKey) = Unit
    }
}
