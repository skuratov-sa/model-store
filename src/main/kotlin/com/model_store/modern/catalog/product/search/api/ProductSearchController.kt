package com.model_store.modern.catalog.product.search.api

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.model_store.modern.catalog.product.search.application.ProductSearchQuery
import com.model_store.modern.catalog.product.search.domain.CatalogFlag
import com.model_store.modern.catalog.product.search.domain.ProductSearchCriteria
import com.model_store.modern.catalog.product.search.domain.SearchDateRange
import com.model_store.modern.catalog.product.search.domain.SearchPage
import com.model_store.modern.catalog.product.search.domain.SearchPriceRange
import com.model_store.modern.catalog.product.search.domain.SearchSort
import com.model_store.modern.shared.domain.Actor
import com.model_store.modern.shared.domain.TokenType
import org.springframework.context.annotation.Profile
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

/** Jackson maps absent/null Java primitive fields in the legacy request to zero. */
data class SearchPageRequest(
    val size: Int? = null,
    val lastCreatedAt: Instant? = null,
    val lastPrice: Float? = null,
    val lastId: Long? = null,
    val sortBy: SearchSort? = null,
) {
    fun criteria() = SearchPage(size ?: 0, lastCreatedAt, lastPrice, lastId, sortBy)
}

data class SearchPriceRangeRequest(val minPrice: Int? = null, val maxPrice: Int? = null) {
    fun criteria() = SearchPriceRange(minPrice ?: 0, maxPrice ?: 0)
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class ProductSearchRequest(
    val name: String? = null,
    val categoryId: Long? = null,
    val catalogFlags: List<CatalogFlag>? = null,
    val used: Boolean? = null,
    val originality: String? = null,
    val participantId: Long? = null,
    val priceRange: SearchPriceRangeRequest? = null,
    val dateRange: SearchDateRange? = null,
    val pageable: SearchPageRequest? = null,
) {
    fun criteria() = ProductSearchCriteria(
        name, categoryId, catalogFlags, used, originality, participantId,
        priceRange?.criteria(), dateRange, pageable?.criteria(),
    )
}

@RestController
@Profile("modern")
class ProductSearchController(private val query: ProductSearchQuery) {
    @PostMapping("/products/find")
    fun products(
        @RequestBody request: ProductSearchRequest,
        @AuthenticationPrincipal actor: Actor?,
    ): List<ProductSearchDto> {
        val viewerId = actor?.takeIf { it.tokenType == TokenType.ACCESS || it.isAgentAccess }?.participantId
        return query.products(request.criteria(), viewerId).map(ProductSearchDtoMapper::map)
    }

    @PostMapping("/products/names/find")
    fun names(@RequestParam(required = false) name: String?): List<String> = query.names(name)
}
