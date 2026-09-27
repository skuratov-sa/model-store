package com.model_store.service.impl;

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
import com.model_store.model.dto.ProductBasketDto;
import com.model_store.model.page.Pageable;
import com.model_store.service.BasketService;
import com.model_store.service.FavoriteService;
import com.model_store.service.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.r2dbc.core.DatabaseClient;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProductServiceAdultContentTest extends IntegrationTest {

    @Autowired
    private DatabaseClient databaseClient;

    @Autowired
    private BasketService basketService;

    @Autowired
    private FavoriteService favoriteService;

    private Participant adultUser;
    private Participant minorUser;
    private Participant userWithoutAge;
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
                .age(18)
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
                .age(17)
                .createdAt(Instant.now())
                .build()).block();

        userWithoutAge = participantRepository.save(Participant.builder()
                .login("no_age_" + System.nanoTime())
                .mail("no_age_" + System.nanoTime() + "@example.com")
                .fullName("User Without Age")
                .phoneNumber("+70000000003")
                .status(ParticipantStatus.ACTIVE)
                .password("pass")
                .role(ParticipantRole.USER)
                .deadlineSending(3)
                .deadlinePayment(7)
                .sellerStatus(SellerStatus.DEFAULT)
                .createdAt(Instant.now())
                .build()).block();

        normalProduct = saveProduct("Normal Product", adultUser.getId());
        adultProduct = saveProduct("Adult Product", adultUser.getId());
        linkToNsfwCategory(adultProduct.getId());
    }

    @Test
    void findByParams_anonUser_excludesAdultContent() {
        assertOnlyNormalProduct(baseRequest(), null);
    }

    @Test
    void findByParams_underageUser_excludesAdultContent() {
        assertOnlyNormalProduct(baseRequest(), minorUser.getId());
    }

    @Test
    void findByParams_userWithoutAge_excludesAdultContent() {
        assertOnlyNormalProduct(baseRequest(), userWithoutAge.getId());
    }

    @Test
    void findByParams_adultUser_returnsAllProducts() {
        List<Long> ids = productService.findByParams(baseRequest(), adultUser.getId())
                .map(ProductDto::getId).collectList().block();

        assertThat(ids).contains(normalProduct.getId(), adultProduct.getId());
    }

    @Test
    void findByParams_inactiveUser_excludesAdultContent() {
        adultUser.setStatus(ParticipantStatus.BLOCKED);
        participantRepository.save(adultUser).block();

        assertOnlyNormalProduct(baseRequest(), adultUser.getId());
    }

    @Test
    void basketAndFavorites_determineAdultContentFromAge() {
        for (Participant user : List.of(adultUser, minorUser)) {
            basketService.addToBasket(user.getId(), normalProduct.getId(), 1).block();
            basketService.addToBasket(user.getId(), adultProduct.getId(), 1).block();
            favoriteService.addToFavorites(user.getId(), normalProduct.getId()).block();
            favoriteService.addToFavorites(user.getId(), adultProduct.getId()).block();
        }

        List<Long> adultBasketIds = basketService.findBasketProductsByParams(adultUser.getId(), baseRequest())
                .map(ProductBasketDto::getProduct).map(ProductDto::getId).collectList().block();
        List<Long> minorBasketIds = basketService.findBasketProductsByParams(minorUser.getId(), baseRequest())
                .map(ProductBasketDto::getProduct).map(ProductDto::getId).collectList().block();
        List<Long> adultFavoriteIds = favoriteService.findFavoriteByParams(adultUser.getId(), baseRequest())
                .map(ProductDto::getId).collectList().block();
        List<Long> minorFavoriteIds = favoriteService.findFavoriteByParams(minorUser.getId(), baseRequest())
                .map(ProductDto::getId).collectList().block();

        assertThat(adultBasketIds).contains(normalProduct.getId(), adultProduct.getId());
        assertThat(adultFavoriteIds).contains(normalProduct.getId(), adultProduct.getId());
        assertThat(minorBasketIds).contains(normalProduct.getId()).doesNotContain(adultProduct.getId());
        assertThat(minorFavoriteIds).contains(normalProduct.getId()).doesNotContain(adultProduct.getId());
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

    private void assertOnlyNormalProduct(FindProductRequest req, Long participantId) {
        List<Long> ids = productService.findByParams(req, participantId)
                .map(ProductDto::getId).collectList().block();

        assertThat(ids).contains(normalProduct.getId());
        assertThat(ids).doesNotContain(adultProduct.getId());
    }
}
