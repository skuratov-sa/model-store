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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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

    // --- helpers ---

    @ParameterizedTest
    @EnumSource(value = SortByType.class, names = {"DATE_ASC", "DATE_DESC", "PRICE_ASC", "PRICE_DESC"})
    void equalKeys_allPagesHaveNoDuplicatesOrGaps(SortByType sort) {
        Instant time = Instant.parse("2026-01-01T12:00:00Z");
        Product first = saveProduct("First", 100f, time);
        Product second = saveProduct("Second", 100f, time);
        Product third = saveProduct("Third", 100f, time);

        assertThat(allPages(sort)).containsExactly(third.getId(), second.getId(), first.getId());
    }

    @Test
    void dateAsc_returnsOldestFirstAcrossPages() {
        Instant time = Instant.parse("2026-01-01T12:00:00Z");
        Product newest = saveProduct("Newest", 100f, time.plusSeconds(2));
        Product oldest = saveProduct("Oldest", 100f, time);
        Product middle = saveProduct("Middle", 100f, time.plusSeconds(1));
        assertThat(allPages(SortByType.DATE_ASC))
                .containsExactly(oldest.getId(), middle.getId(), newest.getId());
    }

    @ParameterizedTest
    @EnumSource(value = SortByType.class, names = {"SELLER_RATING_ASC", "SELLER_RATING_DESC"})
    void sellerRating_unratedLastWithStableFilteredPagination(SortByType sort) {
        Instant time = Instant.parse("2026-01-01T12:00:00Z");
        Product unrated1 = saveProduct("Selected unrated 1", 100f, time);
        Product unrated2 = saveProduct("Selected unrated 2", 100f, time);
        // A row with no reviews and the historical zero rating is also unrated.
        Participant zeroSeller = ratedSeller("zero", 0f, 0);
        Product zeroRated = saveForSeller("Selected zero", zeroSeller, time);
        Participant lowSeller = ratedSeller("low", 3.33f, 3);
        Participant highSeller = ratedSeller("high", 4.67f, 3);
        Product low = saveForSeller("Selected low", lowSeller, time);
        Product high1 = saveForSeller("Selected high 1", highSeller, time);
        Product high2 = saveForSeller("Selected high 2", highSeller, time);
        saveForSeller("Excluded by name", highSeller, time);

        List<Long> expected = sort == SortByType.SELLER_RATING_ASC
                ? List.of(low.getId(), high2.getId(), high1.getId(), zeroRated.getId(), unrated2.getId(), unrated1.getId())
                : List.of(high2.getId(), high1.getId(), low.getId(), zeroRated.getId(), unrated2.getId(), unrated1.getId());
        assertThat(allPages(sort)).containsExactlyElementsOf(expected);
    }

    private List<Long> allPages(SortByType sort) {
        java.util.ArrayList<Long> ids = new java.util.ArrayList<>();
        ProductDto cursor = null;
        for (int page = 0; page < 20; page++) {
            FindProductRequest request = request(1, cursor == null ? null : cursor.getCreatedAt(),
                    cursor == null ? null : cursor.getPrice(), cursor == null ? 0L : cursor.getId(), sort);
            request.setName(sort.name().startsWith("SELLER_RATING") ? "Selected" : null);
            request.getPageable().setLastSellerRating(cursor == null ? null : cursor.getSellerRating());
            List<ProductDto> products = productService.findByParams(request, null).collectList().block();
            if (products.isEmpty()) return ids;
            cursor = products.getFirst();
            ids.add(cursor.getId());
        }
        throw new AssertionError("Pagination did not terminate: " + ids);
    }

    private Participant ratedSeller(String suffix, float rating, int count) {
        Participant seller = Participant.builder()
                .login("rated_" + suffix).mail(suffix + "@rating.test").fullName(suffix)
                .password("pass").role(ParticipantRole.USER).status(ParticipantStatus.ACTIVE)
                .deadlineSending(3).deadlinePayment(7).isAgent(false)
                .sellerStatus(SellerStatus.DEFAULT).age(25).createdAt(Instant.now()).build();
        seller = participantRepository.save(seller).block();
        databaseClient.sql("INSERT INTO seller_rating(seller_id, average_rating, total_reviews) VALUES (:id, :rating, :count)")
                .bind("id", seller.getId()).bind("rating", rating).bind("count", count)
                .fetch().rowsUpdated().block();
        return seller;
    }

    private Product saveForSeller(String name, Participant seller, Instant time) {
        Product product = saveProduct(name, 100f, time);
        product.setParticipantId(seller.getId());
        return productRepository.save(product).block();
    }

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

    private FindProductRequest request(int size, Instant lastCreatedAt, Float lastPrice, Long lastId, SortByType sortBy) {
        FindProductRequest req = new FindProductRequest();
        req.setPageable(new Pageable(size, lastCreatedAt, lastPrice, lastId, sortBy));
        return req;
    }

    private List<Long> findByParams(int size, Instant lastCreatedAt, Float lastPrice, Long lastId, SortByType sortBy) {
        return productService.findByParams(request(size, lastCreatedAt, lastPrice, lastId, sortBy), null)
                .map(ProductDto::getId)
                .collectList()
                .block();
    }
}
