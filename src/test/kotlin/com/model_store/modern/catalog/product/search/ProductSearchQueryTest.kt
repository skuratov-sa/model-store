package com.model_store.modern.catalog.product.search

import com.model_store.modern.catalog.product.search.application.ProductSearchQuery
import com.model_store.modern.catalog.product.search.application.ProductSearchReadPort
import com.model_store.modern.catalog.product.search.application.ProductSearchRow
import com.model_store.modern.catalog.product.search.api.ProductSearchController
import com.model_store.modern.catalog.product.search.api.ProductSearchRequest
import com.model_store.modern.catalog.product.search.domain.CatalogFlag
import com.model_store.modern.catalog.product.search.domain.ProductSearchCriteria
import com.model_store.modern.catalog.product.search.domain.SearchPage
import com.model_store.modern.catalog.product.search.domain.SearchSort
import com.model_store.modern.shared.domain.Actor
import com.model_store.modern.shared.domain.TokenType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ProductSearchQueryTest {
    @Test
    fun `catalog flags and explicit used retain legacy precedence`() {
        val all = ProductSearchCriteria(catalogFlags = listOf(CatalogFlag.ALL))
        assertEquals(null, all.preorderFilter)
        assertEquals(null, all.usedFilter)
        assertEquals(true, ProductSearchCriteria(catalogFlags = listOf(CatalogFlag.PREORDER)).preorderFilter)
        assertEquals(false, ProductSearchCriteria(catalogFlags = listOf(CatalogFlag.NON_PREORDER)).preorderFilter)
        assertEquals(null, ProductSearchCriteria(catalogFlags = listOf(CatalogFlag.PREORDER, CatalogFlag.NON_PREORDER)).preorderFilter)
        assertEquals(false, ProductSearchCriteria(used = false, catalogFlags = listOf(CatalogFlag.USED)).usedFilter)
        assertEquals(true, ProductSearchCriteria(catalogFlags = listOf(CatalogFlag.USED)).usedFilter)
        assertEquals(SearchPage(50, lastId = 0, sortBy = SearchSort.DATE_DESC), all.effectivePage)
        assertEquals(SearchPage(0, lastId = 0, sortBy = SearchSort.DATE_DESC),
            ProductSearchCriteria(pageable = SearchPage()).effectivePage)
    }

    @Test
    fun `adult visibility comes only from an active profile age`() {
        val seen = mutableListOf<Boolean>()
        val port = object : ProductSearchReadPort {
            override fun adultAge(participantId: Long): Int? = mapOf(1L to 18, 2L to 17, 3L to null)[participantId]
            override fun findProducts(criteria: ProductSearchCriteria, includeAdult: Boolean): List<ProductSearchRow> {
                seen += includeAdult
                return emptyList()
            }
            override fun findNames(search: String?) = emptyList<String>()
        }
        val query = ProductSearchQuery(port)
        listOf(null, 1L, 2L, 3L).forEach { query.products(ProductSearchCriteria(), it) }
        assertEquals(listOf(false, true, false, false), seen)
    }

    @Test
    fun `verified access and agent actors control adult visibility`() {
        val seen = mutableListOf<Boolean>()
        val port = object : ProductSearchReadPort {
            override fun adultAge(participantId: Long) = 18
            override fun findProducts(criteria: ProductSearchCriteria, includeAdult: Boolean): List<ProductSearchRow> {
                seen += includeAdult
                return emptyList()
            }
            override fun findNames(search: String?) = emptyList<String>()
        }
        val controller = ProductSearchController(ProductSearchQuery(port))
        controller.products(ProductSearchRequest(), null)
        controller.products(ProductSearchRequest(), Actor(1, "adult", "USER"))
        controller.products(ProductSearchRequest(), Actor(1, "adult", "USER", TokenType.AGENT_ACCESS))
        assertEquals(listOf(false, true, true), seen)
    }
}
