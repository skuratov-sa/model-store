package com.model_store.service.impl;

import com.model_store.configuration.property.ApplicationProperties;
import com.model_store.exception.ApiException;
import com.model_store.exception.constant.ErrorCode;
import com.model_store.mapper.ProductMapper;
import com.model_store.model.CreateOrUpdateProductRequest;
import com.model_store.model.base.Product;
import com.model_store.model.base.SocialNetwork;
import com.model_store.model.base.Transfer;
import com.model_store.model.constant.ImageTag;
import com.model_store.model.constant.ParticipantRole;
import com.model_store.model.constant.ProductAvailabilityType;
import com.model_store.model.constant.ProductStatus;
import com.model_store.repository.ImageRepository;
import com.model_store.repository.ParticipantRepository;
import com.model_store.repository.ProductCategoryRepository;
import com.model_store.repository.ProductRepository;
import com.model_store.repository.SellerRatingRepository;
import com.model_store.service.CategoryService;
import com.model_store.service.ImageService;
import com.model_store.service.ParticipantService;
import com.model_store.service.ReviewService;
import com.model_store.service.SellerRatingService;
import com.model_store.service.SocialNetworksService;
import com.model_store.service.TransferService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProductServiceImplUnitTest {

    @Mock ProductRepository productRepository;
    @Mock CategoryService categoryService;
    @Mock ProductMapper productMapper;
    @Mock ImageService imageService;
    @Mock ReviewService reviewService;
    @Mock ApplicationProperties properties;
    @Mock SocialNetworksService socialNetworksService;
    @Mock TransferService transferService;
    @Mock SellerRatingService sellerRatingService;
    @Mock ParticipantService participantService;
    @Mock ProductCategoryRepository productCategoryRepository;
    @Mock ImageRepository imageRepository;
    @Mock ParticipantRepository participantRepository;
    @Mock SellerRatingRepository sellerRatingRepository;

    ProductServiceImpl productService;

    @BeforeEach
    void setUp() {
        productService = new ProductServiceImpl(
                productRepository, categoryService, productMapper,
                imageService, reviewService, properties,
                socialNetworksService, transferService, sellerRatingService, participantService,
                productCategoryRepository, imageRepository, participantRepository, sellerRatingRepository
        );
        when(properties.getProductExpirationDays()).thenReturn(30);
    }

    // --- createProduct: pre-checks ---

    @Test
    void createProduct_noTransfer_returnsTransferNotFoundError() {
        when(transferService.findByParticipantId(1L)).thenReturn(Flux.empty());
        when(socialNetworksService.findByParticipantId(1L)).thenReturn(Flux.just(new SocialNetwork()));

        StepVerifier.create(productService.createProduct(validRequest(), 1L, ParticipantRole.USER))
                .expectErrorMatches(e -> e instanceof ApiException
                        && ((ApiException) e).getCode() == ErrorCode.TRANSFER_NOT_FOUND)
                .verify();
    }

    @Test
    void createProduct_noSocialNetwork_returnsSocialNetworkNotFoundError() {
        when(transferService.findByParticipantId(1L)).thenReturn(Flux.just(new Transfer()));
        when(socialNetworksService.findByParticipantId(1L)).thenReturn(Flux.empty());

        StepVerifier.create(productService.createProduct(validRequest(), 1L, ParticipantRole.USER))
                .expectErrorMatches(e -> e instanceof ApiException
                        && ((ApiException) e).getCode() == ErrorCode.SOCIAL_NETWORK_NOT_FOUND)
                .verify();
    }

    @Test
    void createProduct_preorderWithNullPrepayment_returnsInvalidRequestError() {
        when(transferService.findByParticipantId(1L)).thenReturn(Flux.just(new Transfer()));
        when(socialNetworksService.findByParticipantId(1L)).thenReturn(Flux.just(new SocialNetwork()));

        CreateOrUpdateProductRequest req = validRequest();
        req.setAvailability(ProductAvailabilityType.PREORDER);
        req.setPrepaymentAmount(null);

        StepVerifier.create(productService.createProduct(req, 1L, ParticipantRole.USER))
                .expectErrorMatches(e -> e instanceof ApiException
                        && ((ApiException) e).getCode() == ErrorCode.INVALID_REQUEST)
                .verify();
    }

    @Test
    void createProduct_preorderWithZeroPrepayment_returnsInvalidRequestError() {
        when(transferService.findByParticipantId(1L)).thenReturn(Flux.just(new Transfer()));
        when(socialNetworksService.findByParticipantId(1L)).thenReturn(Flux.just(new SocialNetwork()));

        CreateOrUpdateProductRequest req = validRequest();
        req.setAvailability(ProductAvailabilityType.PREORDER);
        req.setPrepaymentAmount(0f);

        StepVerifier.create(productService.createProduct(req, 1L, ParticipantRole.USER))
                .expectErrorMatches(e -> e instanceof ApiException
                        && ((ApiException) e).getCode() == ErrorCode.INVALID_REQUEST)
                .verify();
    }

    @Test
    void createProduct_nonPositiveCount_returnsInvalidRequestError() {
        when(transferService.findByParticipantId(1L)).thenReturn(Flux.just(new Transfer()));
        when(socialNetworksService.findByParticipantId(1L)).thenReturn(Flux.just(new SocialNetwork()));

        CreateOrUpdateProductRequest req = validRequest();
        req.setCount(0);

        StepVerifier.create(productService.createProduct(req, 1L, ParticipantRole.USER))
                .expectErrorMatches(e -> e instanceof ApiException
                        && ((ApiException) e).getCode() == ErrorCode.INVALID_REQUEST)
                .verify();
    }

    @Test
    void createProduct_externalOnly_nullifiesCountBeforeSave() {
        when(transferService.findByParticipantId(1L)).thenReturn(Flux.just(new Transfer()));
        when(socialNetworksService.findByParticipantId(1L)).thenReturn(Flux.just(new SocialNetwork()));

        CreateOrUpdateProductRequest req = validRequest();
        req.setAvailability(ProductAvailabilityType.EXTERNAL_ONLY);
        req.setCount(5);

        Product mappedProduct = Product.builder().id(null).count(5).build();
        when(productMapper.toProduct(any(), anyLong(), any(), any())).thenReturn(mappedProduct);

        Product savedProduct = Product.builder().id(1L).count(null).build();
        when(productRepository.save(any())).thenReturn(Mono.just(savedProduct));

        StepVerifier.create(productService.createProduct(req, 1L, ParticipantRole.USER))
                .expectNext(1L)
                .verifyComplete();

        verify(productRepository).save(argThat(p -> p.getCount() == null));
    }

    // --- updateProduct ---

    @Test
    void updateProduct_productBelongsToOtherParticipant_returnsNotFoundError() {
        Product product = Product.builder().id(1L).participantId(99L).status(ProductStatus.ACTIVE).build();
        when(productRepository.findActualProduct(1L)).thenReturn(Mono.just(product));

        StepVerifier.create(productService.updateProduct(1L, validRequest(), 1L))
                .expectErrorMatches(e -> e instanceof ApiException
                        && ((ApiException) e).getCode() == ErrorCode.PRODUCT_NOT_FOUND)
                .verify();
    }

    // --- deleteProduct ---

    @Test
    void deleteProduct_productBelongsToOtherParticipant_returnsNotFoundError() {
        Product product = Product.builder().id(1L).participantId(99L).status(ProductStatus.ACTIVE).build();
        when(productRepository.findActualProduct(1L)).thenReturn(Mono.just(product));

        StepVerifier.create(productService.deleteProduct(1L, 1L))
                .expectErrorMatches(e -> e instanceof ApiException
                        && ((ApiException) e).getCode() == ErrorCode.PRODUCT_NOT_FOUND)
                .verify();
    }

    @Test
    void deleteProduct_usesProductIdForImageDeletion_notParticipantId() {
        Product product = Product.builder().id(42L).participantId(1L).status(ProductStatus.ACTIVE).build();
        when(productRepository.findActualProduct(42L)).thenReturn(Mono.just(product));
        when(productRepository.save(any())).thenReturn(Mono.just(product));
        when(imageService.deleteImagesByEntityId(42L, ImageTag.PRODUCT)).thenReturn(Mono.empty());

        StepVerifier.create(productService.deleteProduct(42L, 1L))
                .verifyComplete();

        verify(imageService).deleteImagesByEntityId(eq(42L), eq(ImageTag.PRODUCT));
        verify(imageService, never()).deleteImagesByEntityId(eq(1L), any());
    }

    // --- decrementCountIfSufficient ---

    @Test
    void decrementCountIfSufficient_sufficientStock_completesEmpty() {
        when(productRepository.decrementCountIfSufficient(1L, 2)).thenReturn(Mono.just(1));

        StepVerifier.create(productService.decrementCountIfSufficient(1L, 2))
                .verifyComplete();
    }

    @Test
    void decrementCountIfSufficient_insufficientStock_returnsOutOfStockError() {
        when(productRepository.decrementCountIfSufficient(1L, 2)).thenReturn(Mono.just(0));

        StepVerifier.create(productService.decrementCountIfSufficient(1L, 2))
                .expectErrorMatches(e -> e instanceof ApiException
                        && ((ApiException) e).getCode() == ErrorCode.OUT_OF_STOCK)
                .verify();
    }

    // --- extendExpirationDate ---

    @Test
    void extendExpirationDate_productBelongsToOtherParticipant_returnsNotFoundError() {
        Product product = Product.builder().id(1L).participantId(99L).status(ProductStatus.ACTIVE).build();
        when(productRepository.findActualProduct(1L)).thenReturn(Mono.just(product));

        StepVerifier.create(productService.extendExpirationDate(1L, 1L))
                .expectErrorMatches(e -> e instanceof ApiException
                        && ((ApiException) e).getCode() == ErrorCode.PRODUCT_NOT_FOUND)
                .verify();
    }

    // --- helpers ---

    private CreateOrUpdateProductRequest validRequest() {
        CreateOrUpdateProductRequest req = new CreateOrUpdateProductRequest();
        req.setName("Test Product");
        req.setPrice(100f);
        req.setAvailability(ProductAvailabilityType.PURCHASABLE);
        req.setCount(5);
        req.setCategoryIds(List.of());
        req.setImageIds(List.of());
        return req;
    }
}
