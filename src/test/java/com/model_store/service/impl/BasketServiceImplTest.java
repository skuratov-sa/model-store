package com.model_store.service.impl;

import com.model_store.exception.ApiException;
import com.model_store.exception.constant.ErrorCode;
import com.model_store.model.base.Participant;
import com.model_store.model.base.Product;
import com.model_store.model.constant.Currency;
import com.model_store.model.constant.ParticipantRole;
import com.model_store.model.constant.ParticipantStatus;
import com.model_store.model.constant.ProductAvailabilityType;
import com.model_store.model.constant.ProductStatus;
import com.model_store.model.constant.SellerStatus;
import com.model_store.repository.ProductBasketRepository;
import com.model_store.service.BasketService;
import com.model_store.service.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.test.StepVerifier;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class BasketServiceImplTest extends IntegrationTest {

    private static final String PRODUCT_NOT_PURCHASABLE_MESSAGE =
            "Товар доступен только на внешнем сайте и не может быть добавлен в корзину";

    @Autowired
    private BasketService basketService;

    @Autowired
    private ProductBasketRepository productBasketRepository;

    @Autowired
    private DatabaseClient databaseClient;

    private Participant participant;

    @BeforeEach
    void setUp() {
        databaseClient.sql("""
                TRUNCATE TABLE product_basket, product, participant
                RESTART IDENTITY CASCADE
                """).fetch().rowsUpdated().block();

        participant = participantRepository.save(newParticipant()).block();
    }

    @Test
    void addToBasket_purchasableProduct_savesBasketItemWithCount() {
        Product product = saveProduct(ProductAvailabilityType.PURCHASABLE, 10);

        StepVerifier.create(basketService.addToBasket(participant.getId(), product.getId(), 3))
                .verifyComplete();

        StepVerifier.create(productBasketRepository.findByParticipantIdAndProductId(participant.getId(), product.getId()))
                .assertNext(item -> {
                    assertThat(item.getParticipantId()).isEqualTo(participant.getId());
                    assertThat(item.getProductId()).isEqualTo(product.getId());
                    assertThat(item.getCount()).isEqualTo(3);
                })
                .verifyComplete();
    }

    @Test
    void addToBasket_preorderProduct_savesBasketItem() {
        Product product = saveProduct(ProductAvailabilityType.PREORDER, null);

        StepVerifier.create(basketService.addToBasket(participant.getId(), product.getId(), 2))
                .verifyComplete();

        StepVerifier.create(productBasketRepository.findByParticipantIdAndProductId(participant.getId(), product.getId()))
                .assertNext(item -> assertThat(item.getCount()).isEqualTo(2))
                .verifyComplete();
    }

    @Test
    void addToBasket_externalOnlyProduct_returnsUserErrorAndDoesNotSaveBasketItem() {
        Product product = saveProduct(ProductAvailabilityType.EXTERNAL_ONLY, null);

        StepVerifier.create(basketService.addToBasket(participant.getId(), product.getId(), 1))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(ApiException.class);
                    ApiException apiException = (ApiException) error;
                    assertThat(apiException.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(apiException.getCode()).isEqualTo(ErrorCode.PRODUCT_NOT_PURCHASABLE);
                    assertThat(apiException.getMessage()).isEqualTo(PRODUCT_NOT_PURCHASABLE_MESSAGE);
                })
                .verify();

        StepVerifier.create(productBasketRepository.findByParticipantIdAndProductId(participant.getId(), product.getId()))
                .verifyComplete();
    }

    @Test
    void addToBasket_invalidCount_returnsExistingCountInvalidError() {
        Product product = saveProduct(ProductAvailabilityType.PURCHASABLE, 10);

        StepVerifier.create(basketService.addToBasket(participant.getId(), product.getId(), 0))
                .expectErrorSatisfies(error -> assertApiException(error, HttpStatus.BAD_REQUEST, ErrorCode.COUNT_INVALID))
                .verify();
    }

    @Test
    void addToBasket_insufficientStock_returnsExistingStockError() {
        Product product = saveProduct(ProductAvailabilityType.PURCHASABLE, 1);

        StepVerifier.create(basketService.addToBasket(participant.getId(), product.getId(), 2))
                .expectErrorSatisfies(error -> assertApiException(error, HttpStatus.BAD_REQUEST, ErrorCode.NOT_ENOUGH_STOCK))
                .verify();
    }

    @Test
    void addToBasket_existingBasketItem_returnsExistingDuplicateError() {
        Product product = saveProduct(ProductAvailabilityType.PURCHASABLE, 10);
        basketService.addToBasket(participant.getId(), product.getId(), 1).block();

        StepVerifier.create(basketService.addToBasket(participant.getId(), product.getId(), 1))
                .expectErrorSatisfies(error -> assertApiException(error, HttpStatus.CONFLICT, ErrorCode.PRODUCT_ALREADY_IN_BASKET))
                .verify();
    }

    @Test
    void updateCount_externalOnlyProduct_returnsUserError() {
        Product product = saveProduct(ProductAvailabilityType.EXTERNAL_ONLY, null);

        StepVerifier.create(basketService.updateCount(participant.getId(), product.getId(), 2))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(ApiException.class);
                    ApiException apiException = (ApiException) error;
                    assertThat(apiException.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(apiException.getCode()).isEqualTo(ErrorCode.PRODUCT_NOT_PURCHASABLE);
                    assertThat(apiException.getMessage()).isEqualTo(PRODUCT_NOT_PURCHASABLE_MESSAGE);
                })
                .verify();
    }

    private void assertApiException(Throwable error, HttpStatus status, ErrorCode code) {
        assertThat(error).isInstanceOf(ApiException.class);
        ApiException apiException = (ApiException) error;
        assertThat(apiException.getStatus()).isEqualTo(status);
        assertThat(apiException.getCode()).isEqualTo(code);
    }

    private Product saveProduct(ProductAvailabilityType availability, Integer count) {
        Product.ProductBuilder builder = Product.builder()
                .name("Basket Test Product")
                .description("Description for basket test product")
                .price(1500f)
                .count(count)
                .currency(Currency.RUB)
                .originality("Original")
                .participantId(participant.getId())
                .status(ProductStatus.ACTIVE)
                .expirationDate(Instant.now().plusSeconds(3600 * 24 * 30))
                .availability(availability)
                .createdAt(Instant.now());

        if (ProductAvailabilityType.PREORDER.equals(availability)) {
            builder.prepaymentAmount(500f);
        }

        if (ProductAvailabilityType.EXTERNAL_ONLY.equals(availability)) {
            builder.externalUrl("https://example.com");
        }

        return productRepository.save(builder.build()).block();
    }

    private Participant newParticipant() {
        long nano = System.nanoTime();
        return Participant.builder()
                .login("basket_user_" + nano)
                .mail("basket_user_" + nano + "@example.com")
                .fullName("Basket Test User")
                .phoneNumber("+79990001122")
                .status(ParticipantStatus.ACTIVE)
                .password("password123")
                .role(ParticipantRole.USER)
                .deadlineSending(3)
                .deadlinePayment(7)
                .sellerStatus(SellerStatus.DEFAULT)
                .age(25)
                .createdAt(Instant.now())
                .build();
    }
}
