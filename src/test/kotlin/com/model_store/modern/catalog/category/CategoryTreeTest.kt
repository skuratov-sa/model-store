package com.model_store.modern.catalog.category

import com.model_store.modern.catalog.category.domain.Category
import com.model_store.modern.catalog.category.domain.CategoryRuleViolation
import com.model_store.modern.catalog.category.domain.CategoryIntegrityFailure
import com.model_store.modern.catalog.category.domain.CategoryInvalidReference
import com.model_store.modern.catalog.category.api.CategoryErrorHandler
import com.model_store.modern.catalog.category.domain.CategoryTree
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class CategoryTreeTest {
    @Test
    fun `orders every tree level by display order null last then ID`() {
        val tree = CategoryTree.assemble(listOf(
            Category(8, "late", 1, null),
            Category(4, "second", 1, 1),
            Category(1, "root", null, 1),
            Category(3, "first", 1, 1),
            Category(2, "other root", null, null),
        ))
        assertEquals(listOf(1L, 2L), tree.map { it.id })
        assertEquals(listOf(3L, 4L, 8L), tree.first().children.map { it.id })
    }

    @Test
    fun `rejects damaged parent graph and missing links`() {
        assertThrows(CategoryIntegrityFailure::class.java) {
            CategoryTree.assemble(listOf(Category(1, "orphan", 2, null)))
        }
        assertThrows(CategoryIntegrityFailure::class.java) {
            CategoryTree.assemble(listOf(Category(1, "a", 2, null), Category(2, "b", 1, null)))
        }
        assertThrows(CategoryRuleViolation::class.java) {
            CategoryTree.validateLinks(listOf(1, 2), setOf(1))
        }
        assertThrows(CategoryRuleViolation::class.java) {
            CategoryTree.validateParent(7, emptySet())
        }
    }

    @Test
    fun `reports damaged stored tree as server failure and bad link as invalid reference`() {
        val handler = CategoryErrorHandler()
        val corrupt = handler.corruptTree(CategoryIntegrityFailure("cycle"))
        assertEquals(500, corrupt.statusCode.value())
        assertEquals("INTERNAL_ERROR", corrupt.body!!.code)
        val missing = handler.invalid(CategoryInvalidReference("missing"))
        assertEquals(400, missing.statusCode.value())
        assertEquals("INVALID_REFERENCE", missing.body!!.code)
    }
}
