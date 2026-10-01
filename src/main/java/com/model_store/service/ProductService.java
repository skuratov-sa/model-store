package com.model_store.service;

import com.model_store.model.CreateAgentProductRequest;
import com.model_store.model.CreateOrUpdateProductRequest;
import com.model_store.model.FindMyProductRequest;
import com.model_store.model.FindProductRequest;
import com.model_store.model.GiveawaySettingsRequest;
import com.model_store.model.base.Product;
import com.model_store.model.constant.ParticipantRole;
import com.model_store.model.constant.ProductStatus;
import com.model_store.model.dto.GetProductResponse;
import com.model_store.model.dto.AdminGiveawayProductResponse;
import com.model_store.model.dto.GiveawayResponse;
import com.model_store.model.dto.ProductDto;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

public interface ProductService {

    Mono<GetProductResponse> getProductById(Long productId);

    Mono<Product> findById(Long productId);

    Mono<Product> findByIdForUpdate(Long productId);

    Mono<ProductDto> shortInfoById(Long productId);

    Flux<String> findNamesBySearch(String search);

    Flux<ProductDto> findByParams(FindProductRequest searchParams, Long currentParticipantId);

    Mono<ProductDto> buildProductDto(Product product);

    Flux<ProductDto> buildProductDtos(List<Product> products);

    Flux<Long> findExpiredActiveProductIds();

    Flux<ProductDto> findMyByParams(FindMyProductRequest searchParams, Long participantId);

    Flux<ProductDto> findMyByParams(FindMyProductRequest searchParams, Long participantId, boolean includeGiveaways);

    Mono<Long> createProduct(CreateOrUpdateProductRequest request, Long participantId, ParticipantRole role);

    Mono<Long> createAgentProduct(CreateAgentProductRequest request, Long participantId);

    Mono<Void> updateProduct(Long id, CreateOrUpdateProductRequest request, Long participantId);

    Mono<Void> updateProduct(Long id, CreateOrUpdateProductRequest request, Long participantId, ParticipantRole role);

    Mono<Void> updateAgentProduct(Long id, CreateOrUpdateProductRequest request, Long agentId);

    Mono<Void> deleteProduct(Long id, Long participantId);

    Mono<Product> findActualProduct(Long productId);

    Mono<Product> findActualProductForUpdate(Long productId);

    Mono<GiveawayResponse> findActiveGiveaway();

    Mono<GiveawayResponse> findPublicGiveawayById(Long productId);

    Flux<Product> findGiveawayHistory(Long adminId, int page, int size);

    Mono<Product> findAdminGiveaway(Long productId, Long adminId);

    Mono<AdminGiveawayProductResponse> findAdminGiveawayDetails(Long productId, Long adminId);

    Mono<Void> updateAdminGiveaway(Long productId, GiveawaySettingsRequest request, Long adminId);

    Mono<Void> updateProductStatus(Long id, ProductStatus status);

    Mono<Long> save(Product product);

    Mono<Void> extendExpirationDate(Long id, Long participantId);

    Mono<Void> decrementCountIfSufficient(Long productId, Integer amount);

    Mono<Void> incrementCountIfLimited(Long productId, Integer amount);
}
