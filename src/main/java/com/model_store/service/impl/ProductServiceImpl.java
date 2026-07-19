package com.model_store.service.impl;

import com.model_store.configuration.property.ApplicationProperties;
import com.model_store.exception.ApiErrors;
import com.model_store.exception.constant.ErrorCode;
import com.model_store.mapper.ProductMapper;
import com.model_store.model.CreateAgentProductRequest;
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
import com.model_store.model.dto.CategoryDto;
import com.model_store.model.dto.GetProductResponse;
import com.model_store.model.dto.ProductDto;
import com.model_store.repository.ImageRepository;
import com.model_store.repository.ParticipantRepository;
import com.model_store.repository.ProductCategoryRepository;
import com.model_store.repository.ProductRepository;
import com.model_store.repository.SellerRatingRepository;
import com.model_store.service.CategoryService;
import com.model_store.service.ImageService;
import com.model_store.service.ParticipantService;
import com.model_store.service.ProductService;
import com.model_store.service.ReviewService;
import com.model_store.service.SellerRatingService;
import com.model_store.service.SocialNetworksService;
import com.model_store.service.TransferService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static com.model_store.model.constant.ProductStatus.ACTIVE;
import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

@Slf4j
@Service
public class ProductServiceImpl implements ProductService {
    private final ProductRepository productRepository;
    private final CategoryService categoryService;
    private final ProductMapper productMapper;
    private final ImageService imageService;
    private final ReviewService reviewService;
    private final ApplicationProperties properties;
    private final SocialNetworksService socialNetworksService;
    private final TransferService transferService;
    private final SellerRatingService sellerRatingService;
    private final ParticipantService participantService;
    private final ProductCategoryRepository productCategoryRepository;
    private final ImageRepository imageRepository;
    private final ParticipantRepository participantRepository;
    private final SellerRatingRepository sellerRatingRepository;

    @Autowired
    public ProductServiceImpl(
            ProductRepository productRepository,
            CategoryService categoryService,
            ProductMapper productMapper,
            @Lazy ImageService imageService,
            ReviewService reviewService,
            ApplicationProperties properties,
            SocialNetworksService socialNetworksService,
            TransferService transferService,
            SellerRatingService sellerRatingService,
            ParticipantService participantService,
            ProductCategoryRepository productCategoryRepository,
            ImageRepository imageRepository,
            ParticipantRepository participantRepository,
            SellerRatingRepository sellerRatingRepository
    ) {
        this.productRepository = productRepository;
        this.categoryService = categoryService;
        this.productMapper = productMapper;
        this.imageService = imageService;
        this.reviewService = reviewService;
        this.properties = properties;
        this.socialNetworksService = socialNetworksService;
        this.transferService = transferService;
        this.sellerRatingService = sellerRatingService;
        this.participantService = participantService;
        this.productCategoryRepository = productCategoryRepository;
        this.imageRepository = imageRepository;
        this.participantRepository = participantRepository;
        this.sellerRatingRepository = sellerRatingRepository;
    }

    public Mono<GetProductResponse> getProductById(Long productId) {
        Mono<List<Long>> imageFindMono = imageService.findActualImages(productId, ImageTag.PRODUCT).collectList().defaultIfEmpty(List.of());
        Mono<List<ReviewResponseDto>> findReviewsMono = reviewService.findByProductId(productId).collectList().defaultIfEmpty(List.of());

        Mono<Product> productFindMono = productRepository.findActualProduct(productId).cache();
        Mono<Long> participantIdMono = productFindMono.map(Product::getParticipantId);
        Mono<String> loginMono = participantIdMono.flatMap(participantService::findLoginById).defaultIfEmpty("unknown");
        Mono<SellerRating> ratingMono = participantIdMono.flatMap(sellerRatingService::findBySellarId).defaultIfEmpty(new SellerRating(0L,0f, 0));


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
                                                tuple5.getT5().getAverageRating(),
                                                tuple5.getT5().getTotalReviews()
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
        return findById(productId).flatMap(this::buildProductDto);
    }

    @Override
    public Flux<String> findNamesBySearch(String search) {
        return productRepository.findNamesBySearch(search);
    }

    public Flux<ProductDto> findByParams(FindProductRequest searchParams, Long currentParticipantId) {
        if (Boolean.TRUE.equals(searchParams.getIncludeAdult())) {
            return checkAdultAccess(currentParticipantId)
                    .thenMany(buildProductDtos(productRepository.findByParams(searchParams, null)));
        }
        return buildProductDtos(productRepository.findByParams(searchParams, null));
    }

    private Mono<Void> checkAdultAccess(Long participantId) {
        if (participantId == null) {
            return Mono.error(ApiErrors.forbidden(ErrorCode.ADULT_CONTENT_RESTRICTED,
                    "Для просмотра контента 18+ необходима авторизация"));
        }
        return participantService.findAgeById(participantId)
                .flatMap(age -> {
                    if (age == null || age < 18) {
                        return Mono.error(ApiErrors.forbidden(ErrorCode.ADULT_CONTENT_RESTRICTED,
                                "Контент 18+ доступен только пользователям от 18 лет"));
                    }
                    return Mono.<Void>empty();
                });
    }

    @Override
    public Flux<ProductDto> findMyByParams(FindMyProductRequest searchParams, Long participantId) {
        return buildProductDtos(productRepository.findMyByParams(searchParams, participantId));
    }

    @Override
    public Mono<ProductDto> buildProductDto(Product product) {
        if (product == null) {
            return Mono.empty();
        }
        return buildProductDtos(List.of(product)).next();
    }

    @Override
    public Flux<ProductDto> buildProductDtos(List<Product> products) {
        if (products == null || products.isEmpty()) {
            return Flux.empty();
        }

        Long[] productIds = products.stream()
                .map(Product::getId)
                .filter(Objects::nonNull)
                .distinct()
                .toArray(Long[]::new);
        Long[] participantIds = products.stream()
                .map(Product::getParticipantId)
                .filter(Objects::nonNull)
                .distinct()
                .toArray(Long[]::new);

        Mono<Map<Long, List<CategoryDto>>> categoriesByProductIdMono =
                productIds.length == 0
                        ? Mono.just(Collections.emptyMap())
                        : productCategoryRepository.findCategoryViewsByProductIds(productIds)
                        .collectMultimap(
                                view -> view.getProductId(),
                                view -> CategoryDto.builder()
                                        .id(view.getCategoryId())
                                        .name(view.getCategoryName())
                                        .build()
                        )
                        .map(grouped -> grouped.entrySet().stream()
                                .collect(Collectors.toMap(
                                        Map.Entry::getKey,
                                        entry -> List.copyOf(entry.getValue())
                                )));

        Mono<Map<Long, Long>> mainImageByProductIdMono =
                productIds.length == 0
                        ? Mono.just(Collections.emptyMap())
                        : imageRepository.findMainImageViewsByEntities(productIds, ImageTag.PRODUCT)
                        .collectMap(view -> view.getEntityId(), view -> view.getImageId());

        Mono<Map<Long, String>> loginByParticipantIdMono =
                participantIds.length == 0
                        ? Mono.just(Collections.emptyMap())
                        : participantRepository.findLoginViewsByIds(participantIds)
                        .collectMap(view -> view.getParticipantId(), view -> view.getLogin());

        Mono<Map<Long, SellerRating>> ratingByParticipantIdMono =
                participantIds.length == 0
                        ? Mono.just(Collections.emptyMap())
                        : sellerRatingRepository.findBySellerIds(participantIds)
                        .collectMap(SellerRating::getSellerId);

        return Mono.zip(categoriesByProductIdMono, mainImageByProductIdMono, loginByParticipantIdMono, ratingByParticipantIdMono)
                .flatMapMany(tuple -> Flux.fromIterable(products).map(product -> {
                    SellerRating rating = tuple.getT4().get(product.getParticipantId());
                    return productMapper.toProductDto(
                        product,
                        tuple.getT1().getOrDefault(product.getId(), List.of()),
                        tuple.getT2().get(product.getId()),
                        tuple.getT3().getOrDefault(product.getParticipantId(), "unknown"),
                        averageRating(rating),
                        totalReviews(rating)
                    );
                }));
    }

    private Flux<ProductDto> buildProductDtos(Flux<Product> products) {
        return products.collectList().flatMapMany(this::buildProductDtos);
    }

    private Float averageRating(SellerRating rating) {
        return rating == null || rating.getAverageRating() == null ? 0f : rating.getAverageRating();
    }

    private Integer totalReviews(SellerRating rating) {
        return rating == null || rating.getTotalReviews() == null ? 0 : rating.getTotalReviews();
    }

    public Flux<Long> findExpiredActiveProductIds() {
        return productRepository.findExpiredActiveProductIds();
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
        ).flatMap(tuple -> {
            if (!tuple.getT1())
                return Mono.error(ApiErrors.badRequest(ErrorCode.TRANSFER_NOT_FOUND, "Добавьте способ получения оплаты перед созданием товара"));
            if (!tuple.getT2())
                return Mono.error(ApiErrors.badRequest(ErrorCode.SOCIAL_NETWORK_NOT_FOUND, "Добавьте социальную сеть перед созданием товара"));
            return createProduct(request, participantId);
        });
    }

    private Mono<Long> createProduct(CreateOrUpdateProductRequest request, Long participantId) {
        log.info("Create product request: {}, participantId: {}", request, participantId);

        if (ProductAvailabilityType.PREORDER.equals(request.getAvailability())
                && (isNull(request.getPrepaymentAmount()) || request.getPrepaymentAmount() <= 0)) {
            return Mono.error(ApiErrors.badRequest(ErrorCode.INVALID_REQUEST, "Предоплата указана неверно"));
        }

        if (nonNull(request.getCount()) && request.getCount() <= 0) {
            return Mono.error(ApiErrors.badRequest(ErrorCode.INVALID_REQUEST, "Введено некорректное кол-во товаров"));
        }

        Product product = productMapper.toProduct(request, participantId, ACTIVE, getExpirationDate());
        if (request.getAvailability() == ProductAvailabilityType.EXTERNAL_ONLY) product.setCount(null);

        return productRepository.save(product)
                .flatMap(savedProduct ->
                        updateImagesStatus(request.getImageIds(), savedProduct.getId())
                                .then(addLinkProductAndCategories(request.getCategoryIds(), savedProduct.getId()))
                                .thenReturn(savedProduct.getId())
                );
    }

    @Override
    @Transactional
    public Mono<Long> createAgentProduct(CreateAgentProductRequest request, Long participantId) {
        log.info("Create agent product request: {}, participantId: {}", request, participantId);
        Product product = Product.builder()
                .name(request.getName())
                .description(request.getDescription())
                .price(request.getPrice())
                .currency(request.getCurrency())
                .originality(request.getOriginality())
                .externalUrl(request.getExternalUrl())
                .availability(ProductAvailabilityType.EXTERNAL_ONLY)
                .count(null)
                .participantId(participantId)
                .status(ACTIVE)
                .expirationDate(getExpirationDate())
                .build();

        return productRepository.save(product)
                .flatMap(savedProduct ->
                        updateImagesStatus(request.getImageIds(), savedProduct.getId())
                                .then(addLinkProductAndCategories(request.getCategoryIds(), savedProduct.getId()))
                                .thenReturn(savedProduct.getId())
                );
    }

    @Transactional
    public Mono<Void> updateProduct(Long id, CreateOrUpdateProductRequest request, Long participantId) {
        log.info("Update product request: {}, participantId: {}", request, participantId);

        return productRepository.findActualProduct(id)
                .doOnNext(product -> log.debug("Product found : {}", product))
                .filter(product -> Objects.equals(product.getParticipantId(), participantId))
                .switchIfEmpty(Mono.error(
                        ApiErrors.notFound(ErrorCode.PRODUCT_NOT_FOUND, "Не удалось выполнить операцию: не достаточно прав или его не существует")
                ))
                .map(product -> productMapper.updateProduct(request, product))
                .flatMap(productRepository::save)
                .flatMap(p -> updateImagesStatus(request.getImageIds(), id))
                .then();
    }

    public Mono<Void> deleteProduct(Long id, Long participantId) {
        log.info("Delete product id: {}, participantId: {}", id, participantId);
        return productRepository.findActualProduct(id)
                .filter(product -> Objects.equals(product.getParticipantId(), participantId))
                .switchIfEmpty(Mono.error(
                        ApiErrors.notFound(ErrorCode.PRODUCT_NOT_FOUND, "Не удалось обновить товар: не достаточно прав или его не существует")
                )).flatMap(product -> {
                    product.setStatus(ProductStatus.DELETED);
                    return productRepository.save(product)
                            .then(imageService.deleteImagesByEntityId(product.getId(), ImageTag.PRODUCT));
                });
    }

    @Override
    public Mono<Void> updateProductStatus(Long id, ProductStatus status) {
        log.info("Update product id: {}, status: {}", id, status);
        return productRepository.findActualProduct(id)
                .switchIfEmpty(Mono.error(
                        ApiErrors.notFound(ErrorCode.PRODUCT_NOT_FOUND, "Не удалось выполнить операцию: не достаточно прав или его не существует")
                )).flatMap(product -> {
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
        return productRepository.findProductForExtend(id)
                .filter(product -> Objects.equals(product.getParticipantId(), participantId))
                .switchIfEmpty(Mono.error(
                        ApiErrors.notFound(ErrorCode.PRODUCT_NOT_FOUND, "Не удалось выполнить операцию: не достаточно прав или его не существует")
                )).flatMap(product -> {
                    product.setExpirationDate(getExpirationDate());
                    product.setStatus(ACTIVE);
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

    @Override
    public Mono<Void> decrementCountIfSufficient(Long productId, Integer amount) {
        return productRepository.decrementCountIfSufficient(productId, amount)
                .flatMap(updated -> updated == 0
                        ? Mono.error(ApiErrors.badRequest(ErrorCode.OUT_OF_STOCK, "Недостаточно товара на складе"))
                        : Mono.empty());
    }

    @Override
    public Mono<Void> incrementCountIfLimited(Long productId, Integer amount) {
        if (isNull(amount) || amount <= 0) {
            return Mono.empty();
        }
        return productRepository.incrementCountIfLimited(productId, amount).then();
    }

    private Instant getExpirationDate() {
        return Instant.now().plus(properties.getProductExpirationDays(), ChronoUnit.DAYS);
    }
}
