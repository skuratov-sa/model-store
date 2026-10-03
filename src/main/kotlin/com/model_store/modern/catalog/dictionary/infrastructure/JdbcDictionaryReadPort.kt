package com.model_store.modern.catalog.dictionary.infrastructure

import com.model_store.modern.catalog.dictionary.application.DictionaryReadPort
import com.model_store.modern.catalog.dictionary.domain.DictionaryEntry
import com.model_store.modern.catalog.dictionary.domain.DictionaryType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class JdbcDictionaryReadPort(private val jdbc: JdbcTemplate) : DictionaryReadPort {
    override fun findByType(type: DictionaryType): List<DictionaryEntry> = jdbc.query(
        "select type::text as type, value, description from dictionary where type = ?::dictionary_type",
        { rs, _ ->
            DictionaryEntry(DictionaryType.valueOf(rs.getString("type")), rs.getString("value"), rs.getString("description"))
        },
        type.name,
    )
}
