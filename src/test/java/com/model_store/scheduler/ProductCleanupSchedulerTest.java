package com.model_store.scheduler;

import com.model_store.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.Mockito.*;

class ProductCleanupSchedulerTest {

    @Test
    void removesReferencesForWholeBatchBeforeDeletedProducts() {
        ProductRepository repository = mock(ProductRepository.class);
        TransactionalOperator operator = mock(TransactionalOperator.class);
        when(repository.findDeletedWithoutImages(200)).thenReturn(Flux.just(7L, 8L));
        when(repository.deleteFavoritesByProductIds(any(Long[].class))).thenReturn(Mono.just(1));
        when(repository.deleteBasketByProductIds(any(Long[].class))).thenReturn(Mono.just(1));
        when(repository.deleteCartByProductIds(any(Long[].class))).thenReturn(Mono.just(1));
        when(repository.deleteDeletedWithoutImages(any(Long[].class))).thenReturn(Mono.just(2));
        when(operator.transactional(any(Mono.class))).thenAnswer(invocation -> invocation.getArgument(0));

        StepVerifier.create(new ProductCleanupScheduler(repository, operator).cleanup()).verifyComplete();

        var ordered = inOrder(repository);
        ordered.verify(repository).deleteFavoritesByProductIds(aryEq(new Long[]{7L, 8L}));
        ordered.verify(repository).deleteBasketByProductIds(aryEq(new Long[]{7L, 8L}));
        ordered.verify(repository).deleteCartByProductIds(aryEq(new Long[]{7L, 8L}));
        ordered.verify(repository).deleteDeletedWithoutImages(aryEq(new Long[]{7L, 8L}));
    }

    @Test
    void continuesWithNextBatchWhenFirstBatchIsFull() {
        ProductRepository repository = mock(ProductRepository.class);
        TransactionalOperator operator = mock(TransactionalOperator.class);
        when(repository.findDeletedWithoutImages(200)).thenReturn(
                Flux.range(1, 200).map(Integer::longValue), Flux.just(201L));
        when(repository.deleteFavoritesByProductIds(any(Long[].class))).thenReturn(Mono.just(0));
        when(repository.deleteBasketByProductIds(any(Long[].class))).thenReturn(Mono.just(0));
        when(repository.deleteCartByProductIds(any(Long[].class))).thenReturn(Mono.just(0));
        when(repository.deleteDeletedWithoutImages(any(Long[].class))).thenReturn(Mono.just(1));
        when(operator.transactional(any(Mono.class))).thenAnswer(invocation -> invocation.getArgument(0));

        StepVerifier.create(new ProductCleanupScheduler(repository, operator).cleanup()).verifyComplete();

        verify(repository, times(2)).findDeletedWithoutImages(200);
        verify(repository, times(2)).deleteDeletedWithoutImages(any(Long[].class));
    }
}
