package com.model_store.service.impl;

import com.model_store.exception.ApiException;
import com.model_store.exception.constant.ErrorCode;
import com.model_store.model.FindProductRequest;
import com.model_store.model.base.Participant;
import com.model_store.model.base.Product;
import com.model_store.model.constant.Currency;
import com.model_store.model.constant.ParticipantRole;
import com.model_store.model.constant.ParticipantStatus;
import com.model_store.model.constant.ProductAvailabilityType;
import com.model_store.model.constant.ProductStatus;
import com.model_store.model.constant.SellerStatus;
import com.model_store.model.constant.SortByType;
import com.model_store.model.dto.ProductDto;
import com.model_store.model.page.Pageable;
import com.model_store.service.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.r2dbc.core.DatabaseClient;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductServiceAdultContentTest extends IntegrationTest {

    @Autowired
    private DatabaseClient databaseClient;

    private Participant adultUser;
    private Participant minorUser;
    private Product normalProduct;
    private Product adultProduct;

    @BeforeEach
    void setUp() {
        databaseClient.sql("TRUNCATE TABLE product_category, product, participant RESTART IDENTITY CASCADE")
                .fetch().rowsUpdated().block();

        adultUser = participantRepository.save(Participant.builder()
                .login("adult_" + System.nanoTime())
                .mail("adult_" + System.nanoTime() + "@example.com")
                .fullName("Adult User")
                .phoneNumber("+70000000001")
                .status(ParticipantStatus.ACTIVE)
                .password("pass")
                .role(ParticipantRole.USER)
                .deadlineSending(3)
                .deadlinePayment(7)
                .sellerStatus(SellerStatus.DEFAULT)
                .age(20)
                .createdAt(Instant.now())
                .build()).block();

        minorUser = participantRepository.save(Participant.builder()
                .login("minor_" + System.nanoTime())
                .mail("minor_" + System.nanoTime() + "@example.com")
                .fullName("Minor User")
                .phoneNumber("+70000000002")
                .status(ParticipantStatus.ACTIVE)
                .password("pass")
                .role(ParticipantRole.USER)
                .deadlineSending(3)
                .deadlinePayment(7)
                .sellerStatus(SellerStatus.DEFAULT)
                .age(15)
                .createdAt(Instant.now())
                .build()).block();

        normalProduct = saveProduct("Normal Product", adultUser.getId());
        adultProduct = saveProduct("Adult Product", adultUser.getId());
        linkToNsfwCategory(adultProduct.getId());
    }

    @Test
    void findByParams_anonUser_includeAdultTrue_throwsForbidden() {
        FindProductRequest req = baseRequest();
        req.setIncludeAdult(true);

        assertThatThrownBy(() -> productService.findByParams(req, null).collectList().block())
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo(ErrorCode.ADULT_CONTENT_RESTRICTED));
    }

    @Test
    void findByParams_underageUser_includeAdultTrue_throwsForbidden() {
        FindProductRequest req = baseRequest();
        req.setIncludeAdult(true);

        assertThatThrownBy(() -> productService.findByParams(req, minorUser.getId()).collectList().block())
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo(ErrorCode.ADULT_CONTENT_RESTRICTED));
    }

    @Test
    void findByParams_adultUser_includeAdultTrue_returnsAllProducts() {
        FindProductRequest req = baseRequest();
        req.setIncludeAdult(true);

        List<Long> ids = productService.findByParams(req, adultUser.getId())
                .map(ProductDto::getId).collectList().block();

        assertThat(ids).contains(normalProduct.getId(), adultProduct.getId());
    }

    @Test
    void findByParams_anonUser_includeAdultFalse_excludesAdultContent() {
        FindProductRequest req = baseRequest();
        req.setIncludeAdult(false);

        List<Long> ids = productService.findByParams(req, null)
                .map(ProductDto::getId).collectList().block();

        assertThat(ids).contains(normalProduct.getId());
        assertThat(ids).doesNotContain(adultProduct.getId());
    }

    @Test
    void findByParams_underageUser_includeAdultFalse_excludesAdultContent() {
        FindProductRequest req = baseRequest();
        req.setIncludeAdult(false);

        List<Long> ids = productService.findByParams(req, minorUser.getId())
                .map(ProductDto::getId).collectList().block();

        assertThat(ids).contains(normalProduct.getId());
        assertThat(ids).doesNotContain(adultProduct.getId());
    }

    // --- helpers ---

    private Product saveProduct(String name, Long participantId) {
        return productRepository.save(Product.builder()
                .name(name)
                .description("desc")
                .price(100f)
                .currency(Currency.RUB)
                .originality("Original")
                .participantId(participantId)
                .status(ProductStatus.ACTIVE)
                .availability(ProductAvailabilityType.PURCHASABLE)
                .count(10)
                .expirationDate(Instant.now().plusSeconds(86400 * 30))
                .createdAt(Instant.now())
                .build()).block();
    }

    private void linkToNsfwCategory(Long productId) {
        Long nsfwId = databaseClient
                .sql("SELECT id FROM category WHERE slug = 'nsfw_adult' LIMIT 1")
                .map(row -> row.get("id", Long.class))
                .one().block();
        if (nsfwId == null) return;
        databaseClient
                .sql("INSERT INTO product_category (product_id, category_id) VALUES (:pid, :cid)")
                .bind("pid", productId)
                .bind("cid", nsfwId)
                .fetch().rowsUpdated().block();
    }

    private FindProductRequest baseRequest() {
        FindProductRequest req = new FindProductRequest();
        req.setPageable(new Pageable(50, null, null, 0L, SortByType.DATE_DESC));
        return req;
    }
}
