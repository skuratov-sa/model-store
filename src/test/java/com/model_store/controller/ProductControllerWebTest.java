package com.model_store.controller;

import com.model_store.model.CustomUserDetails;
import com.model_store.model.FindProductRequest;
import com.model_store.model.CreateOrUpdateProductRequest;
import com.model_store.model.GiveawaySettingsRequest;
import com.model_store.model.base.Participant;
import com.model_store.model.base.Dictionary;
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
import com.model_store.service.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.http.MediaType;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@AutoConfigureWebTestClient
class ProductControllerWebTest extends IntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private DatabaseClient databaseClient;

    private String userToken;
    private String adminToken;

    @BeforeEach
    void setUp() {
        databaseClient.sql("TRUNCATE TABLE product_category, product, participant RESTART IDENTITY CASCADE")
                .fetch().rowsUpdated().block();

        Participant participant = participantRepository.save(
                Participant.builder()
                        .login("webtest_" + System.nanoTime())
                        .mail("webtest_" + System.nanoTime() + "@example.com")
                        .fullName("Web Test User")
                        .phoneNumber("+70000000099")
                        .status(ParticipantStatus.ACTIVE)
                        .password("pass")
                        .role(ParticipantRole.USER)
                        .deadlineSending(3)
                        .deadlinePayment(7)
                        .sellerStatus(SellerStatus.DEFAULT)
                        .createdAt(Instant.now())
                        .build()
        ).block();

        CustomUserDetails userDetails = CustomUserDetails.builder()
                .id(participant.getId())
                .login(participant.getLogin())
                .email(participant.getMail())
                .fullName(participant.getFullName())
                .role(ParticipantRole.USER.name())
                .password("pass")
                .status(ParticipantStatus.ACTIVE)
                .build();

        CustomUserDetails adminDetails = CustomUserDetails.builder()
                .id(participant.getId())
                .login(participant.getLogin())
                .email(participant.getMail())
                .fullName(participant.getFullName())
                .role(ParticipantRole.ADMIN.name())
                .password("pass")
                .status(ParticipantStatus.ACTIVE)
                .build();

        userToken = "Bearer " + jwtService.generateAccessToken(userDetails, Duration.ofMinutes(30));
        adminToken = "Bearer " + jwtService.generateAccessToken(adminDetails, Duration.ofMinutes(30));
    }

    // --- public endpoints ---

    @Test
    void getProduct_nonExistentId_returns404() {
        webTestClient.get()
                .uri("/product/999999")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void giveawayRoutesAreSeparateAndAdminHistoryIsProtected() {
        CreateOrUpdateProductRequest request = new CreateOrUpdateProductRequest();
        request.setName("Figure Giveaway");
        request.setPrice(100f);
        request.setCurrency(Currency.RUB);
        request.setAvailability(ProductAvailabilityType.GIVEAWAY);
        GiveawaySettingsRequest giveaway = new GiveawaySettingsRequest();
        giveaway.setEnabled(true);
        giveaway.setTelegramUrl("https://t.me/figure_draw");
        giveaway.setStartAt(Instant.now().minusSeconds(60));
        giveaway.setEndAt(Instant.now().plusSeconds(3600));
        giveaway.setWinnersCount(1);
        giveaway.setRules("Join Telegram");
        giveaway.setHomeText("Draw a figure");
        request.setGiveaway(giveaway);

        webTestClient.post().uri("/products").header("Authorization", userToken)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(request)
                .exchange().expectStatus().isForbidden();

        Long id = webTestClient.post().uri("/products").header("Authorization", adminToken)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(request)
                .exchange().expectStatus().isOk().expectBody(Long.class).returnResult().getResponseBody();
        assertThat(id).isNotNull();

        webTestClient.get().uri("/product/{id}", id).exchange().expectStatus().isNotFound();
        webTestClient.get().uri("/giveaways/active").exchange()
                .expectStatus().isOk().expectHeader().valueEquals("X-Robots-Tag", "noindex, nofollow")
                .expectBody().jsonPath("$.productId").isEqualTo(id.intValue());
        webTestClient.get().uri("/giveaways/products/{id}", id).exchange()
                .expectStatus().isOk().expectBody().jsonPath("$.telegramUrl").isEqualTo("https://t.me/figure_draw");
        webTestClient.get().uri("/admin/actions/giveaways/{id}", id)
                .header("Authorization", adminToken).exchange()
                .expectStatus().isOk().expectBody().jsonPath("$.product.id").isEqualTo(id.intValue());
        webTestClient.get().uri("/admin/actions/giveaways/history").header("Authorization", userToken)
                .exchange().expectStatus().isForbidden();
        webTestClient.get().uri("/admin/actions/giveaways/history").header("Authorization", adminToken)
                .exchange().expectStatus().isOk().expectBody().jsonPath("$[0].id").isEqualTo(id.intValue());
    }

    @Test
    void availabilityDictionaryShowsGiveawayOnlyToAdmin() {
        List<Dictionary> publicValues = webTestClient.get().uri("/dictionary?type=PRODUCT_AVAILABILITY")
                .exchange().expectStatus().isOk().expectBodyList(Dictionary.class)
                .returnResult().getResponseBody();
        List<Dictionary> adminValues = webTestClient.get().uri("/dictionary?type=PRODUCT_AVAILABILITY")
                .header("Authorization", adminToken)
                .exchange().expectStatus().isOk().expectBodyList(Dictionary.class)
                .returnResult().getResponseBody();

        assertThat(publicValues).extracting(Dictionary::getValue).containsExactlyInAnyOrder("PURCHASABLE", "PREORDER");
        assertThat(adminValues).extracting(Dictionary::getValue).contains("GIVEAWAY", "EXTERNAL_PRODUCT");
    }

    @Test
    void findProducts_publicEndpoint_returns200WithEmptyList() {
        FindProductRequest req = new FindProductRequest();
        req.setPageable(new Pageable(10, null, null, 0L, SortByType.DATE_DESC));

        webTestClient.post()
                .uri("/products/find")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$").isArray();
    }

    @Test
    void findProducts_nonPreorderFlagIsAcceptedInJson() {
        webTestClient.post()
                .uri("/products/find")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"catalogFlags\":[\"NON_PREORDER\"]}")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void findProducts_determinesAdultContentFromValidAccessToken() {
        Participant adult = createParticipant(18, ParticipantStatus.ACTIVE);
        Participant minor = createParticipant(17, ParticipantStatus.ACTIVE);
        Participant withoutAge = createParticipant(null, ParticipantStatus.ACTIVE);
        Participant blocked = createParticipant(25, ParticipantStatus.BLOCKED);
        Product normal = saveProduct("Normal product", adult.getId());
        Product restricted = saveProduct("Adult product", adult.getId());
        linkToNsfwCategory(restricted.getId());

        String adultToken = accessToken(adult, Duration.ofMinutes(30));
        assertSearchResult(null, true, normal.getId(), restricted.getId(), false);
        assertSearchResult("Bearer invalid", true, normal.getId(), restricted.getId(), false);
        assertSearchResult(accessToken(adult, Duration.ofMinutes(-1)), true, normal.getId(), restricted.getId(), false);
        assertSearchResult("Bearer " + jwtService.generateRefreshToken(userDetails(adult)), true, normal.getId(), restricted.getId(), false);
        assertSearchResult("Bearer " + jwtService.generateVerificationAccessToken(adult.getId()), true, normal.getId(), restricted.getId(), false);
        assertSearchResult(accessToken(minor, Duration.ofMinutes(30)), true, normal.getId(), restricted.getId(), false);
        assertSearchResult(accessToken(withoutAge, Duration.ofMinutes(30)), null, normal.getId(), restricted.getId(), false);
        assertSearchResult(accessToken(blocked, Duration.ofMinutes(30)), null, normal.getId(), restricted.getId(), false);
        assertSearchResult(adultToken, false, normal.getId(), restricted.getId(), true);
        assertSearchResult(adultToken, null, normal.getId(), restricted.getId(), true);
    }

    @Test
    void protectedEndpoint_invalidTokenStillReturns401() {
        webTestClient.post()
                .uri("/products/my")
                .header("Authorization", "Bearer invalid")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    private void assertSearchResult(String authorization, Boolean includeAdult, Long normalId, Long restrictedId, boolean shouldIncludeAdult) {
        FindProductRequest request = new FindProductRequest();
        request.setPageable(new Pageable(10, null, null, 0L, SortByType.DATE_DESC));
        Object body = includeAdult == null
                ? request
                : Map.of("pageable", request.getPageable(), "includeAdult", includeAdult);

        WebTestClient.RequestBodySpec requestSpec = webTestClient.post()
                .uri("/products/find")
                .contentType(MediaType.APPLICATION_JSON);
        if (authorization != null) requestSpec.header("Authorization", authorization);

        List<ProductDto> products = requestSpec.bodyValue(body)
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(ProductDto.class)
                .returnResult()
                .getResponseBody();
        assertThat(products).isNotNull();
        List<Long> ids = products.stream().map(ProductDto::getId).toList();
        assertThat(ids).contains(normalId);
        if (shouldIncludeAdult) {
            assertThat(ids).contains(restrictedId);
        } else {
            assertThat(ids).doesNotContain(restrictedId);
        }
    }

    private Participant createParticipant(Integer age, ParticipantStatus status) {
        String suffix = Long.toString(System.nanoTime());
        return participantRepository.save(Participant.builder()
                .login("search_" + suffix)
                .mail("search_" + suffix + "@example.com")
                .fullName("Search User")
                .phoneNumber("+70000000098")
                .status(status)
                .password("pass")
                .role(ParticipantRole.USER)
                .deadlineSending(3)
                .deadlinePayment(7)
                .sellerStatus(SellerStatus.DEFAULT)
                .age(age)
                .createdAt(Instant.now())
                .build()).block();
    }

    private CustomUserDetails userDetails(Participant participant) {
        return CustomUserDetails.builder()
                .id(participant.getId())
                .login(participant.getLogin())
                .email(participant.getMail())
                .fullName(participant.getFullName())
                .role(ParticipantRole.USER.name())
                .password("pass")
                .status(participant.getStatus())
                .build();
    }

    private String accessToken(Participant participant, Duration lifetime) {
        return "Bearer " + jwtService.generateAccessToken(userDetails(participant), lifetime);
    }

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
        Long categoryId = databaseClient.sql("SELECT id FROM category WHERE slug = 'nsfw_adult' LIMIT 1")
                .map(row -> row.get("id", Long.class)).one().block();
        assertThat(categoryId).isNotNull();
        databaseClient.sql("INSERT INTO product_category (product_id, category_id) VALUES (:pid, :cid)")
                .bind("pid", productId)
                .bind("cid", categoryId)
                .fetch().rowsUpdated().block();
    }

    // --- protected endpoints: 401 without token ---

    @Test
    void findMyProducts_noToken_returns401() {
        webTestClient.post()
                .uri("/products/my")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void deleteProduct_noToken_returns401() {
        webTestClient.delete()
                .uri("/product/1")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void createProduct_noToken_returns401() {
        webTestClient.post()
                .uri("/products")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void extendProduct_noToken_returns401() {
        webTestClient.post()
                .uri("/products/extend/1")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    // --- admin endpoints: 403 for USER role ---

    @Test
    void adminUpdateProductStatus_userToken_returns403() {
        webTestClient.put()
                .uri("/admin/actions/product/1?productStatus=BLOCKED")
                .header("Authorization", userToken)
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void adminUpdateParticipantStatus_userToken_returns403() {
        webTestClient.put()
                .uri("/admin/actions/participants/1/status")
                .header("Authorization", userToken)
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void adminIssueAgentToken_userToken_returns403() {
        webTestClient.post()
                .uri("/admin/actions/agents/1/token")
                .header("Authorization", userToken)
                .exchange()
                .expectStatus().isForbidden();
    }

    // --- admin endpoints: 200 for ADMIN role ---

    @Test
    void adminCreateCategory_adminToken_returns200() {
        webTestClient.post()
                .uri("/admin/actions/categories?name=TestCategory")
                .header("Authorization", adminToken)
                .exchange()
                .expectStatus().isOk();
    }
}
