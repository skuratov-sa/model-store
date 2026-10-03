package com.model_store.modern.media.storage.domain

/** The four buckets used by the existing image records. */
enum class StorageLocation {
    PARTICIPANT, PRODUCT, ORDER, SYSTEM,
}

enum class ImageVariant(val directory: String) {
    ORIGINAL("original"), MEDIUM("medium"), THUMBNAIL("thumbnail"),
}

sealed interface StorageKey {
    val value: String
}

/** Image rows keep only the basename; the variant is part of the S3 key. */
class ImageVariantKey private constructor(
    val filename: String,
    val variant: ImageVariant,
) : StorageKey {
    override val value: String = "${variant.directory}/$filename"

    companion object {
        fun of(filename: String, variant: ImageVariant): ImageVariantKey {
            require(filename.isNotBlank() && filename != "." && filename != ".." &&
                '/' !in filename && '\\' !in filename && filename.none { it.isISOControl() }) {
                "Image filename must be a basename"
            }
            return ImageVariantKey(filename, variant)
        }
    }
}

/** The legacy system default image is stored at the bucket root and is read only. */
data object DefaultImageKey : StorageKey {
    override val value: String = "not_found.jpeg"
}
