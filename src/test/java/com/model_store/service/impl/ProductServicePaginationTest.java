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
import com.model_store.model.page.Pageable;
import com.model_store.service.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.r2dbc.core.DatabaseClient;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProductServicePaginationTest extends IntegrationTest {

    @Autowired
    private DatabaseClient databaseClient;

    private Participant participant;

    @BeforeEach
    void setUp() {
        databaseClient.sql("TRUNCATE TABLE product_category, product, participant RESTART IDENTITY CASCADE")
                .fetch().rowsUpdated().block();

        participant = participantRepository.save(
                Participant.builder()
                        .login("paginationuser")
                        .mail("pagination@example.com")
                        .fullName("Pagination User")
                        .phoneNumber("+70000000000")
                        .status(ParticipantStatus.ACTIVE)
                        .password("pass")
                        .role(ParticipantRole.USER)
                        .deadlineSending(3)
                        .deadlinePayment(7)
                        .sellerStatus(SellerStatus.DEFAULT)
                        .age(20)
                        .createdAt(Instant.now())
                        .build()
        ).block();
    }

    // --- DATE_DESC pagination ---

    @Test
    void dateDesc_firstPage_returnsNewestFirst() {
        Instant base = Instant.now().minusSeconds(300);
        Product p1 = saveProduct("P1", 100f, base);
        Product p2 = saveProduct("P2", 200f, base.plusSeconds(60));
        Product p3 = saveProduct("P3", 150f, base.plusSeconds(120));
        Product p4 = saveProduct("P4", 250f, base.plusSeconds(180));
        Product p5 = saveProduct("P5", 300f, base.plusSeconds(240));

        List<Long> ids = findByParams(3, null, null, 0L, SortByType.DATE_DESC);

        assertThat(ids).hasSize(3);
        assertThat(ids.get(0)).isEqualTo(p5.getId());
        assertThat(ids.get(1)).isEqualTo(p4.getId());
        assertThat(ids.get(2)).isEqualTo(p3.getId());
    }

    @Test
    void dateDesc_secondPage_continuesWithoutDuplicates() {
        Instant base = Instant.now().minusSeconds(300);
        Product p1 = saveProduct("P1", 100f, base);
        Product p2 = saveProduct("P2", 200f, base.plusSeconds(60));
        Product p3 = saveProduct("P3", 150f, base.plusSeconds(120));
        Product p4 = saveProduct("P4", 250f, base.plusSeconds(180));
        Product p5 = saveProduct("P5", 300f, base.plusSeconds(240));

        List<ProductDto> page1 = productService.findByParams(request(3, null, null, 0L, SortByType.DATE_DESC), null)
                .collectList().block();
        assertThat(page1).hasSize(3);

        ProductDto cursor = page1.get(2);
        List<Long> page2Ids = findByParams(3, cursor.getCreatedAt(), null, cursor.getId(), SortByType.DATE_DESC);

        assertThat(page2Ids).hasSize(2);
        assertThat(page2Ids).doesNotContainAnyElementsOf(page1.stream().map(ProductDto::getId).toList());
        assertThat(page2Ids.get(0)).isEqualTo(p2.getId());
        assertThat(page2Ids.get(1)).isEqualTo(p1.getId());
    }

    @Test
    void dateDesc_lastPage_returnsEmptyFlux() {
        Instant base = Instant.now().minusSeconds(300);
        Product p1 = saveProduct("P1", 100f, base);

        List<ProductDto> page1 = productService.findByParams(request(5, null, null, 0L, SortByType.DATE_DESC), null)
                .collectList().block();
        assertThat(page1).hasSize(1);

        ProductDto cursor = page1.get(0);
        List<Long> page2Ids = findByParams(5, cursor.getCreatedAt(), null, cursor.getId(), SortByType.DATE_DESC);

        assertThat(page2Ids).isEmpty();
    }

    @Test
    void dateDesc_sameCreatedAt_usesIdForStableOrdering() {
        Instant sameTime = Instant.now().minusSeconds(100);
        Product p1 = saveProduct("P1", 100f, sameTime);
        Product p2 = saveProduct("P2", 200f, sameTime);
        Product p3 = saveProduct("P3", 300f, sameTime);

        List<ProductDto> page1 = productService.findByParams(request(2, null, null, 0L, SortByType.DATE_DESC), null)
                .collectList().block();
        assertThat(page1).hasSize(2);

        ProductDto cursor = page1.get(1);
        List<Long> page2Ids = findByParams(2, cursor.getCreatedAt(), null, cursor.getId(), SortByType.DATE_DESC);

        assertThat(page2Ids).hasSize(1);
        assertThat(page2Ids).doesNotContainAnyElementsOf(page1.stream().map(ProductDto::getId).toList());
    }

    // --- PRICE_ASC pagination ---

    @Test
    void priceAsc_firstPage_returnsCheapestFirst() {
        Instant base = Instant.now().minusSeconds(300);
        saveProduct("P1", 500f, base);
        saveProduct("P2", 100f, base.plusSeconds(1));
        saveProduct("P3", 300f, base.plusSeconds(2));
        saveProduct("P4", 200f, base.plusSeconds(3));
        saveProduct("P5", 400f, base.plusSeconds(4));

        List<ProductDto> page1 = productService.findByParams(request(5, null, null, 0L, SortByType.PRICE_ASC), null)
                .collectList().block();

        assertThat(page1).hasSize(5);
        List<Float> prices = page1.stream().map(ProductDto::getPrice).toList();
        assertThat(prices).containsExactly(100f, 200f, 300f, 400f, 500f);
    }

    @Test
    void priceAsc_secondPage_continuesWithoutDuplicates() {
        Instant base = Instant.now().minusSeconds(300);
        saveProduct("P1", 500f, base);
        saveProduct("P2", 100f, base.plusSeconds(1));
        saveProduct("P3", 300f, base.plusSeconds(2));
        saveProduct("P4", 200f, base.plusSeconds(3));
        saveProduct("P5", 400f, base.plusSeconds(4));

        List<ProductDto> page1 = productService.findByParams(request(3, null, null, 0L, SortByType.PRICE_ASC), null)
                .collectList().block();
        assertThat(page1).hasSize(3);

        ProductDto cursor = page1.get(2);
        List<Long> page2Ids = findByParams(3, null, cursor.getPrice(), cursor.getId(), SortByType.PRICE_ASC);

        assertThat(page2Ids).hasSize(2);
        assertThat(page2Ids).doesNotContainAnyElementsOf(page1.stream().map(ProductDto::getId).toList());
    }

    // --- PRICE_DESC pagination ---

    @Test
    void priceDesc_firstPage_returnsMostExpensiveFirst() {
        Instant base = Instant.now().minusSeconds(300);
        saveProduct("P1", 100f, base);
        saveProduct("P2", 500f, base.plusSeconds(1));
        saveProduct("P3", 300f, base.plusSeconds(2));
        saveProduct("P4", 200f, base.plusSeconds(3));
        saveProduct("P5", 400f, base.plusSeconds(4));

        List<ProductDto> page1 = productService.findByParams(request(5, null, null, 0L, SortByType.PRICE_DESC), null)
                .collectList().block();

        assertThat(page1).hasSize(5);
        List<Float> prices = page1.stream().map(ProductDto::getPrice).toList();
        assertThat(prices).containsExactly(500f, 400f, 300f, 200f, 100f);
    }

    @Test
    void priceDesc_secondPage_continuesWithoutDuplicates() {
        Instant base = Instant.now().minusSeconds(300);
        saveProduct("P1", 100f, base);
        saveProduct("P2", 500f, base.plusSeconds(1));
        saveProduct("P3", 300f, base.plusSeconds(2));
        saveProduct("P4", 200f, base.plusSeconds(3));
        saveProduct("P5", 400f, base.plusSeconds(4));

        List<ProductDto> page1 = productService.findByParams(request(3, null, null, 0L, SortByType.PRICE_DESC), null)
                .collectList().block();
        assertThat(page1).hasSize(3);

        ProductDto cursor = page1.get(2);
        List<Long> page2Ids = findByParams(3, null, cursor.getPrice(), cursor.getId(), SortByType.PRICE_DESC);

        assertThat(page2Ids).hasSize(2);
        assertThat(page2Ids).doesNotContainAnyElementsOf(page1.stream().map(ProductDto::getId).toList());
    }

    // --- filtering ---

    @Test
    void findByParams_excludesDeletedProducts() {
        Instant base = Instant.now().minusSeconds(60);
        Product active = saveProduct("Active", 100f, base);
        Product deleted = saveProductWithStatus("Deleted", 200f, base.plusSeconds(1), ProductStatus.DELETED);

        List<Long> ids = findByParams(10, null, null, 0L, SortByType.DATE_DESC);

        assertThat(ids).contains(active.getId());
        assertThat(ids).doesNotContain(deleted.getId());
    }

    @Test
    void findByParams_excludesAdultProductsByDefault() {
        Instant base = Instant.now().minusSeconds(60);
        Product normal = saveProduct("Normal", 100f, base);
        Product nsfw = saveProduct("NSFW", 200f, base.plusSeconds(1));
        linkToNsfwCategory(nsfw.getId());

        FindProductRequest req = request(10, null, null, 0L, SortByType.DATE_DESC);
        req.setIncludeAdult(false);

        List<Long> ids = productService.findByParams(req, null).map(ProductDto::getId).collectList().block();

        assertThat(ids).contains(normal.getId());
        assertThat(ids).doesNotContain(nsfw.getId());
    }

    @Test
    void findByParams_includesAdultProductsWhenFlagTrue() {
        Instant base = Instant.now().minusSeconds(60);
        Product normal = saveProduct("Normal", 100f, base);
        Product nsfw = saveProduct("NSFW", 200f, base.plusSeconds(1));
        linkToNsfwCategory(nsfw.getId());

        FindProductRequest req = request(10, null, null, 0L, SortByType.DATE_DESC);
        req.setIncludeAdult(true);

        List<Long> ids = productService.findByParams(req, participant.getId()).map(ProductDto::getId).collectList().block();

        assertThat(ids).contains(normal.getId());
        assertThat(ids).contains(nsfw.getId());
    }

    // --- helpers ---

    private Product saveProduct(String name, float price, Instant createdAt) {
        return saveProductWithStatus(name, price, createdAt, ProductStatus.ACTIVE);
    }

    private Product saveProductWithStatus(String name, float price, Instant createdAt, ProductStatus status) {
        return productRepository.save(
                Product.builder()
                        .name(name)
                        .description("desc")
                        .price(price)
                        .currency(Currency.RUB)
                        .originality("Original")
                        .participantId(participant.getId())
                        .status(status)
                        .availability(ProductAvailabilityType.PURCHASABLE)
                        .count(10)
                        .expirationDate(Instant.now().plusSeconds(86400 * 30))
                        .createdAt(createdAt)
                        .build()
        ).block();
    }

    private void linkToNsfwCategory(Long productId) {
        Long nsfwId = databaseClient
                .sql("SELECT id FROM category WHERE slug = 'nsfw_adult' LIMIT 1")
                .map(row -> row.get("id", Long.class))
                .one()
                .block();
        if (nsfwId == null) return;

        databaseClient
                .sql("INSERT INTO product_category (product_id, category_id) VALUES (:pid, :cid)")
                .bind("pid", productId)
                .bind("cid", nsfwId)
                .fetch().rowsUpdated().block();
    }

    private FindProductRequest request(int size, Instant lastCreatedAt, Float lastPrice, Long lastId, SortByType sortBy) {
        FindProductRequest req = new FindProductRequest();
        req.setPageable(new Pageable(size, lastCreatedAt, lastPrice, lastId, sortBy));
        req.setIncludeAdult(false);
        return req;
    }

    private List<Long> findByParams(int size, Instant lastCreatedAt, Float lastPrice, Long lastId, SortByType sortBy) {
        return productService.findByParams(request(size, lastCreatedAt, lastPrice, lastId, sortBy), null)
                .map(ProductDto::getId)
                .collectList()
                .block();
    }
}
