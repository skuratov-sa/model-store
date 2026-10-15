package com.model_store.modern.catalog.product.command.infrastructure

import com.model_store.modern.catalog.product.command.application.FailureKind
import com.model_store.modern.catalog.product.command.application.ProductCommandFailure
import com.model_store.modern.catalog.product.command.application.ProductImageLink
import com.model_store.modern.media.image.command.application.ProductImageAttachment
import com.model_store.modern.media.image.command.application.ProductImageAttachmentFailure
import com.model_store.modern.media.image.command.application.ProductImageAttachmentProblem
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Repository

@Repository
@Profile("modern")
class JdbcProductImageLink(private val media: ProductImageAttachment) : ProductImageLink {
    override fun claim(productId: Long, ownerId: Long, imageIds: List<Long>) = translate {
        media.claim(productId, ownerId, imageIds)
    }

    override fun deleteProductImages(productId: Long) = translate {
        media.markProductImagesDeleted(productId)
    }

    private fun translate(action: () -> Unit) {
        try {
            action()
        } catch (error: ProductImageAttachmentFailure) {
            throw when (error.problem) {
                ProductImageAttachmentProblem.DUPLICATE_IDS -> failure(FailureKind.BAD_REQUEST,
                    "INVALID_REQUEST", "Повторяющиеся ID изображений")
                ProductImageAttachmentProblem.IMAGE_NOT_FOUND -> failure(FailureKind.NOT_FOUND,
                    "IMAGE_NOT_FOUND", "Не удалось найти изображение")
                ProductImageAttachmentProblem.ACCESS_DENIED -> failure(FailureKind.FORBIDDEN,
                    "ACCESS_DENIED", "Доступ к изображению запрещён")
                ProductImageAttachmentProblem.ALREADY_ATTACHED -> failure(FailureKind.CONFLICT,
                    "INVALID_REQUEST", "Изображение уже связано с другим товаром")
                ProductImageAttachmentProblem.TOO_MANY_IMAGES -> failure(FailureKind.BAD_REQUEST,
                    "INVALID_REQUEST", "Слишком много изображений")
                ProductImageAttachmentProblem.CLAIM_CONFLICT -> failure(FailureKind.CONFLICT,
                    "INVALID_REQUEST", "Не удалось связать изображение с товаром")
                ProductImageAttachmentProblem.IMAGE_BUSY -> failure(FailureKind.CONFLICT,
                    "INVALID_REQUEST", "Изображение занято другой операцией")
            }
        }
    }

    private fun failure(kind: FailureKind, code: String, message: String) =
        ProductCommandFailure(kind, code, message)
}
