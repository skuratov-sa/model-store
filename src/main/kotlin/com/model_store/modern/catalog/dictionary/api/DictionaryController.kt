package com.model_store.modern.catalog.dictionary.api

import com.model_store.modern.catalog.dictionary.application.DictionaryQuery
import com.model_store.modern.catalog.dictionary.domain.DictionaryEntry
import com.model_store.modern.catalog.dictionary.domain.DictionaryType
import com.model_store.modern.shared.domain.Actor
import org.springframework.context.annotation.Profile
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

data class DictionaryResponse(val type: DictionaryType, val value: String?, val description: String?) {
    constructor(entry: DictionaryEntry) : this(entry.type, entry.value, entry.description)
}

@RestController
@Profile("modern")
class DictionaryController(private val query: DictionaryQuery) {
    @GetMapping("/dictionary")
    fun get(@RequestParam type: DictionaryType, @AuthenticationPrincipal actor: Actor?): List<DictionaryResponse> =
        query.findByType(type, actor?.role == "ADMIN").map(::DictionaryResponse)
}
