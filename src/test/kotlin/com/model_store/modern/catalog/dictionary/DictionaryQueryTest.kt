package com.model_store.modern.catalog.dictionary

import com.model_store.modern.catalog.dictionary.application.DictionaryQuery
import com.model_store.modern.catalog.dictionary.application.DictionaryReadPort
import com.model_store.modern.catalog.dictionary.api.DictionaryController
import com.model_store.modern.catalog.dictionary.domain.DictionaryEntry
import com.model_store.modern.catalog.dictionary.domain.DictionaryType
import com.model_store.modern.shared.domain.Actor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DictionaryQueryTest {
    @Test
    fun `availability exposes only purchasable and preorder to guests and users`() {
        val type = DictionaryType.PRODUCT_AVAILABILITY
        val rows = listOf("PURCHASABLE", "EXTERNAL_PRODUCT", "PREORDER", "GIVEAWAY")
            .map { DictionaryEntry(type, it, it) }
        val query = DictionaryQuery(DictionaryReadPort { rows })

        assertEquals(listOf("PURCHASABLE", "PREORDER"), query.findByType(type, false).map { it.value })
        assertEquals(rows, query.findByType(type, true))
    }

    @Test
    fun `other dictionary types retain every row in repository order`() {
        val type = DictionaryType.ORDER_STATUS
        val rows = listOf("COMPLETED", "BOOKED", "CANCELLED").map { DictionaryEntry(type, it, null) }
        val query = DictionaryQuery(DictionaryReadPort { rows })

        assertEquals(rows, query.findByType(type, false))
    }

    @Test
    fun `controller treats missing actor and user as public while admin sees restricted entries`() {
        val type = DictionaryType.PRODUCT_AVAILABILITY
        val rows = listOf("GIVEAWAY", "PREORDER", "PURCHASABLE", "EXTERNAL_PRODUCT")
            .map { DictionaryEntry(type, it, it) }
        val controller = DictionaryController(DictionaryQuery(DictionaryReadPort { rows }))

        assertEquals(listOf("PREORDER", "PURCHASABLE"), controller.get(type, null).map { it.value })
        assertEquals(listOf("PREORDER", "PURCHASABLE"),
            controller.get(type, Actor(1, "user", "USER")).map { it.value })
        assertEquals(rows.map { it.value }, controller.get(type, Actor(2, "admin", "ADMIN")).map { it.value })
    }
}
