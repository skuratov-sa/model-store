package com.model_store.bridge.dictionary

import com.model_store.model.base.Dictionary
import com.model_store.model.constant.DictionaryType
import com.model_store.modern.catalog.dictionary.application.DictionaryQuery
import com.model_store.modern.catalog.dictionary.domain.DictionaryType as ModernDictionaryType
import com.model_store.modern.shared.config.MigrationScenarioProperties
import com.model_store.service.DictionaryService
import com.model_store.service.impl.DictionaryServiceImpl
import org.springframework.context.annotation.Primary
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service
import reactor.core.publisher.Flux
import reactor.core.scheduler.Schedulers

/** DictionaryService has one production consumer: DictionaryController. */
@Service
@Primary
@Profile("!modern")
class DictionaryServiceBridge(
    private val legacy: DictionaryServiceImpl,
    private val modern: DictionaryQuery,
    private val scenarios: MigrationScenarioProperties,
) : DictionaryService {
    override fun findAllByType(type: DictionaryType): Flux<Dictionary> {
        if (scenarios.implementationFor("dictionary") != MigrationScenarioProperties.Implementation.MODERN) {
            return legacy.findAllByType(type)
        }
        // The controller applies the existing admin-only availability filter after this call.
        // Query's transaction opens and closes on the boundedElastic worker.
        return Flux.defer {
            Flux.fromIterable(modern.findByType(ModernDictionaryType.valueOf(type.name), true).map { entry ->
                dictionaryConstructor.newInstance(type, entry.value, entry.description)
            })
        }.subscribeOn(Schedulers.boundedElastic())
    }

    private companion object {
        // Lombok's all-args constructor is produced after Kotlin compilation in this module.
        val dictionaryConstructor by lazy {
            Dictionary::class.java.getDeclaredConstructor(DictionaryType::class.java, String::class.java, String::class.java)
        }
    }
}
