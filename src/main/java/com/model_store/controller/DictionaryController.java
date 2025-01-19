package com.model_store.controller;

import com.model_store.model.base.Dictionary;
import com.model_store.model.constant.DictionaryType;
import com.model_store.service.DictionaryService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequiredArgsConstructor
public class DictionaryController {
    private final DictionaryService dictionaryService;

    @Operation(summary = "Получить информацию из словаря")
    @GetMapping(path = "/dictionary")
    public Flux<Dictionary> getProduct(@RequestParam DictionaryType type) {
        return dictionaryService.findAllByType(type);
    }
}