package com.model_store.service;

import com.amazonaws.services.s3.AmazonS3;
import com.model_store.configuration.property.ApplicationProperties;
import com.model_store.mapper.ProductMapper;
import com.model_store.model.base.Participant;
import com.model_store.model.base.Product;
import com.model_store.model.constant.Currency;
import com.model_store.model.constant.ParticipantRole;
import com.model_store.model.constant.ParticipantStatus;
import com.model_store.model.constant.ProductAvailabilityType;
import com.model_store.model.constant.ProductStatus;
import com.model_store.model.constant.SellerStatus;
import com.model_store.repository.ParticipantRepository;
import com.model_store.repository.ProductRepository;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import reactor.core.publisher.Mono;

import java.time.Instant;

@SpringBootTest
@ExtendWith(SpringExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class IntegrationTest  {

    @Autowired
    protected ProductService productService;

    @MockBean
    private AmazonS3 amazonS3; // мокируем клиент S3

    @Autowired
    protected ProductRepository productRepository;

    @Autowired
    protected ParticipantRepository participantRepository;

    @Autowired
    protected CategoryService categoryService;

    @Autowired
    protected ProductMapper productMapper;

    @Autowired
    protected ImageService imageService;

    @Autowired
    protected ReviewService reviewService;

    @Autowired
    protected ApplicationProperties properties;

    @Autowired
    protected SocialNetworksService socialNetworksService;

    @Autowired
    protected TransferService transferService;

    @Autowired
    protected AddressService addressService;

    protected Mono<Product> createProduct(ProductStatus status, Long participantId, ProductAvailabilityType availability, Float prepaymentAmount, String externalUrl) {
        Product.ProductBuilder builder = Product.builder()
                .id(100L)
                .name("Test Product")
                .description("Description for test product")
                .price(1500f)
                .count(10)
                .currency(Currency.RUB)
                .originality("Original")
                .participantId(participantId)
                .status(status)
                .expirationDate(Instant.now().plusSeconds(3600 * 24 * 30)) // через 30 дней
                .availability(availability);

        if (prepaymentAmount != null) builder.prepaymentAmount(prepaymentAmount);
        if (externalUrl != null) builder.externalUrl(externalUrl);

        return productRepository.save(builder.build());
    }


    protected Mono<Product> createPurchasableProduct(ProductStatus status, Long participantId) {
        return createProduct(status, participantId, ProductAvailabilityType.PURCHASABLE, null, null);
    }

    protected Mono<Product> createExternalProduct(ProductStatus status, Long participantId) {
        return createProduct(status, participantId, ProductAvailabilityType.EXTERNAL_ONLY, null, "https://example.com");
    }

    protected Mono<Product> createPreorderProduct(ProductStatus status, Long participantId) {
        return createProduct(status, participantId, ProductAvailabilityType.PREORDER, 500f, null);
    }


    protected Mono<Participant> createTestParticipant() {
        var participant = Participant.builder()
                .id(1L)
                .login("testuser")
                .mail("testuser@example.com")
                .fullName("Тестовый Пользователь")
                .phoneNumber("+79990001122")
                .status(ParticipantStatus.ACTIVE)
                .password("password123")
                .role(ParticipantRole.USER)
                .deadlineSending(3)
                .deadlinePayment(7)
                .sellerStatus(SellerStatus.DEFAULT)
                .createdAt(Instant.now())
                .build();

        return participantRepository.save(participant);
    }


}
