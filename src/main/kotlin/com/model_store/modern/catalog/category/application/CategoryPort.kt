package com.model_store.modern.catalog.category.application

import com.model_store.modern.catalog.category.domain.Category

interface CategoryPort {
    fun all(): List<Category>
    fun existingIds(ids: Collection<Long>): Set<Long>
    fun create(name: String, parentId: Long?): Long
    fun rename(id: Long, name: String)
    fun findByProduct(productId: Long): List<Category>
    fun productExists(productId: Long): Boolean
    fun addLinks(productId: Long, categoryIds: List<Long>)
}
