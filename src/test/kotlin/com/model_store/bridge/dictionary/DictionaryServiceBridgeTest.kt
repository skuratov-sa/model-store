package com.model_store.bridge.dictionary

import com.model_store.model.base.Dictionary
import com.model_store.model.constant.DictionaryType
import com.model_store.modern.catalog.dictionary.application.DictionaryQuery
import com.model_store.modern.catalog.dictionary.domain.DictionaryEntry
import com.model_store.modern.catalog.dictionary.domain.DictionaryType as ModernDictionaryType
import com.model_store.modern.shared.config.MigrationScenarioProperties
import com.model_store.service.impl.DictionaryServiceImpl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.times
import reactor.core.publisher.Flux
import java.util.concurrent.atomic.AtomicReference

class DictionaryServiceBridgeTest {
    private val legacy = mock(DictionaryServiceImpl::class.java)
    private val modern = mock(DictionaryQuery::class.java)
    private val type = DictionaryType.CURRENCY

    @Test
    fun `unset and explicit legacy flags delegate without calling JDBC`() {
        val row = Dictionary::class.java.getDeclaredConstructor(DictionaryType::class.java, String::class.java, String::class.java)
            .newInstance(type, "RUB", "Рубль")
        `when`(legacy.findAllByType(type)).thenReturn(Flux.just(row))
        val default = DictionaryServiceBridge(legacy, modern, MigrationScenarioProperties())
        val explicit = DictionaryServiceBridge(legacy, modern,
            MigrationScenarioProperties(mapOf("dictionary" to MigrationScenarioProperties.Implementation.LEGACY)))

        assertEquals(listOf(row), default.findAllByType(type).collectList().block())
        assertEquals(listOf(row), explicit.findAllByType(type).collectList().block())
        verify(legacy, times(2)).findAllByType(type)
        verifyNoInteractions(modern)
    }

    @Test
    fun `modern query is deferred to boundedElastic and preserves rows`() {
        val worker = AtomicReference<String>()
        `when`(modern.findByType(ModernDictionaryType.CURRENCY, true)).thenAnswer {
            worker.set(Thread.currentThread().name)
            listOf(DictionaryEntry(ModernDictionaryType.CURRENCY, "RUB", "Рубль"))
        }
        val bridge = DictionaryServiceBridge(legacy, modern,
            MigrationScenarioProperties(mapOf("dictionary" to MigrationScenarioProperties.Implementation.MODERN)))
        val result = bridge.findAllByType(type)
        verifyNoInteractions(modern)

        assertEquals("RUB", result.single().block()!!.value)
        assertTrue(worker.get().startsWith("boundedElastic-"), worker.get())
        verify(modern).findByType(ModernDictionaryType.CURRENCY, true)
        verifyNoInteractions(legacy)
    }

    @Test
    fun `modern conversion preserves nullable dictionary columns`() {
        `when`(modern.findByType(ModernDictionaryType.CURRENCY, true)).thenReturn(
            listOf(DictionaryEntry(ModernDictionaryType.CURRENCY, null, null)))
        val bridge = DictionaryServiceBridge(legacy, modern,
            MigrationScenarioProperties(mapOf("dictionary" to MigrationScenarioProperties.Implementation.MODERN)))

        val row = bridge.findAllByType(type).single().block()!!
        assertEquals(type, row.type)
        assertEquals(null, row.value)
        assertEquals(null, row.description)
    }

    @Test
    fun `modern query failure reaches the subscriber without legacy fallback`() {
        val failure = IllegalStateException("database unavailable")
        `when`(modern.findByType(ModernDictionaryType.CURRENCY, true)).thenThrow(failure)
        val bridge = DictionaryServiceBridge(legacy, modern,
            MigrationScenarioProperties(mapOf("dictionary" to MigrationScenarioProperties.Implementation.MODERN)))
        val result = bridge.findAllByType(type)
        verifyNoInteractions(modern)

        assertEquals(failure, assertThrows(IllegalStateException::class.java) { result.collectList().block() })
        verifyNoInteractions(legacy)
    }
}
