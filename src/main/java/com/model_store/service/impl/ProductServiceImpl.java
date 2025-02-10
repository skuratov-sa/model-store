package com.model_store.service.impl;

import com.amazonaws.services.kms.model.NotFoundException;
import com.model_store.mapper.ProductMapper;
import com.model_store.model.CreateOrUpdateProductRequest;
import com.model_store.model.FindProductRequest;
import com.model_store.model.base.Product;
import com.model_store.model.base.ProductFavorite;
import com.model_store.model.constant.ImageStatus;
import com.model_store.model.constant.ImageTag;
import com.model_store.model.constant.ProductStatus;
import com.model_store.model.dto.GetProductResponse;
import com.model_store.model.dto.ProductDto;
import com.model_store.model.page.PagedResult;
import com.model_store.repository.ProductFavoriteRepository;
import com.model_store.repository.ProductRepository;
import com.model_store.service.CategoryService;
import com.model_store.service.ParticipantService;
import com.model_store.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

import static java.util.Objects.isNull;

@Service
@RequiredArgsConstructor
public class ProductServiceImpl implements ProductService {
    private final ProductRepository productRepository;
    private final ParticipantService participantService;
    private final ProductFavoriteRepository productFavoriteRepository;
    private final CategoryService categoryService;
    private final ProductMapper productMapper;
    private final ImageServiceImpl imageService;

    @Transactional
    public Mono<GetProductResponse> getProductById(Long productId) {
        return Mono.zip(productRepository.findActualProduct(productId),
                imageService.findActualImages(productId, ImageTag.PRODUCT).collectList().defaultIfEmpty(List.of())
        ).flatMap(tuple2 ->
                categoryService.findById(tuple2.getT1().getCategoryId())
                        .map(category -> productMapper.toGetProductResponse(tuple2.getT1(), category, tuple2.getT2())
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
                .flatMap(product -> categoryService.findById(product.getCategoryId())
                        .zipWith(imageService.findMainImage(product.getId(), ImageTag.PRODUCT).defaultIfEmpty(-1L))
                        .map(tuple2 ->
                                productMapper.toProductDto(product, tuple2.getT1(), tuple2.getT2() == -1L ? null : tuple2.getT2())
                        )
                );
    }

    public Mono<PagedResult<ProductDto>> findByParams(FindProductRequest searchParams) {
        // 1. Получаем список продуктов
        Mono<List<ProductDto>> products = productRepository.findByParams(searchParams, null)
                .flatMap(product -> categoryService.findById(product.getCategoryId())
                        .zipWith(imageService.findMainImage(product.getId(), ImageTag.PRODUCT).defaultIfEmpty(-1L))
                        .map(tuple -> productMapper.toProductDto(product, tuple.getT1(), tuple.getT2() == -1L ? null : tuple.getT2()))
                ).collectList();

        // 2. Получаем общее количество
        Mono<Integer> totalCount = productRepository.findCountBySearchParams(searchParams, null).defaultIfEmpty(0);

        return products.zipWith(totalCount)
                .map(tuple -> new PagedResult<>(tuple.getT1(), tuple.getT2(), searchParams.getPageable()));
    }

    public Flux<PagedResult<Product>> findFavoriteByParams(Long participantId, FindProductRequest searchParams) {

        return productFavoriteRepository.findByParticipantId(participantId)
                .map(ProductFavorite::getProductId)
                .collectList()
                .flatMapMany(productIds -> {
                    var monoProducts = productRepository.findByParams(searchParams, productIds.toArray(Long[]::new)).collectList();
                    var monoTotalCount = productRepository.findCountBySearchParams(searchParams, null).defaultIfEmpty(0);

                    return monoProducts.zipWith(monoTotalCount)
                            .map(tuple -> new PagedResult<>(tuple.getT1(), tuple.getT2(), searchParams.getPageable()));

                });
    }

    public Mono<Void> addToFavorites(Long participantId, Long productId) {
        return Mono.zip(productRepository.findActualProduct(productId), participantService.findActualById(participantId))
                .switchIfEmpty(Mono.error(new NotFoundException("Product or participant not found")))
                .flatMap(ignore ->
                        productFavoriteRepository.findByParticipantIdAndProductId(participantId, productId)
                                .switchIfEmpty(productFavoriteRepository.save(ProductFavorite.builder()
                                        .participantId(participantId)
                                        .productId(productId)
                                        .build())
                                ).then()
                );
    }

    public Mono<Void> removeFromFavorites(Long participantId, Long productId) {
        return productFavoriteRepository.deleteByParticipantIdAndProductId(participantId, productId);
    }

    @Transactional
    public Mono<Long> createProduct(CreateOrUpdateProductRequest request) {
        Product product = productMapper.toProduct(request);
        return productRepository.save(product)
                .flatMap(p -> updateImagesStatus(request.getImageIds(), p.getId())
                        .then(Mono.just(p.getId()))
                );
    }

    @Transactional
    public Mono<Void> updateProduct(Long id, CreateOrUpdateProductRequest request) {
        return productRepository.findActualProduct(id)
                .switchIfEmpty(Mono.error(new NotFoundException("Product not found")))
                .map(product -> productMapper.updateProduct(request, product))
                .flatMap(productRepository::save)
                .flatMap(p -> updateImagesStatus(request.getImageIds(), id))
                .then();
    }

    public Mono<Void> deleteProduct(Long id) {
        return productRepository.findActualProduct(id)
                .switchIfEmpty(Mono.error(new NotFoundException("Product not found")))
                .flatMap(product -> {
                    product.setStatus(ProductStatus.DELETED);
                    return productRepository.save(product)
                            .then(imageService.deleteImagesByEntityId(product.getParticipantId(), ImageTag.PRODUCT));
                });
    }

    public Mono<Void> deleteProductsByParticipant(Long participantId) {
        return productRepository.findByParticipantId(participantId).then();
    }

    @Override
    public Mono<Long> save(Product product) {
        return productRepository.save(product).map(Product::getId);
    }

    private Mono<Void> updateImagesStatus(List<Long> imageIds, Long productId) {
        if (isNull(imageIds) || imageIds.isEmpty()) {
            return Mono.empty();
        }
        return imageService.updateImagesStatus(imageIds, productId, ImageStatus.ACTIVE, ImageTag.PRODUCT);
    }
}