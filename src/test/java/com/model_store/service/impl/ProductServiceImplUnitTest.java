package com.model_store.service.impl;

import com.model_store.configuration.property.ApplicationProperties;
import com.model_store.exception.ApiException;
import com.model_store.exception.constant.ErrorCode;
import com.model_store.mapper.ProductMapper;
import com.model_store.model.CreateAgentProductRequest;
import com.model_store.model.CreateOrUpdateProductRequest;
import com.model_store.model.base.Product;
import com.model_store.model.base.Participant;
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
        when(participantRepository.findByIdAndIsAgentTrue(anyLong())).thenReturn(Mono.empty());
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
        req.setAvailability(ProductAvailabilityType.EXTERNAL_PRODUCT);
        req.setExternalUrl("https://t.me/source");
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

    @Test
    void createAgentProduct_withoutUrl_isRejectedBeforeSave() {
        CreateAgentProductRequest request = new CreateAgentProductRequest();
        request.setExternalUrl("   ");

        StepVerifier.create(productService.createAgentProduct(request, 7L))
                .expectErrorMatches(e -> e instanceof ApiException
                        && ((ApiException) e).getCode() == ErrorCode.INVALID_REQUEST)
                .verify();

        verify(productRepository, never()).save(any());
    }

    @Test
    void createAgentProduct_preservesUrlExactly() {
        Participant bot = new Participant();
        bot.setId(7L);
        bot.setIsAgent(true);
        bot.setStatus(com.model_store.model.constant.ParticipantStatus.ACTIVE);
        when(participantRepository.findByIdAndIsAgentTrue(7L)).thenReturn(Mono.just(bot));
        Product saved = Product.builder().id(5L).build();
        when(productRepository.save(any())).thenReturn(Mono.just(saved));

        CreateAgentProductRequest request = new CreateAgentProductRequest();
        request.setExternalUrl("https://t.me/example?start=42");
        StepVerifier.create(productService.createAgentProduct(request, 7L))
                .expectNext(5L)
                .verifyComplete();

        verify(productRepository).save(argThat(p -> request.getExternalUrl().equals(p.getExternalUrl())));
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

    @Test
    void updateAgentProduct_blockedProduct_canBeModeratedByAdmin() {
        com.model_store.model.base.Participant agent = new com.model_store.model.base.Participant();
        agent.setId(7L);
        agent.setIsAgent(true);
        Product product = Product.builder().id(5L).participantId(7L)
                .status(ProductStatus.BLOCKED).price(100f)
                .availability(ProductAvailabilityType.EXTERNAL_PRODUCT)
                .externalUrl("https://t.me/source").build();
        when(participantRepository.findByIdAndIsAgentTrue(7L)).thenReturn(Mono.just(agent));
        when(productRepository.findById(5L)).thenReturn(Mono.just(product));
        when(productMapper.updateProduct(any(), any())).thenReturn(product);
        when(productRepository.save(product)).thenReturn(Mono.just(product));

        CreateOrUpdateProductRequest request = validRequest();
        request.setAvailability(ProductAvailabilityType.EXTERNAL_PRODUCT);
        request.setCategoryIds(null);
        StepVerifier.create(productService.updateAgentProduct(5L, request, 7L))
                .verifyComplete();
        verify(productRepository).findById(5L);
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
        when(productRepository.findProductForExtend(1L)).thenReturn(Mono.just(product));

        StepVerifier.create(productService.extendExpirationDate(1L, 1L))
                .expectErrorMatches(e -> e instanceof ApiException
                        && ((ApiException) e).getCode() == ErrorCode.PRODUCT_NOT_FOUND)
                .verify();
    }

    @Test
    void extendExpirationDate_blockedOrDeletedProduct_returnsNotFoundError() {
        when(productRepository.findProductForExtend(1L)).thenReturn(Mono.empty());

        StepVerifier.create(productService.extendExpirationDate(1L, 1L))
                .expectErrorMatches(e -> e instanceof ApiException
                        && ((ApiException) e).getCode() == ErrorCode.PRODUCT_NOT_FOUND)
                .verify();
    }

    @Test
    void extendExpirationDate_reactivatesExpiredProduct() {
        Product product = Product.builder().id(5L).participantId(7L)
                .status(ProductStatus.TIME_EXPIRED).build();
        when(productRepository.findProductForExtend(5L)).thenReturn(Mono.just(product));
        when(productRepository.save(any())).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(productService.extendExpirationDate(5L, 7L)).verifyComplete();

        verify(productRepository).save(argThat(p -> p.getStatus() == ProductStatus.ACTIVE
                && p.getExpirationDate() != null && p.getExpirationDate().isAfter(java.time.Instant.now())));
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
