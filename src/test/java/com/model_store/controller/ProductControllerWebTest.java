package com.model_store.controller;

import com.model_store.model.CustomUserDetails;
import com.model_store.model.FindProductRequest;
import com.model_store.model.base.Participant;
import com.model_store.model.constant.ParticipantRole;
import com.model_store.model.constant.ParticipantStatus;
import com.model_store.model.constant.SellerStatus;
import com.model_store.model.constant.SortByType;
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
    void findProducts_publicEndpoint_returns200WithEmptyList() {
        FindProductRequest req = new FindProductRequest();
        req.setPageable(new Pageable(10, null, null, 0L, SortByType.DATE_DESC));
        req.setIncludeAdult(false);

        webTestClient.post()
                .uri("/products/find")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$").isArray();
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
