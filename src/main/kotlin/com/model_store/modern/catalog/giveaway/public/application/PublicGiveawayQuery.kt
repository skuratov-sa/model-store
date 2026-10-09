package com.model_store.modern.catalog.giveaway.`public`.application

import com.model_store.modern.catalog.giveaway.`public`.domain.PublicGiveaway
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

interface PublicGiveawayReadPort {
    fun active(): PublicGiveaway?
    fun byProductId(productId: Long): PublicGiveaway?
}

@Service
class PublicGiveawayQuery(private val read: PublicGiveawayReadPort) {
    @Transactional(transactionManager = "transactionManager", readOnly = true)
    fun active(): PublicGiveaway? = read.active()

    @Transactional(transactionManager = "transactionManager", readOnly = true)
    fun byProductId(productId: Long): PublicGiveaway? = read.byProductId(productId)
}
