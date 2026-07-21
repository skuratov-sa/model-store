package com.model_store.service.impl;

import com.model_store.model.CreateOrUpdateProductRequest;
import com.model_store.model.FindProductRequest;
import com.model_store.model.base.Participant;
import com.model_store.model.base.Product;
import com.model_store.model.constant.Currency;
import com.model_store.model.constant.ParticipantRole;
import com.model_store.model.constant.ParticipantStatus;
import com.model_store.model.constant.ProductAvailabilityType;
import com.model_store.model.constant.ProductStatus;
import com.model_store.model.constant.SellerStatus;
import com.model_store.service.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class ProductServiceImplTest extends IntegrationTest {

    @Autowired
    private DatabaseClient databaseClient;

    @BeforeEach
    void cleanUp() {
        databaseClient.sql("TRUNCATE TABLE product_category, product, participant RESTART IDENTITY CASCADE")
                .fetch().rowsUpdated().block();
    }

    @Test
    void getProductById_returnsCorrectProductDetails() {
        var result = newParticipant()
                .flatMap(p -> createPurchasableProduct(ProductStatus.ACTIVE, p.getId()))
                .flatMap(product -> productService.getProductById(product.getId()));

        StepVerifier.create(result)
                .assertNext(response -> {
                    assertThat(response.getName()).isEqualTo("Test Product");
                    assertThat(response.getStatus()).isEqualTo(ProductStatus.ACTIVE);
                    assertThat(response.getPrice()).isEqualTo(1500f);
                })
                .verifyComplete();
    }

    @Test
    void findById_existingProduct_returnsProduct() {
        var result = newParticipant()
                .flatMap(p -> createPurchasableProduct(ProductStatus.ACTIVE, p.getId()))
                .flatMap(saved -> productService.findById(saved.getId()));

        StepVerifier.create(result)
                .assertNext(product -> assertThat(product.getName()).isEqualTo("Test Product"))
                .verifyComplete();
    }

    @Test
    void findById_nonExistingProduct_returnsEmpty() {
        StepVerifier.create(productService.findById(999999L))
                .verifyComplete();
    }

    @Test
    void shortInfoById_returnsProductDto() {
        var result = newParticipant()
                .flatMap(p -> createPurchasableProduct(ProductStatus.ACTIVE, p.getId()))
                .flatMap(saved -> productService.shortInfoById(saved.getId()));

        StepVerifier.create(result)
                .assertNext(dto -> {
                    assertThat(dto.getName()).isEqualTo("Test Product");
                    assertThat(dto.getPrice()).isEqualTo(1500f);
                })
                .verifyComplete();
    }

    @Test
    void findByParams_externalProduct_returnsExternalUrl() {
        FindProductRequest request = new FindProductRequest();
        request.setIncludeAdult(false);

        var result = newParticipant()
                .flatMap(p -> createExternalProduct(ProductStatus.ACTIVE, p.getId()))
                .thenMany(productService.findByParams(request, null));

        StepVerifier.create(result)
                .assertNext(dto -> assertThat(dto.getExternalUrl()).isEqualTo("https://example.com"))
                .verifyComplete();
    }

    @Test
    void findNamesBySearch_matchesProductName() {
        var result = newParticipant()
                .flatMap(p -> createPurchasableProduct(ProductStatus.ACTIVE, p.getId()))
                .thenMany(productService.findNamesBySearch("Test"));

        StepVerifier.create(result)
                .assertNext(name -> assertThat(name).containsIgnoringCase("Test"))
                .thenCancel()
                .verify();
    }

    @Test
    void findActualProduct_activeProduct_returnsIt() {
        var result = newParticipant()
                .flatMap(p -> createPurchasableProduct(ProductStatus.ACTIVE, p.getId()))
                .flatMap(saved -> productService.findActualProduct(saved.getId()));

        StepVerifier.create(result)
                .assertNext(product -> assertThat(product.getStatus()).isEqualTo(ProductStatus.ACTIVE))
                .verifyComplete();
    }

    @Test
    void findActualProduct_deletedProduct_returnsEmpty() {
        var result = newParticipant()
                .flatMap(p -> createPurchasableProduct(ProductStatus.DELETED, p.getId()))
                .flatMap(saved -> productService.findActualProduct(saved.getId()));

        StepVerifier.create(result)
                .verifyComplete();
    }

    @Test
    void updateProduct_ownerUpdatesFields_persistsChanges() {
        var result = newParticipant()
                .flatMap(p -> createPurchasableProduct(ProductStatus.ACTIVE, p.getId())
                        .flatMap(saved -> {
                            CreateOrUpdateProductRequest req = updateRequest("Updated Name", 999f);
                            return productService.updateProduct(saved.getId(), req, p.getId())
                                    .then(productRepository.findById(saved.getId()));
                        })
                );

        StepVerifier.create(result)
                .assertNext(updated -> {
                    assertThat(updated.getName()).isEqualTo("Updated Name");
                    assertThat(updated.getPrice()).isEqualTo(999f);
                })
                .verifyComplete();
    }

    @Test
    void deleteProduct_setsStatusToDeleted() {
        var result = newParticipant()
                .flatMap(p -> createPurchasableProduct(ProductStatus.ACTIVE, p.getId())
                        .flatMap(saved ->
                                productService.deleteProduct(saved.getId(), p.getId())
                                        .then(productRepository.findById(saved.getId()))
                        )
                );

        StepVerifier.create(result)
                .assertNext(product -> assertThat(product.getStatus()).isEqualTo(ProductStatus.DELETED))
                .verifyComplete();
    }

    @Test
    void updateProductStatus_changesStatus() {
        var result = newParticipant()
                .flatMap(p -> createPurchasableProduct(ProductStatus.ACTIVE, p.getId())
                        .flatMap(saved ->
                                productService.updateProductStatus(saved.getId(), ProductStatus.BLOCKED)
                                        .then(productRepository.findById(saved.getId()))
                        )
                );

        StepVerifier.create(result)
                .assertNext(product -> assertThat(product.getStatus()).isEqualTo(ProductStatus.BLOCKED))
                .verifyComplete();
    }

    @Test
    void save_persistsProductAndReturnsId() {
        var result = newParticipant()
                .flatMap(p -> {
                    Product product = Product.builder()
                            .name("Direct Save")
                            .price(50f)
                            .currency(Currency.RUB)
                            .originality("Copy")
                            .participantId(p.getId())
                            .status(ProductStatus.ACTIVE)
                            .availability(ProductAvailabilityType.PURCHASABLE)
                            .count(3)
                            .expirationDate(Instant.now().plusSeconds(86400 * 30))
                            .build();
                    return productService.save(product);
                });

        StepVerifier.create(result)
                .assertNext(id -> assertThat(id).isPositive())
                .verifyComplete();
    }

    @Test
    void extendExpirationDate_ownerExtendsDate_newDateIsLater() {
        var result = newParticipant()
                .flatMap(p -> createPurchasableProduct(ProductStatus.ACTIVE, p.getId())
                        .flatMap(saved -> {
                            Instant originalExpiry = saved.getExpirationDate();
                            return productService.extendExpirationDate(saved.getId(), p.getId())
                                    .then(productRepository.findById(saved.getId()))
                                    .doOnNext(updated ->
                                            assertThat(updated.getExpirationDate()).isAfterOrEqualTo(originalExpiry)
                                    );
                        })
                );

        StepVerifier.create(result)
                .assertNext(updated -> assertThat(updated.getExpirationDate()).isNotNull())
                .verifyComplete();
    }

    @Test
    void extendExpirationDate_timeExpiredProduct_setsActiveAndExtendsDate() {
        var result = newParticipant()
                .flatMap(p -> createPurchasableProduct(ProductStatus.TIME_EXPIRED, p.getId())
                        .flatMap(saved -> {
                            Instant originalExpiry = saved.getExpirationDate();
                            return productService.extendExpirationDate(saved.getId(), p.getId())
                                    .then(productRepository.findById(saved.getId()))
                                    .doOnNext(updated ->
                                            assertThat(updated.getExpirationDate()).isAfterOrEqualTo(originalExpiry)
                                    );
                        })
                );

        StepVerifier.create(result)
                .assertNext(updated -> assertThat(updated.getStatus()).isEqualTo(ProductStatus.ACTIVE))
                .verifyComplete();
    }

    @Test
    void decrementCountIfSufficient_reducesCountByAmount() {
        var result = newParticipant()
                .flatMap(p -> createPurchasableProduct(ProductStatus.ACTIVE, p.getId())
                        .flatMap(saved ->
                                productService.decrementCountIfSufficient(saved.getId(), 3)
                                        .then(productRepository.findById(saved.getId()))
                        )
                );

        // createPurchasableProduct creates a product with count=10
        StepVerifier.create(result)
                .assertNext(product -> assertThat(product.getCount()).isEqualTo(7))
                .verifyComplete();
    }

    @Test
    void decrementCountIfSufficient_insufficientStock_doesNotChangeCount() {
        var result = newParticipant()
                .flatMap(p -> createPurchasableProduct(ProductStatus.ACTIVE, p.getId())
                        .flatMap(saved ->
                                productService.decrementCountIfSufficient(saved.getId(), 999)
                                        .onErrorResume(e -> Mono.empty())
                                        .then(productRepository.findById(saved.getId()))
                        )
                );

        StepVerifier.create(result)
                .assertNext(product -> assertThat(product.getCount()).isEqualTo(10))
                .verifyComplete();
    }

    // --- helpers ---

    /** Creates a participant with auto-generated ID (no hardcoded IDs). */
    private Mono<Participant> newParticipant() {
        return participantRepository.save(
                Participant.builder()
                        .login("user_" + System.nanoTime())
                        .mail("user_" + System.nanoTime() + "@test.com")
                        .fullName("Test User")
                        .phoneNumber("+79990001122")
                        .status(ParticipantStatus.ACTIVE)
                        .password("password123")
                        .role(ParticipantRole.USER)
                        .deadlineSending(3)
                        .deadlinePayment(7)
                        .sellerStatus(SellerStatus.DEFAULT)
                        .createdAt(Instant.now())
                        .build()
        );
    }

    private CreateOrUpdateProductRequest updateRequest(String name, float price) {
        CreateOrUpdateProductRequest req = new CreateOrUpdateProductRequest();
        req.setName(name);
        req.setPrice(price);
        req.setCurrency(Currency.RUB);
        req.setOriginality("Original");
        req.setAvailability(ProductAvailabilityType.PURCHASABLE);
        req.setCount(5);
        req.setCategoryIds(List.of());
        req.setImageIds(List.of());
        return req;
    }
}
