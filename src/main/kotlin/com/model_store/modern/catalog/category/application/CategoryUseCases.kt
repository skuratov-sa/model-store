package com.model_store.modern.catalog.category.application

import com.model_store.modern.catalog.category.domain.Category
import com.model_store.modern.catalog.category.domain.CategoryBranch
import com.model_store.modern.catalog.category.domain.CategoryInvalidReference
import com.model_store.modern.catalog.category.domain.CategoryTree
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class CategoryUseCases(private val port: CategoryPort) {
    @Transactional(transactionManager = "transactionManager", readOnly = true)
    fun tree(): List<CategoryBranch> = CategoryTree.assemble(port.all())

    @Transactional(transactionManager = "transactionManager")
    fun create(name: String, parentId: Long?): Long {
        CategoryTree.validateParent(parentId, parentId?.let { port.existingIds(listOf(it)) } ?: emptySet())
        return port.create(name, parentId)
    }

    @Transactional(transactionManager = "transactionManager")
    fun rename(id: Long, name: String) {
        // Legacy treats an unknown ID as a successful no-op.
        port.rename(id, name)
    }

    @Transactional(transactionManager = "transactionManager", readOnly = true)
    fun byProduct(productId: Long): List<Category> = port.findByProduct(productId)

    @Transactional(transactionManager = "transactionManager")
    fun addLinks(productId: Long, ids: List<Long>) {
        if (ids.isEmpty()) return
        if (!port.productExists(productId)) throw CategoryInvalidReference("Product does not exist: $productId")
        CategoryTree.validateLinks(ids, port.existingIds(ids))
        port.addLinks(productId, ids)
    }
}
