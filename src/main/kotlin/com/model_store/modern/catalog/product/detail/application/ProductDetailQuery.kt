package com.model_store.modern.catalog.product.detail.application

import com.model_store.modern.catalog.product.detail.domain.MyProduct
import com.model_store.modern.catalog.product.detail.domain.MyProductsFilter
import com.model_store.modern.catalog.product.detail.domain.ProductCard
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

interface ProductDetailReadPort {
    fun publicCard(id: Long): ProductCard?
    fun myProducts(ownerId: Long, includeGiveaways: Boolean, filter: MyProductsFilter): List<MyProduct>
}

@Service
class ProductDetailQuery(private val read: ProductDetailReadPort) {
    @Transactional(transactionManager = "transactionManager", readOnly = true)
    fun card(id: Long): ProductCard? = read.publicCard(id)

    @Transactional(transactionManager = "transactionManager", readOnly = true)
    fun mine(ownerId: Long, isAdmin: Boolean, filter: MyProductsFilter): List<MyProduct> =
        read.myProducts(ownerId, isAdmin, filter)
}
