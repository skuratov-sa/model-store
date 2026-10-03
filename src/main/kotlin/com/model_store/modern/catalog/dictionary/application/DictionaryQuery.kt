package com.model_store.modern.catalog.dictionary.application

import com.model_store.modern.catalog.dictionary.domain.DictionaryEntry
import com.model_store.modern.catalog.dictionary.domain.DictionaryType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

fun interface DictionaryReadPort {
    fun findByType(type: DictionaryType): List<DictionaryEntry>
}

@Service
class DictionaryQuery(private val entries: DictionaryReadPort) {
    @Transactional(transactionManager = "transactionManager", readOnly = true)
    fun findByType(type: DictionaryType, isAdmin: Boolean): List<DictionaryEntry> {
        val rows = entries.findByType(type)
        return if (type == DictionaryType.PRODUCT_AVAILABILITY && !isAdmin) {
            rows.filter { it.value == "PURCHASABLE" || it.value == "PREORDER" }
        } else rows
    }
}
