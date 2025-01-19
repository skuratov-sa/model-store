package com.model_store.service;

import com.model_store.model.base.Dictionary;
import com.model_store.model.constant.DictionaryType;
import reactor.core.publisher.Flux;

public interface DictionaryService {
    Flux<Dictionary> findAllByType(DictionaryType type);
}