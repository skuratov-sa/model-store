package com.model_store.service.impl;

import com.model_store.model.FindProductRequest;
import com.model_store.model.FindMyProductRequest;
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
import com.model_store.model.util.PriceRange;
import com.model_store.service.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.r2dbc.core.DatabaseClient;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProductServiceFilterTest extends IntegrationTest {

    @Autowired
    private DatabaseClient databaseClient;

    private Participant seller;

    @BeforeEach
    void setUp() {
        databaseClient.sql("TRUNCATE TABLE product_category, product, participant RESTART IDENTITY CASCADE")
                .fetch().rowsUpdated().block();

        seller = participantRepository.save(
                Participant.builder()
                        .login("filteruser_" + System.nanoTime())
                        .mail("filter_" + System.nanoTime() + "@example.com")
                        .fullName("Filter User")
                        .phoneNumber("+70000000001")
                        .status(ParticipantStatus.ACTIVE)
                        .password("pass")
                        .role(ParticipantRole.USER)
                        .deadlineSending(3)
                        .deadlinePayment(7)
                        .sellerStatus(SellerStatus.DEFAULT)
                        .createdAt(Instant.now())
                        .build()
        ).block();
    }

    @Test
    void findByParams_filterByName_returnsOnlyMatchingProducts() {
        Product anime = saveProduct("Anime Figure", 100f);
        saveProduct("Model Kit", 200f);

        FindProductRequest req = baseRequest();
        req.setName("Anime");

        List<Long> ids = productService.findByParams(req, null).map(ProductDto::getId).collectList().block();

        assertThat(ids).containsExactly(anime.getId());
    }

    @Test
    void findByParams_filterByName_isCaseInsensitive() {
        Product p = saveProduct("Gundam Wing", 300f);
        saveProduct("Nendoroid", 150f);

        FindProductRequest req = baseRequest();
        req.setName("gundam");

        List<Long> ids = productService.findByParams(req, null).map(ProductDto::getId).collectList().block();

        assertThat(ids).contains(p.getId());
        assertThat(ids).hasSize(1);
    }

    @Test
    void findByParams_filterByParticipantId_returnsOnlyTheirProducts() {
        Participant other = participantRepository.save(
                Participant.builder()
                        .login("other_" + System.nanoTime())
                        .mail("other_" + System.nanoTime() + "@example.com")
                        .fullName("Other User")
                        .phoneNumber("+70000000002")
                        .status(ParticipantStatus.ACTIVE)
                        .password("pass")
                        .role(ParticipantRole.USER)
                        .deadlineSending(3)
                        .deadlinePayment(7)
                        .sellerStatus(SellerStatus.DEFAULT)
                        .createdAt(Instant.now())
                        .build()
        ).block();

        Product mine = saveProduct("My Product", 100f);
        Product theirs = saveProductForParticipant("Their Product", 200f, other.getId());

        FindProductRequest req = baseRequest();
        req.setParticipantId(seller.getId());

        List<Long> ids = productService.findByParams(req, null).map(ProductDto::getId).collectList().block();

        assertThat(ids).contains(mine.getId());
        assertThat(ids).doesNotContain(theirs.getId());
    }

    @Test
    void findByParams_filterByMinPrice_excludesProductsBelowThreshold() {
        saveProduct("Cheap", 50f);
        saveProduct("Mid", 200f);
        saveProduct("Expensive", 500f);

        FindProductRequest req = baseRequest();
        PriceRange priceRange = new PriceRange();
        priceRange.setMinPrice(150);
        req.setPriceRange(priceRange);

        List<Float> prices = productService.findByParams(req, null).map(ProductDto::getPrice).collectList().block();

        assertThat(prices).allMatch(p -> p >= 150f);
        assertThat(prices).doesNotContain(50f);
    }

    @Test
    void findByParams_filterByMaxPrice_excludesProductsAboveThreshold() {
        saveProduct("Cheap", 50f);
        saveProduct("Mid", 200f);
        saveProduct("Expensive", 500f);

        FindProductRequest req = baseRequest();
        PriceRange priceRange = new PriceRange();
        priceRange.setMaxPrice(300);
        req.setPriceRange(priceRange);

        List<Float> prices = productService.findByParams(req, null).map(ProductDto::getPrice).collectList().block();

        assertThat(prices).allMatch(p -> p <= 300f);
        assertThat(prices).doesNotContain(500f);
    }

    @Test
    void findByParams_filterByPriceRange_returnsOnlyProductsInRange() {
        saveProduct("P1", 50f);
        saveProduct("P2", 200f);
        saveProduct("P3", 500f);

        FindProductRequest req = baseRequest();
        PriceRange priceRange = new PriceRange();
        priceRange.setMinPrice(100);
        priceRange.setMaxPrice(300);
        req.setPriceRange(priceRange);

        List<Float> prices = productService.findByParams(req, null).map(ProductDto::getPrice).collectList().block();

        assertThat(prices).allMatch(p -> p >= 100f && p <= 300f);
        assertThat(prices).contains(200f);
        assertThat(prices).doesNotContain(50f, 500f);
    }

    @Test
    void findByParams_filterByOriginality_returnsOnlyMatching() {
        Product original = saveProductWithOriginality("Original Item", 100f, "Original");
        saveProductWithOriginality("Copy Item", 200f, "Copy");

        FindProductRequest req = baseRequest();
        req.setOriginality("Original");

        List<Long> ids = productService.findByParams(req, null).map(ProductDto::getId).collectList().block();

        assertThat(ids).containsExactly(original.getId());
    }

    @Test
    void findByParams_noFilters_returnsAllActiveProducts() {
        saveProduct("P1", 100f);
        saveProduct("P2", 200f);
        saveProduct("P3", 300f);
        saveProductWithStatus("Deleted", 400f, ProductStatus.DELETED);

        List<Long> ids = productService.findByParams(baseRequest(), null).map(ProductDto::getId).collectList().block();

        assertThat(ids).hasSize(3);
    }

    @Test
    void findByParams_emptyNameFilter_returnsAll() {
        saveProduct("P1", 100f);
        saveProduct("P2", 200f);

        FindProductRequest req = baseRequest();
        req.setName(null);

        List<Long> ids = productService.findByParams(req, null).map(ProductDto::getId).collectList().block();

        assertThat(ids).hasSize(2);
    }

    @Test
    void findByParams_publicSearchIncludesUnlimitedAndExcludesEmptyOrExpired() {
        Product unlimited = saveProductWithStatusAndCount("Unlimited", 100f, ProductStatus.ACTIVE, null);
        Product empty = saveProductWithStatusAndCount("Empty", 100f, ProductStatus.ACTIVE, 0);
        Product expired = saveProductWithStatusAndCount("Expired", 100f, ProductStatus.TIME_EXPIRED, 10);

        List<Long> ids = productService.findByParams(baseRequest(), null).map(ProductDto::getId).collectList().block();

        assertThat(ids).contains(unlimited.getId());
        assertThat(ids).doesNotContain(empty.getId(), expired.getId());
    }

    @Test
    void findMyByParams_includesEmptyAndTimeExpiredProducts() {
        Product empty = saveProductWithStatusAndCount("Empty", 100f, ProductStatus.ACTIVE, 0);
        Product expired = saveProductWithStatusAndCount("Expired", 100f, ProductStatus.TIME_EXPIRED, 10);

        List<Long> ids = productService.findMyByParams(myRequest(), seller.getId()).map(ProductDto::getId).collectList().block();

        assertThat(ids).contains(empty.getId(), expired.getId());
    }

    @Test
    void findNamesBySearch_filtersUnavailableProductNamesButIncludesUnlimited() {
        saveProductWithStatusAndCount("Visible Unlimited Search Name", 100f, ProductStatus.ACTIVE, null);
        saveProductWithStatusAndCount("Hidden Empty Search Name", 100f, ProductStatus.ACTIVE, 0);
        saveProductWithStatusAndCount("Hidden Expired Search Name", 100f, ProductStatus.TIME_EXPIRED, 10);

        List<String> names = productService.findNamesBySearch("Search Name").collectList().block();

        assertThat(names).contains("Visible Unlimited Search Name");
        assertThat(names).doesNotContain("Hidden Empty Search Name", "Hidden Expired Search Name");
    }

    // --- helpers ---

    private Product saveProduct(String name, float price) {
        return saveProductWithOriginality(name, price, "Original");
    }

    private Product saveProductWithOriginality(String name, float price, String originality) {
        return productRepository.save(
                Product.builder()
                        .name(name)
                        .description("desc")
                        .price(price)
                        .currency(Currency.RUB)
                        .originality(originality)
                        .participantId(seller.getId())
                        .status(ProductStatus.ACTIVE)
                        .availability(ProductAvailabilityType.PURCHASABLE)
                        .count(10)
                        .expirationDate(Instant.now().plusSeconds(86400 * 30))
                        .createdAt(Instant.now())
                        .build()
        ).block();
    }

    private Product saveProductForParticipant(String name, float price, Long participantId) {
        return productRepository.save(
                Product.builder()
                        .name(name)
                        .description("desc")
                        .price(price)
                        .currency(Currency.RUB)
                        .originality("Original")
                        .participantId(participantId)
                        .status(ProductStatus.ACTIVE)
                        .availability(ProductAvailabilityType.PURCHASABLE)
                        .count(10)
                        .expirationDate(Instant.now().plusSeconds(86400 * 30))
                        .createdAt(Instant.now())
                        .build()
        ).block();
    }

    private Product saveProductWithStatus(String name, float price, ProductStatus status) {
        return saveProductWithStatusAndCount(name, price, status, 10);
    }

    private Product saveProductWithStatusAndCount(String name, float price, ProductStatus status, Integer count) {
        return productRepository.save(
                Product.builder()
                        .name(name)
                        .description("desc")
                        .price(price)
                        .currency(Currency.RUB)
                        .originality("Original")
                        .participantId(seller.getId())
                        .status(status)
                        .availability(ProductAvailabilityType.PURCHASABLE)
                        .count(count)
                        .expirationDate(Instant.now().plusSeconds(86400 * 30))
                        .createdAt(Instant.now())
                        .build()
        ).block();
    }

    private FindProductRequest baseRequest() {
        FindProductRequest req = new FindProductRequest();
        req.setPageable(new Pageable(50, null, null, 0L, SortByType.DATE_DESC));
        req.setIncludeAdult(false);
        return req;
    }

    private FindMyProductRequest myRequest() {
        FindMyProductRequest req = new FindMyProductRequest();
        req.setPageable(new Pageable(50, null, null, 0L, SortByType.DATE_DESC));
        return req;
    }
}
