package com.model_store.service.impl;

import com.amazonaws.services.kms.model.NotFoundException;
import com.model_store.configuration.property.ApplicationProperties;
import com.model_store.mapper.ProductMapper;
import com.model_store.model.CreateOrUpdateProductRequest;
import com.model_store.model.FindMyProductRequest;
import com.model_store.model.FindProductRequest;
import com.model_store.model.ReviewResponseDto;
import com.model_store.model.base.Product;
import com.model_store.model.base.SellerRating;
import com.model_store.model.constant.ImageStatus;
import com.model_store.model.constant.ImageTag;
import com.model_store.model.constant.ParticipantRole;
import com.model_store.model.constant.ProductAvailabilityType;
import com.model_store.model.constant.ProductStatus;
import com.model_store.model.dto.GetProductResponse;
import com.model_store.model.dto.ProductDto;
import com.model_store.repository.ProductRepository;
import com.model_store.service.CategoryService;
import com.model_store.service.ParticipantService;
import com.model_store.service.ProductService;
import com.model_store.service.ReviewService;
import com.model_store.service.SellerRatingService;
import com.model_store.service.SocialNetworksService;
import com.model_store.service.TransferService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

import static com.model_store.model.constant.ProductStatus.ACTIVE;
import static java.util.Objects.isNull;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductServiceImpl implements ProductService {
    private final ProductRepository productRepository;
    private final CategoryService categoryService;
    private final ProductMapper productMapper;
    private final ImageServiceImpl imageService;
    private final ReviewService reviewService;
    private final ApplicationProperties properties;
    private final SocialNetworksService socialNetworksService;
    private final TransferService transferService;
    private final SellerRatingService sellerRatingService;
    private final ParticipantService participantService;

    @Transactional
    public Mono<GetProductResponse> getProductById(Long productId) {
        Mono<List<Long>> imageFindMono = imageService.findActualImages(productId, ImageTag.PRODUCT).collectList().defaultIfEmpty(List.of());
        Mono<List<ReviewResponseDto>> findReviewsMono = reviewService.findByProductId(productId).collectList().defaultIfEmpty(List.of());

        Mono<Product> productFindMono = productRepository.findActualProduct(productId);
        Mono<Long> participantIdMono = productFindMono.map(Product::getParticipantId);
        Mono<String> loginMono = participantIdMono.flatMap(participantService::findLoginById).defaultIfEmpty("unknown");
        Mono<Float> ratingMono = participantIdMono.flatMap(sellerRatingService::findBySellarId).map(SellerRating::getAverageRating).defaultIfEmpty(0f);

        return Mono.zip(productFindMono, imageFindMono, findReviewsMono, loginMono, ratingMono)
                .flatMap(tuple5 ->
                        categoryService.findByProductId(tuple5.getT1().getId()).collectList()
                                .map(categories ->
                                        productMapper.toGetProductResponse(
                                                tuple5.getT1(),
                                                categories,
                                                tuple5.getT2(),
                                                tuple5.getT3(),
                                                tuple5.getT4(),
                                                tuple5.getT5()
                                        )
                                )
                );
    }

    @Override
    public Mono<Product> findById(Long productId) {
        return productRepository.findById(productId);
    }

    @Override
    public Mono<ProductDto> shortInfoById(Long productId) {
        return findById(productId)
                .flatMap(product -> categoryService.findByProductId(product.getId()).collectList()
                        .zipWith(imageService.findMainImage(product.getId(), ImageTag.PRODUCT).defaultIfEmpty(-1L))
                        .map(tuple2 ->
                                productMapper.toProductDto(product, tuple2.getT1(), tuple2.getT2() == -1L ? null : tuple2.getT2())
                        )
                );
    }

    @Override
    public Flux<String> findNamesBySearch(String search) {
        return productRepository.findNamesBySearch(search);
    }

    public Flux<ProductDto> findByParams(FindProductRequest searchParams) {
        return productRepository.findByParams(searchParams, null)
                .concatMap(product -> categoryService.findByProductId(product.getId()).collectList()
                        .zipWith(imageService.findMainImage(product.getId(), ImageTag.PRODUCT).defaultIfEmpty(-1L))
                        .map(tuple -> productMapper.toProductDto(product, tuple.getT1(), tuple.getT2() == -1L ? null : tuple.getT2()))
                );
    }

    public Flux<Long> findExpiredActiveProductIds() {
        return productRepository.findExpiredActiveProductIds();
    }

    @Override
    public Flux<ProductDto> findMyByParams(FindMyProductRequest searchParams, Long participantId) {
        return productRepository.findMyByParams(searchParams, participantId)
                .concatMap(product -> categoryService.findByProductId(product.getId()).collectList()
                        .zipWith(imageService.findMainImage(product.getId(), ImageTag.PRODUCT).defaultIfEmpty(-1L))
                        .map(tuple -> productMapper.toProductDto(product, tuple.getT1(), tuple.getT2() == -1L ? null : tuple.getT2()))
                );
    }

    @Override
    public Mono<Product> findActualProduct(Long productId) {
        return productRepository.findActualProduct(productId);
    }

    @Transactional
    public Mono<Long> createProduct(CreateOrUpdateProductRequest request, Long participantId, ParticipantRole role) {
        return Mono.zip(
                transferService.findByParticipantId(participantId).hasElements(),
                socialNetworksService.findByParticipantId(participantId).hasElements()
        ).flatMap(tuple -> createProduct(request, participantId));
    }

    private Mono<Long> createProduct(CreateOrUpdateProductRequest request, Long participantId) {
        log.info("Create product request: {}, participantId: {}", request, participantId);
        Product product = productMapper.toProduct(request, participantId, ACTIVE, getExpirationDate());

        if (request.getAvailability() != ProductAvailabilityType.PURCHASABLE) product.setCount(null);

        return productRepository.save(product)
                .flatMap(savedProduct ->
                        Mono.when(
                                updateImagesStatus(request.getImageIds(), savedProduct.getId()),
                                addLinkProductAndCategories(request.getCategoryIds(), savedProduct.getId())
                        ).thenReturn(savedProduct.getId())
                );
    }

    @Transactional
    public Mono<Void> updateProduct(Long id, CreateOrUpdateProductRequest request, Long participantId) {
        log.info("Update product request: {}, participantId: {}", request, participantId);

        return productRepository.findActualProduct(id)
                .doOnNext(product -> log.debug("Product found : {}", product))
                .filter(product -> Objects.equals(product.getParticipantId(), participantId))
                .switchIfEmpty(Mono.error(new NotFoundException("Product not found")))
                .map(product -> productMapper.updateProduct(request, product))
                .flatMap(productRepository::save)
                .flatMap(p -> updateImagesStatus(request.getImageIds(), id))
                .then();
    }

    public Mono<Void> deleteProduct(Long id, Long participantId) {
        log.info("Delete product id: {}, participantId: {}", id, participantId);
        return productRepository.findActualProduct(id)
                .filter(product -> Objects.equals(product.getParticipantId(), participantId))
                .switchIfEmpty(Mono.error(new NotFoundException("Product not found")))
                .flatMap(product -> {
                    product.setStatus(ProductStatus.DELETED);
                    return productRepository.save(product)
                            .then(imageService.deleteImagesByEntityId(product.getParticipantId(), ImageTag.PRODUCT));
                });
    }

    @Override
    public Mono<Void> updateProductStatus(Long id, ProductStatus status) {
        log.info("Update product id: {}, status: {}", id, status);
        return productRepository.findActualProduct(id)
                .switchIfEmpty(Mono.error(new NotFoundException("Product not found")))
                .flatMap(product -> {
                    product.setStatus(status);
                    return productRepository.save(product).then();
                });
    }

    @Override
    public Mono<Long> save(Product product) {
        return productRepository.save(product).map(Product::getId);
    }

    @Override
    public Mono<Void> extendExpirationDate(Long id, Long participantId) {
        log.info("Extend expiration date id: {}, participantId: {}", id, participantId);
        return productRepository.findActualProduct(id)
                .filter(product -> Objects.equals(product.getParticipantId(), participantId))
                .switchIfEmpty(Mono.error(new NotFoundException("Product not found")))
                .flatMap(product -> {
                    product.setExpirationDate(getExpirationDate());
                    return productRepository.save(product);
                }).then();

    }

    private Mono<Void> updateImagesStatus(List<Long> imageIds, Long productId) {
        log.debug("Update image status in [PRODUCT ACTIVE] : imageIds: {}, productId: {}", imageIds, productId);
        if (isNull(imageIds) || imageIds.isEmpty()) {
            return Mono.empty();
        }
        return imageService.updateImagesStatus(imageIds, productId, ImageStatus.ACTIVE, ImageTag.PRODUCT);
    }

    private Mono<Void> addLinkProductAndCategories(List<Long> categoryIds, Long productId) {
        log.debug("Add a product and category link categoryIds: {}, productId: {}", categoryIds, productId);

        if (isNull(productId) || categoryIds.isEmpty()) {
            return Mono.empty();
        }
        return categoryService.addLinkProductAndCategories(categoryIds, productId);
    }

    private Instant getExpirationDate() {
        return Instant.now().plus(properties.getProductExpirationDays(), ChronoUnit.DAYS);
    }
}