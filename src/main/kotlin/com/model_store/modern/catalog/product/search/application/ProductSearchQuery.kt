package com.model_store.modern.catalog.product.search.application

import com.model_store.modern.catalog.product.search.domain.ProductSearchCriteria
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

data class SearchCategory(val id: Long, val name: String?)

data class ProductSearchRow(
    val id: Long,
    val name: String?,
    val count: Int?,
    val price: Float,
    val prepaymentAmount: Float?,
    val currency: String,
    val imageId: Long?,
    val sellerId: Long,
    val expirationDate: Instant?,
    val status: String,
    val availability: String,
    val used: Boolean?,
    val sellerLogin: String,
    val sellerRating: Float,
    val totalReviews: Int,
    val createdAt: Instant?,
    val categories: List<SearchCategory> = emptyList(),
)

interface ProductSearchReadPort {
    fun adultAge(participantId: Long): Int?
    fun findProducts(criteria: ProductSearchCriteria, includeAdult: Boolean): List<ProductSearchRow>
    fun findNames(search: String?): List<String>
}

@Service
class ProductSearchQuery(private val read: ProductSearchReadPort) {
    @Transactional(transactionManager = "transactionManager", readOnly = true)
    fun products(criteria: ProductSearchCriteria, participantId: Long?): List<ProductSearchRow> {
        val includeAdult = participantId?.let(read::adultAge)?.let { it >= 18 } ?: false
        return read.findProducts(criteria, includeAdult)
    }

    @Transactional(transactionManager = "transactionManager", readOnly = true)
    fun names(search: String?): List<String> = read.findNames(search)
}
