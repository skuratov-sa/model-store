package com.model_store.service.impl;

import com.amazonaws.services.kms.model.NotFoundException;
import com.model_store.model.FindProductRequest;
import com.model_store.model.base.Participant;
import com.model_store.model.base.Product;
import com.model_store.model.base.ProductFavorite;
import com.model_store.model.constant.SortByType;
import com.model_store.model.dto.FullParticipantDto;
import com.model_store.model.page.Pageable;
import com.model_store.model.page.PagedResult;
import com.model_store.repository.ProductFavoriteRepository;
import com.model_store.repository.ProductRepository;
import com.model_store.service.ParticipantService;
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
class FavoriteServiceImplTest {

    @Mock
    private ProductFavoriteRepository productFavoriteRepository;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private ParticipantService participantService;

    @InjectMocks
    private FavoriteServiceImpl favoriteService;

    private Product product;
    private ProductFavorite productFavorite;
    private FindProductRequest findProductRequest;
    private Participant participant;
    private FullParticipantDto fullParticipantDto;

    @BeforeEach
    void setUp() {
        product = new Product();
        product.setId(1L);
        product.setName("Test Product");

        productFavorite = new ProductFavorite();
        productFavorite.setParticipantId(1L);
        productFavorite.setProductId(1L);

        findProductRequest = new FindProductRequest();
        findProductRequest.setPageable(new Pageable(0, Instant.now(), 77F, 11L, SortByType.PRICE_ASC));

        participant = new Participant();
        participant.setId(1L);
    }

    @Test
    void findFavoriteByParams_shouldReturnPagedProducts_whenFavoritesExist() {
        Long participantId = 1L;
        List<Product> products = Collections.singletonList(product);
        PagedResult<Product> expectedResult = new PagedResult<>(products, 1, findProductRequest.getPageable());

        when(productFavoriteRepository.findByParticipantId(participantId)).thenReturn(Flux.just(productFavorite));
        when(productRepository.findByParams(eq(findProductRequest), any(Long[].class))).thenReturn(Flux.fromIterable(products));
        when(productRepository.findCountBySearchParams(eq(findProductRequest), eq(null))).thenReturn(Mono.just(1));


        StepVerifier.create(favoriteService.findFavoriteByParams(participantId, findProductRequest))
                .expectNext(expectedResult)
                .verifyComplete();

        verify(productFavoriteRepository).findByParticipantId(participantId);
        verify(productRepository).findByParams(eq(findProductRequest), any(Long[].class));
        verify(productRepository).findCountBySearchParams(eq(findProductRequest), eq(null));
    }

    @Test
    void findFavoriteByParams_shouldReturnEmptyPagedResult_whenNoFavoritesExist() {
        Long participantId = 1L;
        PagedResult<Product> expectedResult = new PagedResult<>(Collections.emptyList(), 0, findProductRequest.getPageable());

        when(productFavoriteRepository.findByParticipantId(participantId)).thenReturn(Flux.empty());
        // Ensure findByParams is called with an empty array for productIds
        when(productRepository.findByParams(eq(findProductRequest), eq(new Long[]{}))).thenReturn(Flux.empty());
        when(productRepository.findCountBySearchParams(eq(findProductRequest), eq(null))).thenReturn(Mono.just(0));


        StepVerifier.create(favoriteService.findFavoriteByParams(participantId, findProductRequest))
                .expectNext(expectedResult)
                .verifyComplete();

        verify(productFavoriteRepository).findByParticipantId(participantId);
        verify(productRepository).findByParams(eq(findProductRequest), eq(new Long[]{}));
        verify(productRepository).findCountBySearchParams(eq(findProductRequest), eq(null));
    }

    @Test
    void addToFavorites_shouldAddProductToFavorites_whenProductAndParticipantExistAndProductNotInFavorites() {
        Long participantId = 1L;
        Long productId = 1L;

        when(productRepository.findActualProduct(productId)).thenReturn(Mono.just(product));
        when(participantService.findActualById(participantId)).thenReturn(Mono.just(fullParticipantDto));
        when(productFavoriteRepository.findByParticipantIdAndProductId(participantId, productId)).thenReturn(Flux.empty());
        when(productFavoriteRepository.save(any(ProductFavorite.class))).thenReturn(Mono.just(productFavorite));

        StepVerifier.create(favoriteService.addToFavorites(participantId, productId))
                .verifyComplete();

        verify(productRepository).findActualProduct(productId);
        verify(participantService).findActualById(participantId);
        verify(productFavoriteRepository).findByParticipantIdAndProductId(participantId, productId);
        verify(productFavoriteRepository).save(any(ProductFavorite.class));
    }

    @Test
    void addToFavorites_shouldNotAddProduct_whenProductAlreadyInFavorites() {
        Long participantId = 1L;
        Long productId = 1L;

        when(productRepository.findActualProduct(productId)).thenReturn(Mono.just(product));
        when(participantService.findActualById(participantId)).thenReturn(Mono.just(fullParticipantDto));
        when(productFavoriteRepository.findByParticipantIdAndProductId(participantId, productId)).thenReturn(Flux.just(productFavorite));
        // No call to save should happen

        StepVerifier.create(favoriteService.addToFavorites(participantId, productId))
                .verifyComplete();

        verify(productRepository).findActualProduct(productId);
        verify(participantService).findActualById(participantId);
        verify(productFavoriteRepository).findByParticipantIdAndProductId(participantId, productId);
        // verify(productFavoriteRepository, never()).save(any(ProductFavorite.class)); // This is tricky with switchIfEmpty
    }

    @Test
    void addToFavorites_shouldFail_whenProductDoesNotExist() {
        Long participantId = 1L;
        Long productId = 1L;

        when(productRepository.findActualProduct(productId)).thenReturn(Mono.empty());
        when(participantService.findActualById(participantId)).thenReturn(Mono.just(fullParticipantDto)); // Assuming participant exists

        StepVerifier.create(favoriteService.addToFavorites(participantId, productId))
                .expectError(NotFoundException.class)
                .verify();

        verify(productRepository).findActualProduct(productId);
        verify(participantService).findActualById(participantId);
    }

    @Test
    void addToFavorites_shouldFail_whenParticipantDoesNotExist() {
        Long participantId = 1L;
        Long productId = 1L;

        when(productRepository.findActualProduct(productId)).thenReturn(Mono.just(product)); // Assuming product exists
        when(participantService.findActualById(participantId)).thenReturn(Mono.empty());

        StepVerifier.create(favoriteService.addToFavorites(participantId, productId))
                .expectError(NotFoundException.class)
                .verify();

        verify(productRepository).findActualProduct(productId);
        verify(participantService).findActualById(participantId);
    }

    @Test
    void removeFromFavorites_shouldRemoveProductFromFavorites() {
        Long participantId = 1L;
        Long productId = 1L;

        when(productFavoriteRepository.deleteByParticipantIdAndProductId(participantId, productId)).thenReturn(Mono.empty());

        StepVerifier.create(favoriteService.removeFromFavorites(participantId, productId))
                .verifyComplete();

        verify(productFavoriteRepository).deleteByParticipantIdAndProductId(participantId, productId);
    }

    @Test
    void removeFromFavorites_shouldComplete_evenWhenProductNotInFavorites() {
        Long participantId = 1L;
        Long productId = 1L;
        // deleteByParticipantIdAndProductId returns Mono<Void>, so it completes even if nothing is deleted.
        when(productFavoriteRepository.deleteByParticipantIdAndProductId(participantId, productId)).thenReturn(Mono.empty());

        StepVerifier.create(favoriteService.removeFromFavorites(participantId, productId))
                .verifyComplete();

        verify(productFavoriteRepository).deleteByParticipantIdAndProductId(participantId, productId);
    }
}
