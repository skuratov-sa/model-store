package com.model_store.service.impl;

import com.model_store.model.base.Dictionary;
import com.model_store.model.constant.DictionaryType;
import com.model_store.repository.DictionaryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DictionaryServiceImplTest {

    @Mock
    private DictionaryRepository dictionaryRepository;

    @InjectMocks
    private DictionaryServiceImpl dictionaryService;

    private Dictionary dictionary1;
    private Dictionary dictionary2;

    @BeforeEach
    void setUp() {
        dictionary1 = Dictionary.builder().type(DictionaryType.CURRENCY).value("USD").build();
        dictionary2 = Dictionary.builder().type(DictionaryType.SOCIAL_NETWORK).value("VK").build();
    }

    @Test
    void findAllByType_shouldReturnFluxOfDictionaries_whenTypeExists() {
        DictionaryType type = DictionaryType.CURRENCY;
        when(dictionaryRepository.findAllByType(type)).thenReturn(Flux.just(dictionary1, dictionary2));

        StepVerifier.create(dictionaryService.findAllByType(type))
                .expectNext(dictionary1)
                .expectNext(dictionary2)
                .verifyComplete();

        verify(dictionaryRepository).findAllByType(type);
    }

    @Test
    void findAllByType_shouldReturnEmptyFlux_whenTypeDoesNotExistOrNoDictionariesForType() {
        DictionaryType type = DictionaryType.SOCIAL_NETWORK; // Assuming this type might not have entries
        when(dictionaryRepository.findAllByType(type)).thenReturn(Flux.empty());

        StepVerifier.create(dictionaryService.findAllByType(type))
                .verifyComplete();

        verify(dictionaryRepository).findAllByType(type);
    }
}
