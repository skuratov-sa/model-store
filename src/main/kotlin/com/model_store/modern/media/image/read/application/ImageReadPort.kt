package com.model_store.modern.media.image.read.application

import com.model_store.modern.media.image.read.domain.OrderParties
import com.model_store.modern.media.image.read.domain.ReadableImage

interface ImageReadPort {
    fun findActive(ids: Collection<Long>): List<ReadableImage>
    fun viewerIsAdmin(viewerId: Long): Boolean?
    fun findOrderParties(ids: Collection<Long>): Map<Long, OrderParties>
}
