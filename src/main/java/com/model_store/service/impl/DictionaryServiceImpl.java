package com.model_store.service.impl;

import com.model_store.model.base.Dictionary;
import com.model_store.model.constant.DictionaryType;
import com.model_store.repository.DictionaryRepository;
import com.model_store.service.DictionaryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Service
@RequiredArgsConstructor
public class DictionaryServiceImpl implements DictionaryService {
    private final DictionaryRepository dictionaryRepository;

    @Override
    public Flux<Dictionary> findAllByType(DictionaryType type) {
        return dictionaryRepository.findAllByType(type);
    }
}