package com.model_store.modern.catalog.product.detail

import com.fasterxml.jackson.databind.ObjectMapper
import com.model_store.model.dto.GetProductResponse
import com.model_store.model.dto.ProductDto
import com.model_store.modern.catalog.product.detail.api.MyProductsRequest
import com.model_store.modern.catalog.product.detail.api.ProductCardResponse
import com.model_store.modern.catalog.product.detail.api.MyProductResponse
import com.model_store.modern.catalog.product.detail.api.ProductDetailController
import com.model_store.modern.catalog.product.detail.application.ProductDetailQuery
import com.model_store.modern.catalog.product.detail.application.ProductDetailReadPort
import com.model_store.modern.catalog.product.detail.domain.MyProduct
import com.model_store.modern.catalog.product.detail.domain.MyProductsFilter
import com.model_store.modern.catalog.product.detail.domain.ProductCard
import com.model_store.modern.shared.domain.Actor
import com.model_store.modern.shared.domain.TokenType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

class ProductDetailControllerTest {
    private val port = object : ProductDetailReadPort {
        var owner: Long? = null
        var giveaways: Boolean? = null
        override fun publicCard(id: Long): ProductCard? = null
        override fun myProducts(ownerId: Long, includeGiveaways: Boolean, filter: MyProductsFilter): List<MyProduct> {
            owner = ownerId
            giveaways = includeGiveaways
            return emptyList()
        }
    }
    private val controller = ProductDetailController(ProductDetailQuery(port))

    @Test
    fun `public missing card returns 404`() {
        assertEquals(HttpStatus.NOT_FOUND, controller.card(99).statusCode)
    }

    @Test
    fun `my products uses verified actor id and admin role`() {
        val noActor = assertThrows(ResponseStatusException::class.java) { controller.mine(null, MyProductsRequest()) }
        assertEquals(HttpStatus.UNAUTHORIZED, noActor.statusCode)
        controller.mine(Actor(10, "seller", "USER"), MyProductsRequest())
        assertEquals(10L, port.owner)
        assertFalse(port.giveaways!!)
        controller.mine(Actor(11, "admin", "ADMIN"), MyProductsRequest())
        assertEquals(11L, port.owner)
        assertTrue(port.giveaways!!)
        controller.mine(Actor(12, "agent", "USER", TokenType.AGENT_ACCESS), MyProductsRequest())
        assertEquals(12L, port.owner)
        assertFalse(port.giveaways!!)
    }

    @Test
    fun `response field names and null fields match legacy DTOs`() {
        val json = ObjectMapper()
        val card = ProductCardResponse(1, null, null, 1f, null, null, "RUB", null,
            10, "ACTIVE", emptyList(), "PURCHASABLE", null, null, emptyList(), emptyList(),
            "seller", 0f, 0)
        val mine = MyProductResponse(1, null, null, 1f, null, "RUB", emptyList(), null,
            10, null, "ACTIVE", "PURCHASABLE", null, null, "seller", 0f, 0, null)
        fun fields(value: Any) = json.readTree(json.writeValueAsString(value)).fieldNames().asSequence().toSet()
        assertEquals(fields(GetProductResponse.builder().build()), fields(card))
        assertEquals(fields(ProductDto.builder().build()), fields(mine))
        assertTrue(json.readTree(json.writeValueAsString(card)).has("externalUrl"))
    }
}
