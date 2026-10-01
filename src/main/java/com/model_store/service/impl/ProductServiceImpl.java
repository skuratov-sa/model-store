package com.model_store.service.impl;

import com.model_store.configuration.property.ApplicationProperties;
import com.model_store.exception.ApiErrors;
import com.model_store.exception.constant.ErrorCode;
import com.model_store.mapper.ProductMapper;
import com.model_store.model.CreateAgentProductRequest;
import com.model_store.model.CreateOrUpdateProductRequest;
import com.model_store.model.FindMyProductRequest;
import com.model_store.model.FindProductRequest;
import com.model_store.model.GiveawaySettingsRequest;
import com.model_store.model.ReviewResponseDto;
import com.model_store.model.base.Product;
import com.model_store.model.base.SellerRating;
import com.model_store.model.constant.ImageStatus;
import com.model_store.model.constant.ImageTag;
import com.model_store.model.constant.Currency;
import com.model_store.model.constant.ParticipantRole;
import com.model_store.model.constant.ProductAvailabilityType;
import com.model_store.model.constant.ProductStatus;
import com.model_store.model.dto.CategoryDto;
import com.model_store.model.dto.AdminGiveawayProductResponse;
import com.model_store.model.dto.GetProductResponse;
import com.model_store.model.dto.GiveawayResponse;
import com.model_store.model.dto.ProductDto;
import com.model_store.repository.ImageRepository;
import com.model_store.repository.OrderRepository;
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
import java.net.URI;
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
    private final OrderRepository orderRepository;

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
            SellerRatingRepository sellerRatingRepository,
            OrderRepository orderRepository
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
        this.orderRepository = orderRepository;
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
        Mono<Boolean> includeAdult = currentParticipantId == null
                ? Mono.just(false)
                : participantService.findAgeById(currentParticipantId)
                        .map(age -> age >= 18)
                        .defaultIfEmpty(false);
        return includeAdult.flatMapMany(allowed ->
                buildProductDtos(productRepository.findByParams(searchParams, null, allowed)));
    }

    @Override
    public Flux<ProductDto> findMyByParams(FindMyProductRequest searchParams, Long participantId) {
        return findMyByParams(searchParams, participantId, false);
    }

    @Override
    public Flux<ProductDto> findMyByParams(FindMyProductRequest searchParams, Long participantId, boolean includeGiveaways) {
        return buildProductDtos(productRepository.findMyByParams(searchParams, participantId, includeGiveaways));
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

    @Override
    public Mono<Product> findActualProductForUpdate(Long productId) {
        return productRepository.findActualProductForUpdate(productId);
    }

    @Override
    public Mono<Product> findByIdForUpdate(Long productId) {
        return productRepository.findByIdForUpdate(productId);
    }

    @Override
    public Mono<GiveawayResponse> findActiveGiveaway() {
        return productRepository.findActiveGiveaway().flatMap(this::toGiveawayResponse);
    }

    @Override
    public Mono<GiveawayResponse> findPublicGiveawayById(Long productId) {
        return productRepository.findPublicGiveawayById(productId).flatMap(this::toGiveawayResponse);
    }

    private Mono<GiveawayResponse> toGiveawayResponse(Product product) {
        return imageService.findActualImages(product.getId(), ImageTag.PRODUCT)
                .collectList()
                .map(images -> new GiveawayResponse(
                        product.getId(), product.getName(), product.getDescription(), images,
                        product.getGiveawayTelegramUrl(), product.getGiveawayStartAt(), product.getGiveawayEndAt(),
                        product.getGiveawayWinnersCount(), product.getGiveawayRules(), product.getGiveawayHomeText(),
                        product.getGiveawayStartAt().isAfter(Instant.now())
                                ? ProductStatus.AWAITING_GIVEAWAY : ACTIVE
                ));
    }

    @Override
    public Flux<Product> findGiveawayHistory(Long adminId, int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            return Flux.error(ApiErrors.badRequest(ErrorCode.INVALID_REQUEST, "Некорректная страница истории"));
        }
        return productRepository.findGiveawayHistory(adminId, size, (long) page * size);
    }

    @Override
    public Mono<Product> findAdminGiveaway(Long productId, Long adminId) {
        return productRepository.findById(productId)
                .filter(product -> product.getStatus() != ProductStatus.DELETED && product.getGiveawayEndAt() != null)
                .flatMap(product -> Objects.equals(product.getParticipantId(), adminId)
                        ? Mono.just(product)
                        : participantRepository.findByIdAndIsAgentTrue(product.getParticipantId())
                                .hasElement()
                                .flatMap(isAgent -> isAgent ? Mono.just(product) : Mono.empty()))
                .switchIfEmpty(Mono.error(ApiErrors.notFound(ErrorCode.PRODUCT_NOT_FOUND, "Розыгрыш не найден")));
    }

    @Override
    public Mono<AdminGiveawayProductResponse> findAdminGiveawayDetails(Long productId, Long adminId) {
        return findAdminGiveaway(productId, adminId)
                .flatMap(product -> Mono.zip(
                        imageService.findActualImages(productId, ImageTag.PRODUCT).collectList(),
                        categoryService.findByProductId(productId).collectList()
                ).map(details -> new AdminGiveawayProductResponse(product, details.getT1(), details.getT2())));
    }

    @Override
    @Transactional
    public Mono<Void> updateAdminGiveaway(Long productId, GiveawaySettingsRequest settings, Long adminId) {
        if (settings == null) {
            return Mono.error(ApiErrors.badRequest(ErrorCode.INVALID_REQUEST, "Пустые настройки розыгрыша"));
        }
        return findAdminGiveaway(productId, adminId)
                .flatMap(product -> {
                    CreateOrUpdateProductRequest request = new CreateOrUpdateProductRequest();
                    request.setAvailability(ProductAvailabilityType.GIVEAWAY);
                    request.setGiveaway(settings);
                    return updateOwnedProduct(productId, request, product.getParticipantId(), true);
                });
    }

    private void applyGiveawaySettings(Product product, GiveawaySettingsRequest settings, boolean enteringGiveaway) {
        if (settings == null && enteringGiveaway) {
            throw ApiErrors.badRequest(ErrorCode.INVALID_REQUEST, "Укажите настройки розыгрыша");
        }
        if (settings != null) {
            if (settings.getTelegramUrl() != null) product.setGiveawayTelegramUrl(settings.getTelegramUrl().trim());
            if (settings.getStartAt() != null) product.setGiveawayStartAt(settings.getStartAt());
            if (settings.getEndAt() != null) product.setGiveawayEndAt(settings.getEndAt());
            if (settings.getWinnersCount() != null) product.setGiveawayWinnersCount(settings.getWinnersCount());
            if (settings.getRules() != null) product.setGiveawayRules(settings.getRules());
            if (settings.getHomeText() != null) product.setGiveawayHomeText(settings.getHomeText());
            if (settings.getEnabled() != null) product.setGiveawayEnabled(settings.getEnabled());
            else if (enteringGiveaway) product.setGiveawayEnabled(true);
        }
        if (!isValidTelegramUrl(product.getGiveawayTelegramUrl())
                || product.getGiveawayStartAt() == null || product.getGiveawayEndAt() == null
                || !product.getGiveawayStartAt().isBefore(product.getGiveawayEndAt())
                || product.getGiveawayWinnersCount() == null || product.getGiveawayWinnersCount() <= 0
                || product.getGiveawayRules() == null || product.getGiveawayRules().isBlank()
                || product.getGiveawayHomeText() == null || product.getGiveawayHomeText().isBlank()) {
            throw ApiErrors.badRequest(ErrorCode.INVALID_REQUEST, "Некорректные настройки розыгрыша");
        }
        if (Boolean.TRUE.equals(product.getGiveawayEnabled())) {
            if (!product.getGiveawayEndAt().isAfter(Instant.now())) {
                throw ApiErrors.badRequest(ErrorCode.INVALID_REQUEST, "Дата окончания розыгрыша должна быть в будущем");
            }
            if (product.getStatus() == ProductStatus.BLOCKED || product.getStatus() == ProductStatus.DELETED) {
                throw ApiErrors.badRequest(ErrorCode.INVALID_REQUEST, "Заблокированный товар нельзя активировать");
            }
            product.setStatus(product.getGiveawayStartAt().isAfter(Instant.now())
                    ? ProductStatus.AWAITING_GIVEAWAY : ACTIVE);
        } else if (product.getStatus() == ACTIVE || product.getStatus() == ProductStatus.AWAITING_GIVEAWAY) {
            product.setStatus(ProductStatus.TIME_EXPIRED);
        }
    }

    private boolean isValidTelegramUrl(String value) {
        if (value == null || value.isBlank()) return false;
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            return "https".equalsIgnoreCase(uri.getScheme())
                    && host != null
                    && (host.equalsIgnoreCase("t.me") || host.equalsIgnoreCase("telegram.me"))
                    && uri.getRawUserInfo() == null
                    && uri.getPath() != null && uri.getPath().length() > 1;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    @Transactional
    public Mono<Long> createProduct(CreateOrUpdateProductRequest request, Long participantId, ParticipantRole role) {
        if (request == null || request.getAvailability() == null) {
            return Mono.error(ApiErrors.badRequest(ErrorCode.INVALID_REQUEST, "Укажите тип товара"));
        }
        if (role != ParticipantRole.ADMIN && request.getAvailability() != ProductAvailabilityType.PURCHASABLE
                && request.getAvailability() != ProductAvailabilityType.PREORDER) {
            return Mono.error(ApiErrors.forbidden(ErrorCode.ACCESS_DENIED, "Этот тип товара доступен только администратору"));
        }
        if (request.getAvailability() != ProductAvailabilityType.GIVEAWAY && request.getGiveaway() != null) {
            return Mono.error(ApiErrors.badRequest(ErrorCode.INVALID_REQUEST, "Настройки розыгрыша допустимы только для GIVEAWAY"));
        }
        if (role == ParticipantRole.ADMIN && request.getAvailability() == ProductAvailabilityType.GIVEAWAY) {
            return Mono.defer(() -> createProduct(request, participantId));
        }
        return Mono.zip(
                transferService.findByParticipantId(participantId).hasElements(),
                socialNetworksService.findByParticipantId(participantId).hasElements()
        ).flatMap(tuple -> {
            if (!tuple.getT1())
                return Mono.error(ApiErrors.badRequest(ErrorCode.TRANSFER_NOT_FOUND, "Добавьте способ получения оплаты перед созданием товара"));
            if (!tuple.getT2())
                return Mono.error(ApiErrors.badRequest(ErrorCode.SOCIAL_NETWORK_NOT_FOUND, "Добавьте социальную сеть перед созданием товара"));
            return Mono.defer(() -> createProduct(request, participantId));
        });
    }

    private Mono<Long> createProduct(CreateOrUpdateProductRequest request, Long participantId) {
        log.info("Create product: participantId={}", participantId);

        if (ProductAvailabilityType.PREORDER.equals(request.getAvailability())
                && (isNull(request.getPrepaymentAmount()) || request.getPrepaymentAmount() <= 0)) {
            return Mono.error(ApiErrors.badRequest(ErrorCode.INVALID_REQUEST, "Предоплата указана неверно"));
        }

        if (nonNull(request.getCount()) && request.getCount() <= 0) {
            return Mono.error(ApiErrors.badRequest(ErrorCode.INVALID_REQUEST, "Введено некорректное кол-во товаров"));
        }

        if (request.getAvailability() == ProductAvailabilityType.EXTERNAL_PRODUCT
                && (request.getExternalUrl() == null || request.getExternalUrl().isBlank())) {
            return Mono.error(ApiErrors.badRequest(ErrorCode.INVALID_REQUEST, "Укажите ссылку на внешний товар"));
        }
        if (request.getPrepaymentAmount() != null
                && (request.getPrepaymentAmount() < 0
                    || request.getPrice() == null
                    || request.getPrepaymentAmount() > request.getPrice())) {
            return Mono.error(ApiErrors.badRequest(ErrorCode.INVALID_REQUEST, "Предоплата указана неверно"));
        }

        Product product = productMapper.toProduct(request, participantId, ACTIVE, getExpirationDate());
        if (product.getUsed() == null) product.setUsed(false);
        if (request.getAvailability() == ProductAvailabilityType.EXTERNAL_PRODUCT) product.setCount(null);
        if (request.getAvailability() == ProductAvailabilityType.PURCHASABLE) product.setPrepaymentAmount(null);
        if (request.getAvailability() == ProductAvailabilityType.GIVEAWAY) {
            product.setCount(null);
            product.setPrepaymentAmount(null);
            if (product.getPrice() == null) product.setPrice(0f);
            if (product.getCurrency() == null) product.setCurrency(Currency.RUB);
            applyGiveawaySettings(product, request.getGiveaway(), true);
        }

        Mono<Void> deactivatePrevious = Boolean.TRUE.equals(product.getGiveawayEnabled())
                ? lockGiveawayActivation().then(productRepository.deactivateOtherGiveaways(-1L)).then()
                : Mono.empty();
        return deactivatePrevious.then(productRepository.save(product))
                .flatMap(savedProduct ->
                        updateImagesStatus(request.getImageIds(), savedProduct.getId())
                                .then(addLinkProductAndCategories(request.getCategoryIds(), savedProduct.getId()))
                                .thenReturn(savedProduct.getId())
                );
    }

    @Override
    @Transactional
    public Mono<Long> createAgentProduct(CreateAgentProductRequest request, Long participantId) {
        log.info("Create agent product: participantId={}", participantId);
        if (request.getExternalUrl() == null || request.getExternalUrl().isBlank()) {
            return Mono.error(ApiErrors.badRequest(ErrorCode.INVALID_REQUEST, "Укажите ссылку на внешний товар"));
        }
        Product product = Product.builder()
                .name(request.getName())
                .description(request.getDescription())
                .price(request.getPrice())
                .currency(request.getCurrency())
                .originality(request.getOriginality())
                .externalUrl(request.getExternalUrl())
                .availability(ProductAvailabilityType.EXTERNAL_PRODUCT)
                .used(Boolean.TRUE.equals(request.getUsed()))
                .count(null)
                .participantId(participantId)
                .status(ACTIVE)
                .expirationDate(getExpirationDate())
                .build();

        return participantRepository.findByIdAndIsAgentTrue(participantId)
                .switchIfEmpty(Mono.error(ApiErrors.forbidden(ErrorCode.ACCESS_DENIED, "Учетная запись не является ботом")))
                .filter(p -> p.getStatus() == com.model_store.model.constant.ParticipantStatus.ACTIVE)
                .switchIfEmpty(Mono.error(ApiErrors.forbidden(ErrorCode.ACCESS_DENIED, "Бот не активен")))
                .then(productRepository.save(product))
                .flatMap(savedProduct ->
                        updateImagesStatus(request.getImageIds(), savedProduct.getId())
                                .then(addLinkProductAndCategories(request.getCategoryIds(), savedProduct.getId()))
                                .thenReturn(savedProduct.getId())
                );
    }

    @Transactional
    public Mono<Void> updateProduct(Long id, CreateOrUpdateProductRequest request, Long participantId) {
        return updateProduct(id, request, participantId, ParticipantRole.USER);
    }

    @Override
    @Transactional
    public Mono<Void> updateProduct(Long id, CreateOrUpdateProductRequest request, Long participantId, ParticipantRole role) {
        log.info("Update product: productId={}, participantId={}", id, participantId);

        if (request == null) return Mono.error(ApiErrors.badRequest(ErrorCode.INVALID_REQUEST, "Пустой запрос"));
        if (role != ParticipantRole.ADMIN && request.getAvailability() != null
                && request.getAvailability() != ProductAvailabilityType.PURCHASABLE
                && request.getAvailability() != ProductAvailabilityType.PREORDER) {
            return Mono.error(ApiErrors.forbidden(ErrorCode.ACCESS_DENIED, "Этот тип товара доступен только администратору"));
        }

        return participantRepository.findByIdAndIsAgentTrue(participantId)
                .hasElement()
                .flatMap(isAgent -> isAgent
                        ? Mono.error(ApiErrors.forbidden(ErrorCode.ACCESS_DENIED, "Товары бота редактирует администратор"))
                        : updateOwnedProduct(id, request, participantId, role == ParticipantRole.ADMIN));
    }

    @Override
    @Transactional
    public Mono<Void> updateAgentProduct(Long id, CreateOrUpdateProductRequest request, Long agentId) {
        if (request == null || (request.getAvailability() != null
                && request.getAvailability() != ProductAvailabilityType.EXTERNAL_PRODUCT
                && request.getAvailability() != ProductAvailabilityType.GIVEAWAY)) {
            return Mono.error(ApiErrors.badRequest(ErrorCode.INVALID_REQUEST, "Товар бота может быть EXTERNAL_PRODUCT или GIVEAWAY"));
        }
        return participantRepository.findByIdAndIsAgentTrue(agentId)
                .switchIfEmpty(Mono.error(ApiErrors.notFound(ErrorCode.PARTICIPANT_NOT_FOUND, "Бот не найден")))
                .then(updateOwnedProduct(id, request, agentId, true));
    }

    private Mono<Void> updateOwnedProduct(Long id, CreateOrUpdateProductRequest request,
                                          Long participantId, boolean includeInactive) {
        Mono<Product> source = includeInactive
                ? productRepository.findByIdForUpdate(id).filter(p -> p.getStatus() != ProductStatus.DELETED)
                : productRepository.findActualProductForUpdate(id);
        Mono<Void> activationLock = includeInactive ? lockGiveawayActivation() : Mono.empty();
        return activationLock.then(source)
                .filter(product -> Objects.equals(product.getParticipantId(), participantId))
                .switchIfEmpty(Mono.error(
                        ApiErrors.notFound(ErrorCode.PRODUCT_NOT_FOUND, "Не удалось выполнить операцию: не достаточно прав или его не существует")
                ))
                .flatMap(original -> {
                    if (!includeInactive && request.getGiveaway() != null) {
                        return Mono.error(ApiErrors.forbidden(ErrorCode.ACCESS_DENIED, "Настройки розыгрыша меняет администратор"));
                    }
                    Product product = productMapper.updateProduct(request, original);
                    boolean enteringGiveaway = original.getAvailability() != ProductAvailabilityType.GIVEAWAY
                            && product.getAvailability() == ProductAvailabilityType.GIVEAWAY;
                    if (product.getAvailability() == ProductAvailabilityType.GIVEAWAY) {
                        if (!includeInactive) {
                            return Mono.error(ApiErrors.forbidden(ErrorCode.ACCESS_DENIED, "Розыгрыш настраивает администратор"));
                        }
                        product.setCount(null);
                        product.setPrepaymentAmount(null);
                        applyGiveawaySettings(product, request.getGiveaway(), enteringGiveaway);
                    } else if (original.getAvailability() == ProductAvailabilityType.GIVEAWAY) {
                        product.setGiveawayEnabled(false);
                        if (product.getPrice() == null || product.getPrice() <= 0) {
                            return Mono.error(ApiErrors.badRequest(ErrorCode.INVALID_REQUEST, "Укажите цену товара при возврате в продажу"));
                        }
                        if (product.getStatus() == ProductStatus.TIME_EXPIRED
                                || product.getStatus() == ProductStatus.AWAITING_GIVEAWAY) product.setStatus(ACTIVE);
                        if (!product.getExpirationDate().isAfter(Instant.now())) product.setExpirationDate(getExpirationDate());
                    } else if (request.getGiveaway() != null) {
                        return Mono.error(ApiErrors.badRequest(ErrorCode.INVALID_REQUEST, "Настройки розыгрыша допустимы только для GIVEAWAY"));
                    }
                    if (product.getAvailability() == ProductAvailabilityType.EXTERNAL_PRODUCT
                            && (product.getExternalUrl() == null || product.getExternalUrl().isBlank())) {
                        return Mono.error(ApiErrors.badRequest(ErrorCode.INVALID_REQUEST, "Укажите ссылку на внешний товар"));
                    }
                    if (product.getPrepaymentAmount() != null
                            && (product.getPrepaymentAmount() < 0
                                || product.getPrice() == null
                                || product.getPrepaymentAmount() > product.getPrice())) {
                        return Mono.error(ApiErrors.badRequest(ErrorCode.INVALID_REQUEST, "Предоплата указана неверно"));
                    }
                    Mono<Void> noOrders = enteringGiveaway
                            ? orderRepository.existsActiveOrderForProduct(id).flatMap(hasOrders -> hasOrders
                                    ? Mono.error(ApiErrors.badRequest(ErrorCode.INVALID_REQUEST, "Товар с незавершёнными заказами нельзя перевести в розыгрыш"))
                                    : Mono.empty())
                            : Mono.empty();
                    Mono<Void> deactivatePrevious = Boolean.TRUE.equals(product.getGiveawayEnabled())
                            ? productRepository.deactivateOtherGiveaways(id).then()
                            : Mono.empty();
                    return noOrders.then(deactivatePrevious).thenReturn(product);
                })
                .flatMap(productRepository::save)
                .flatMap(p -> updateImagesStatus(request.getImageIds(), id)
                        .then(replaceCategories(request.getCategoryIds(), id)))
                .then();
    }

    private Mono<Void> lockGiveawayActivation() {
        return productRepository.lockGiveawayActivation()
                .switchIfEmpty(Mono.error(new IllegalStateException("Не найдена запись блокировки розыгрыша")))
                .then();
    }

    @Transactional
    public Mono<Void> deleteProduct(Long id, Long participantId) {
        log.info("Delete product id: {}, participantId: {}", id, participantId);
        return productRepository.findActualProductForUpdate(id)
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
    @Transactional
    public Mono<Void> updateProductStatus(Long id, ProductStatus status) {
        log.info("Update product id: {}, status: {}", id, status);
        if (status == ProductStatus.AWAITING_GIVEAWAY) {
            return Mono.error(ApiErrors.badRequest(ErrorCode.INVALID_REQUEST, "Статус ожидания задаётся датой начала розыгрыша"));
        }
        return lockGiveawayActivation().then(productRepository.findByIdForUpdate(id))
                .filter(product -> product.getStatus() != ProductStatus.DELETED)
                .switchIfEmpty(Mono.error(
                        ApiErrors.notFound(ErrorCode.PRODUCT_NOT_FOUND, "Не удалось выполнить операцию: не достаточно прав или его не существует")
                )).flatMap(product -> {
                    if (status == ACTIVE && (product.getExpirationDate() == null
                            || !product.getExpirationDate().isAfter(Instant.now()))) {
                        product.setExpirationDate(getExpirationDate());
                    }
                    if (product.getAvailability() == ProductAvailabilityType.GIVEAWAY && status != ACTIVE) {
                        product.setGiveawayEnabled(false);
                    }
                    product.setStatus(status);
                    return productRepository.save(product).then();
                });
    }

    @Override
    public Mono<Long> save(Product product) {
        return productRepository.save(product).map(Product::getId);
    }

    @Override
    @Transactional
    public Mono<Void> extendExpirationDate(Long id, Long participantId) {
        log.info("Extend expiration date id: {}, participantId: {}", id, participantId);
        return productRepository.findProductForExtendForUpdate(id)
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

        if (isNull(productId) || categoryIds == null || categoryIds.isEmpty()) {
            return Mono.empty();
        }
        return categoryService.addLinkProductAndCategories(categoryIds, productId);
    }

    private Mono<Void> replaceCategories(List<Long> categoryIds, Long productId) {
        if (categoryIds == null) return Mono.empty();
        return productCategoryRepository.deleteByProductId(productId)
                .then(categoryIds.isEmpty()
                        ? Mono.empty()
                        : categoryService.addLinkProductAndCategories(categoryIds, productId));
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
        return productRepository.incrementCountIfLimited(productId, amount)
                .flatMap(updated -> updated == 1 ? Mono.<Void>empty()
                        : Mono.error(new IllegalStateException("Не удалось восстановить остаток товара")));
    }

    private Instant getExpirationDate() {
        return Instant.now().plus(properties.getProductExpirationDays(), ChronoUnit.DAYS);
    }
}
