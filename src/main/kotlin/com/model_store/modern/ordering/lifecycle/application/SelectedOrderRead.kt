package com.model_store.modern.ordering.lifecycle.application

/** Bounded projection of selected order IDs; same enrichment as ordinary lifecycle reads. */
interface SelectedOrderRead {
    fun ordersByIds(ids: List<Long>, seller: Boolean): List<OrderView>
}
