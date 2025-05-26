package com.model_store.service.impl;

import com.model_store.exception.EntityAlreadyExistException;
import com.model_store.exception.EntityNotFoundException;
import com.model_store.mapper.ProductMapper;
import com.model_store.model.FindProductRequest;
import com.model_store.model.base.Participant;
import com.model_store.model.base.Product;
import com.model_store.model.base.ProductBasket;
import com.model_store.model.constant.ImageTag;
import com.model_store.model.constant.SortByType;
import com.model_store.model.dto.FullParticipantDto;
import com.model_store.model.dto.ProductDto;
import com.model_store.model.page.Pageable;
import com.model_store.model.page.PagedResult;
import com.model_store.repository.ProductBasketRepository;
import com.model_store.repository.ProductRepository;
import com.model_store.service.ImageService;
import com.model_store.service.ParticipantService;
import com.model_store.service.ProductService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BasketServiceImplTest {

    @Mock
    private ParticipantService participantService;

    @Mock
    private ProductService productService;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private ProductBasketRepository productBasketRepository;

    @Mock
    private ProductMapper productMapper;

    @Mock
    private ImageService imageService;

    @InjectMocks
    private BasketServiceImpl basketService;

    private Product product;
    private ProductDto productDto;
    private ProductBasket productBasket;
    private FindProductRequest findProductRequest;
    private Participant participant;
    private FullParticipantDto fullParticipantDto;

    @BeforeEach
    void setUp() {
        product = new Product();
        product.setId(1L);
        product.setName("Test Product");

        productDto = ProductDto.builder().id(1L).name("Test Product Dto").build();

        productBasket = new ProductBasket();
        productBasket.setParticipantId(1L);
        productBasket.setProductId(1L);

        findProductRequest = new FindProductRequest();
        findProductRequest.setPageable(new Pageable(0, Instant.now(), 77F, 11L, SortByType.PRICE_ASC));

        participant = new Participant();
        participant.setId(1L);
    }

    @Test
    void findBasketProductsByParams_shouldReturnPagedProducts() {
        when(productBasketRepository.findByParticipantId(1L)).thenReturn(Flux.just(productBasket));
        when(productRepository.findByParams(eq(findProductRequest), any(Long[].class))).thenReturn(Flux.just(product));
        when(imageService.findMainImage(1L, ImageTag.PRODUCT)).thenReturn(Mono.just(2L));
        when(productMapper.toProductDto(product, 2L)).thenReturn(productDto);

        PagedResult<ProductDto> expectedResult = new PagedResult<>(List.of(productDto), 1, findProductRequest.getPageable());

        StepVerifier.create(basketService.findBasketProductsByParams(1L, findProductRequest))
                .expectNext(expectedResult)
                .verifyComplete();

        verify(productBasketRepository).findByParticipantId(1L);
        verify(productRepository).findByParams(eq(findProductRequest), any(Long[].class));
        verify(imageService).findMainImage(1L, ImageTag.PRODUCT);
        verify(productMapper).toProductDto(product, 2L);
    }

    @Test
    void findBasketProductsByParams_shouldReturnEmpty_whenBasketIsEmpty() {
        when(productBasketRepository.findByParticipantId(1L)).thenReturn(Flux.empty());
        // Ensure productRepository.findByParams is called with an empty array for productIds
        when(productRepository.findByParams(eq(findProductRequest), eq(new Long[]{}))).thenReturn(Flux.empty());


        PagedResult<ProductDto> expectedResult = new PagedResult<>(Collections.emptyList(), 0, findProductRequest.getPageable());

        StepVerifier.create(basketService.findBasketProductsByParams(1L, findProductRequest))
                .expectNext(expectedResult)
                .verifyComplete();

        verify(productBasketRepository).findByParticipantId(1L);
        verify(productRepository).findByParams(eq(findProductRequest), eq(new Long[]{}));
    }

    @Test
    void addToBasket_shouldAddProductToBasket_whenProductAndParticipantExistAndProductNotInBasket() {
        when(productService.findActualProduct(1L)).thenReturn(Mono.just(product));
        when(participantService.findActualById(1L)).thenReturn(Mono.just(fullParticipantDto));
        when(productBasketRepository.findByParticipantIdAndProductId(1L, 1L)).thenReturn(Flux.empty());
        when(productBasketRepository.save(any(ProductBasket.class))).thenReturn(Mono.just(productBasket));

        StepVerifier.create(basketService.addToBasket(1L, 1L))
                .verifyComplete();

        verify(productService).findActualProduct(1L);
        verify(participantService).findActualById(1L);
        verify(productBasketRepository).findByParticipantIdAndProductId(1L, 1L);
        verify(productBasketRepository).save(any(ProductBasket.class));
    }

    @Test
    void addToBasket_shouldFail_whenProductDoesNotExist() {
        when(productService.findActualProduct(1L)).thenReturn(Mono.empty());
        when(participantService.findActualById(1L)).thenReturn(Mono.just(fullParticipantDto)); // Assuming participant exists

        StepVerifier.create(basketService.addToBasket(1L, 1L))
                .expectError(EntityNotFoundException.class)
                .verify();

        verify(productService).findActualProduct(1L);
        verify(participantService).findActualById(1L);
    }

    @Test
    void addToBasket_shouldFail_whenParticipantDoesNotExist() {
        when(productService.findActualProduct(1L)).thenReturn(Mono.just(product)); // Assuming product exists
        when(participantService.findActualById(1L)).thenReturn(Mono.empty());

        StepVerifier.create(basketService.addToBasket(1L, 1L))
                .expectError(EntityNotFoundException.class)
                .verify();

        verify(productService).findActualProduct(1L);
        verify(participantService).findActualById(1L);
    }

    @Test
    void addToBasket_shouldFail_whenProductAlreadyInBasket() {
        when(productService.findActualProduct(1L)).thenReturn(Mono.just(product));
        when(participantService.findActualById(1L)).thenReturn(Mono.just(fullParticipantDto));
        when(productBasketRepository.findByParticipantIdAndProductId(1L, 1L)).thenReturn(Flux.just(productBasket));

        StepVerifier.create(basketService.addToBasket(1L, 1L))
                .expectError(EntityAlreadyExistException.class)
                .verify();

        verify(productService).findActualProduct(1L);
        verify(participantService).findActualById(1L);
        verify(productBasketRepository).findByParticipantIdAndProductId(1L, 1L);
    }

    @Test
    void removeFromBasket_shouldRemoveProductFromBasket() {
        when(productBasketRepository.deleteByParticipantIdAndProductId(1L, 1L)).thenReturn(Mono.empty());

        StepVerifier.create(basketService.removeFromBasket(1L, 1L))
                .verifyComplete();

        verify(productBasketRepository).deleteByParticipantIdAndProductId(1L, 1L);
    }

    @Test
    void removeFromBasket_shouldComplete_evenWhenProductNotInBasket() {
        // deleteByParticipantIdAndProductId returns Mono<Void>, so it completes even if nothing is deleted.
        when(productBasketRepository.deleteByParticipantIdAndProductId(1L, 1L)).thenReturn(Mono.empty());

        StepVerifier.create(basketService.removeFromBasket(1L, 1L))
                .verifyComplete();

        verify(productBasketRepository).deleteByParticipantIdAndProductId(1L, 1L);
    }
}
