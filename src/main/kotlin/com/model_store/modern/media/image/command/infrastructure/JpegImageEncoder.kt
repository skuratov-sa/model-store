package com.model_store.modern.media.image.command.infrastructure

import com.model_store.modern.media.image.command.application.ImageEncoder
import com.model_store.modern.media.image.command.domain.IncomingImage
import com.model_store.modern.media.image.command.domain.InvalidImage
import com.model_store.modern.media.image.command.domain.PreparedImage
import com.model_store.modern.media.storage.domain.ImageVariant
import org.imgscalr.Scalr
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

@Component
@Profile("modern")
class JpegImageEncoder : ImageEncoder {
    override fun prepare(image: IncomingImage): PreparedImage {
        if (image.bytes.isEmpty()) throw InvalidImage("Пустой файл изображения")
        val source = try {
            ImageIO.createImageInputStream(ByteArrayInputStream(image.bytes)).use { stream ->
                if (stream == null) throw InvalidImage("Невозможно прочитать изображение")
                val readers = ImageIO.getImageReaders(stream)
                if (!readers.hasNext()) throw InvalidImage("Невозможно прочитать изображение")
                val reader = readers.next()
                try {
                    reader.input = stream
                    val width = reader.getWidth(0)
                    val height = reader.getHeight(0)
                    if (width <= 0 || height <= 0 || width.toLong() * height > MAX_PIXELS)
                        throw InvalidImage("Слишком большое изображение")
                    reader.read(0) ?: throw InvalidImage("Невозможно прочитать изображение")
                } finally {
                    reader.dispose()
                }
            }
        } catch (ex: InvalidImage) {
            throw ex
        } catch (ex: Exception) {
            throw InvalidImage("Невозможно прочитать изображение")
        }
        val name = UUID.randomUUID().toString() + ".jpg"
        return PreparedImage(
            name, source.width, source.height,
            mapOf(
                ImageVariant.ORIGINAL to jpeg(source, 1.0f),
                ImageVariant.MEDIUM to jpeg(resize(source, 1200), 0.85f),
                ImageVariant.THUMBNAIL to jpeg(resize(source, 400), 0.80f),
            ),
        )
    }

    private fun resize(image: BufferedImage, box: Int): BufferedImage =
        if (image.width > box || image.height > box)
            Scalr.resize(image, Scalr.Method.ULTRA_QUALITY, Scalr.Mode.AUTOMATIC, box, box)
        else image

    private fun jpeg(image: BufferedImage, quality: Float): ByteArray {
        val rgb = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB)
        val graphics = rgb.createGraphics()
        try {
            graphics.color = Color.WHITE
            graphics.fillRect(0, 0, image.width, image.height)
            graphics.drawImage(image, 0, 0, null)
        } finally {
            graphics.dispose()
        }
        val writers = ImageIO.getImageWritersByFormatName("jpeg")
        if (!writers.hasNext()) throw InvalidImage("JPEG encoder is unavailable")
        val writer = writers.next()
        return try {
            ByteArrayOutputStream().use { output ->
                ImageIO.createImageOutputStream(output).use { stream ->
                    writer.output = stream
                    val options = writer.defaultWriteParam.apply {
                        compressionMode = ImageWriteParam.MODE_EXPLICIT
                        compressionQuality = quality
                    }
                    writer.write(null, IIOImage(rgb, null, null), options)
                    stream.flush()
                }
                output.toByteArray()
            }
        } finally {
            writer.dispose()
        }
    }

    private companion object {
        const val MAX_PIXELS = 16_000_000L
    }
}
