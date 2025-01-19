package com.model_store.repository;

import com.model_store.model.base.Dictionary;
import com.model_store.model.constant.DictionaryType;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;

@Repository
public interface DictionaryRepository extends ReactiveCrudRepository<Dictionary, String> {
    @Query("SELECT * FROM dictionary WHERE type = :type::dictionary_type")
    Flux<Dictionary> findAllByType(DictionaryType type);
}