package com.model_store.modern.media.storage.application

import com.model_store.modern.media.storage.domain.StorageLocation
import com.model_store.modern.media.storage.domain.StorageKey
import com.model_store.modern.media.storage.domain.ImageVariantKey

/** Synchronous boundary for existing image objects. Authorization belongs to the calling use case. */
interface ObjectStorage {
    fun read(location: StorageLocation, key: StorageKey): StoredObject
    fun write(location: StorageLocation, key: ImageVariantKey, content: ByteArray, contentType: String)
    fun delete(location: StorageLocation, key: ImageVariantKey)
}

data class StoredObject(val content: ByteArray, val contentType: String)
