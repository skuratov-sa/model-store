package com.model_store.service.impl;

import com.model_store.exception.ApiException;
import com.model_store.model.CreateOrUpdateProductRequest;
import com.model_store.model.FindMyProductRequest;
import com.model_store.model.FindProductRequest;
import com.model_store.model.GiveawaySettingsRequest;
import com.model_store.model.base.Address;
import com.model_store.model.base.Image;
import com.model_store.model.base.Order;
import com.model_store.model.base.Participant;
import com.model_store.model.base.Product;
import com.model_store.model.base.Transfer;
import com.model_store.model.constant.AddressStatus;
import com.model_store.model.constant.Currency;
import com.model_store.model.constant.ImageStatus;
import com.model_store.model.constant.ImageTag;
import com.model_store.model.constant.OrderStatus;
import com.model_store.model.constant.ParticipantRole;
import com.model_store.model.constant.ParticipantStatus;
import com.model_store.model.constant.ProductAvailabilityType;
import com.model_store.model.constant.ProductStatus;
import com.model_store.model.constant.SellerStatus;
import com.model_store.model.constant.ShippingMethodsType;
import com.model_store.model.constant.TransferStatus;
import com.model_store.repository.AddressRepository;
import com.model_store.repository.ImageRepository;
import com.model_store.repository.OrderRepository;
import com.model_store.repository.TransferRepository;
import com.model_store.service.IntegrationTest;
import com.model_store.service.BasketService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.test.StepVerifier;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GiveawayIntegrationTest extends IntegrationTest {
    @Autowired private DatabaseClient databaseClient;
    @Autowired private OrderRepository orders;
    @Autowired private AddressRepository addresses;
    @Autowired private TransferRepository transfers;
    @Autowired private ImageRepository images;
    @Autowired private BasketService basketService;

    private Participant admin;

    @BeforeEach
    void setUp() {
        databaseClient.sql("TRUNCATE TABLE product_category, product, participant, image RESTART IDENTITY CASCADE")
                .fetch().rowsUpdated().block();
        admin = participant("giveaway_admin", ParticipantRole.ADMIN, false);
    }

    @Test
    void giveawayIsVisibleOnlyThroughDedicatedQueriesAndAdminHistory() {
        Long id = productService.createProduct(giveawayRequest(), admin.getId(), ParticipantRole.ADMIN).block();

        assertThat(productService.findActiveGiveaway().block().productId()).isEqualTo(id);
        assertThat(productService.findPublicGiveawayById(id).block().telegramUrl()).isEqualTo("https://t.me/figure_draw");
        assertThat(productService.findByParams(new FindProductRequest(), null).collectList().block()).isEmpty();
        assertThat(productService.findNamesBySearch("Giveaway Figure").collectList().block())
                .doesNotContain("Giveaway Figure");
        assertThat(productService.getProductById(id).block()).isNull();
        assertThat(productService.findActualProductForUpdate(id).block()).isNull();
        assertThat(productService.findMyByParams(new FindMyProductRequest(), admin.getId(), false).collectList().block()).isEmpty();
        assertThat(productService.findMyByParams(new FindMyProductRequest(), admin.getId(), true).collectList().block()).hasSize(1);
        assertThat(productService.findGiveawayHistory(admin.getId(), 0, 50).map(Product::getId).collectList().block())
                .containsExactly(id);
    }

    @Test
    void onlyUnfinishedOrdersPreventConvertingProductToGiveaway() {
        Participant buyer = participant("giveaway_buyer", ParticipantRole.USER, false);
        Product product = ordinaryProduct(admin.getId(), ProductAvailabilityType.PURCHASABLE);
        Address address = addresses.save(Address.builder().country("Russia").city("Moscow")
                .street("Street").houseNumber("1").index(101000).status(AddressStatus.ACTIVE).build()).block();
        Transfer transfer = transfers.save(Transfer.builder().participantId(admin.getId())
                .sending(ShippingMethodsType.RUSSIAN_POST).price(100).currency(Currency.RUB)
                .status(TransferStatus.ACTIVE).build()).block();
        Order order = orders.save(Order.builder().sellerId(admin.getId()).customerId(buyer.getId())
                .productId(product.getId()).addressId(address.getId()).transferId(transfer.getId())
                .productName(product.getName()).productUnitPrice(product.getPrice())
                .productCurrency(product.getCurrency()).productAvailability(product.getAvailability())
                .count(1).status(OrderStatus.BOOKED).totalPrice(100f).prepaymentAmount(0f).build()).block();

        CreateOrUpdateProductRequest request = giveawayRequest();
        StepVerifier.create(productService.updateProduct(product.getId(), request, admin.getId(), ParticipantRole.ADMIN))
                .expectError(ApiException.class).verify();
        assertThat(productRepository.findById(product.getId()).block().getAvailability())
                .isEqualTo(ProductAvailabilityType.PURCHASABLE);

        databaseClient.sql("UPDATE \"order\" SET status = 'COMPLETED' WHERE id = :id")
                .bind("id", order.getId()).fetch().rowsUpdated().block();
        productService.updateProduct(product.getId(), request, admin.getId(), ParticipantRole.ADMIN).block();
        assertThat(productRepository.findById(product.getId()).block().getAvailability())
                .isEqualTo(ProductAvailabilityType.GIVEAWAY);

        Product cancelledProduct = ordinaryProduct(admin.getId(), ProductAvailabilityType.PURCHASABLE);
        orders.save(Order.builder().sellerId(admin.getId()).customerId(buyer.getId())
                .productId(cancelledProduct.getId()).addressId(address.getId()).transferId(transfer.getId())
                .productName(cancelledProduct.getName()).productUnitPrice(cancelledProduct.getPrice())
                .productCurrency(cancelledProduct.getCurrency()).productAvailability(cancelledProduct.getAvailability())
                .count(1).status(OrderStatus.CANCELLED).totalPrice(100f).prepaymentAmount(0f).build()).block();
        CreateOrUpdateProductRequest nextGiveaway = giveawayRequest();
        nextGiveaway.getGiveaway().setStartAt(Instant.now().plusSeconds(7200));
        nextGiveaway.getGiveaway().setEndAt(Instant.now().plusSeconds(10800));
        productService.updateProduct(cancelledProduct.getId(), nextGiveaway, admin.getId(), ParticipantRole.ADMIN).block();
        assertThat(productRepository.findById(cancelledProduct.getId()).block().getAvailability())
                .isEqualTo(ProductAvailabilityType.GIVEAWAY);
    }

    @Test
    void upcomingGiveawayIsVisibleAsWaitingAndCannotBeOrdered() {
        CreateOrUpdateProductRequest request = giveawayRequest();
        request.getGiveaway().setStartAt(Instant.now().plusSeconds(3600));
        request.getGiveaway().setEndAt(Instant.now().plusSeconds(7200));
        Long id = productService.createProduct(request, admin.getId(), ParticipantRole.ADMIN).block();

        assertThat(productRepository.findById(id).block().getStatus()).isEqualTo(ProductStatus.AWAITING_GIVEAWAY);
        assertThat(productService.findActiveGiveaway().block().status()).isEqualTo(ProductStatus.AWAITING_GIVEAWAY);
        assertThat(productService.findActualProduct(id).block()).isNull();
        assertThat(productService.findActualProductForUpdate(id).block()).isNull();
        StepVerifier.create(basketService.addToBasket(admin.getId(), id, 1))
                .expectError(ApiException.class).verify();

        databaseClient.sql("UPDATE product SET giveaway_start_at = CURRENT_TIMESTAMP - INTERVAL '1 minute' WHERE id = :id")
                .bind("id", id).fetch().rowsUpdated().block();
        assertThat(productRepository.findById(id).block().getStatus()).isEqualTo(ProductStatus.AWAITING_GIVEAWAY);
        assertThat(productService.findActiveGiveaway().block().status()).isEqualTo(ProductStatus.ACTIVE);
        assertThat(productService.findAdminGiveawayDetails(id, admin.getId()).block().product().getStatus())
                .isEqualTo(ProductStatus.ACTIVE);
    }

    @Test
    void onlyAdminCanManageGiveawayImagesAndDetailIncludesRelations() {
        Participant stranger = participant("giveaway_stranger", ParticipantRole.USER, false);
        Long id = productService.createProduct(giveawayRequest(), admin.getId(), ParticipantRole.ADMIN).block();
        Image image = images.save(Image.builder().entityId(id).tag(ImageTag.PRODUCT)
                .status(ImageStatus.ACTIVE).filename("prize.png").build()).block();
        Long categoryId = categoryService.createCategory("Giveaway Review Category", null).block();
        databaseClient.sql("INSERT INTO product_category (product_id, category_id) VALUES (:pid, :cid)")
                .bind("pid", id).bind("cid", categoryId).fetch().rowsUpdated().block();

        assertThat(imageService.isActualEntity(id, ImageTag.PRODUCT, admin.getId()).block()).isTrue();
        assertThat(imageService.isActualEntity(id, ImageTag.PRODUCT, stranger.getId()).block()).isFalse();
        assertThat(productService.findAdminGiveawayDetails(id, admin.getId()).block().imageIds())
                .containsExactly(image.getId());
        assertThat(productService.findAdminGiveawayDetails(id, admin.getId()).block().categories())
                .extracting(category -> category.getId()).containsExactly(categoryId);

        imageService.deleteImages(List.of(image.getId()), ImageTag.PRODUCT, stranger.getId()).block();
        assertThat(images.findById(image.getId()).block().getStatus()).isEqualTo(ImageStatus.ACTIVE);
        imageService.deleteImages(List.of(image.getId()), ImageTag.PRODUCT, admin.getId()).block();
        assertThat(images.findById(image.getId()).block().getStatus()).isEqualTo(ImageStatus.DELETE);
    }

    @Test
    void concurrentActivationLeavesOnlyOneEnabledGiveaway() {
        CreateOrUpdateProductRequest first = giveawayRequest();
        first.setName("First Figure");
        first.getGiveaway().setEnabled(false);
        CreateOrUpdateProductRequest second = giveawayRequest();
        second.setName("Second Figure");
        second.getGiveaway().setEnabled(false);
        Long firstId = productService.createProduct(first, admin.getId(), ParticipantRole.ADMIN).block();
        Long secondId = productService.createProduct(second, admin.getId(), ParticipantRole.ADMIN).block();

        GiveawaySettingsRequest activate = new GiveawaySettingsRequest();
        activate.setEnabled(true);
        var results = Mono.zip(
                productService.updateAdminGiveaway(firstId, activate, admin.getId())
                        .thenReturn(true).onErrorReturn(ApiException.class, false),
                productService.updateAdminGiveaway(secondId, activate, admin.getId())
                        .thenReturn(true).onErrorReturn(ApiException.class, false)
        ).block(Duration.ofSeconds(10));
        assertThat(results.getT1()).isNotEqualTo(results.getT2());

        Long enabledCount = databaseClient.sql("SELECT count(*) AS count FROM product WHERE availability = 'GIVEAWAY' AND giveaway_enabled")
                .map(row -> row.get("count", Long.class)).one().block();
        assertThat(enabledCount).isEqualTo(1L);
    }

    @Test
    void expiredGiveawayCanBeExtendedAndReactivated() {
        Long id = productService.createProduct(giveawayRequest(), admin.getId(), ParticipantRole.ADMIN).block();
        databaseClient.sql("""
                UPDATE product SET giveaway_start_at = CURRENT_TIMESTAMP - INTERVAL '2 days',
                                   giveaway_end_at = CURRENT_TIMESTAMP - INTERVAL '1 day'
                WHERE id = :id
                """).bind("id", id).fetch().rowsUpdated().block();

        assertThat(productService.findActiveGiveaway().block()).isNull();
        assertThat(productService.findAdminGiveawayDetails(id, admin.getId()).block().product().getStatus())
                .isEqualTo(ProductStatus.TIME_EXPIRED);

        GiveawaySettingsRequest update = new GiveawaySettingsRequest();
        update.setEndAt(Instant.now().plusSeconds(3600));
        update.setEnabled(true);
        productService.updateAdminGiveaway(id, update, admin.getId()).block();

        assertThat(productService.findActiveGiveaway().block().productId()).isEqualTo(id);
        assertThat(productRepository.findById(id).block().getStatus()).isEqualTo(ProductStatus.ACTIVE);
    }

    @Test
    void upcomingGiveawayDoesNotStopCurrentAndStartsAfterItEnds() {
        Long firstId = productService.createProduct(giveawayRequest(), admin.getId(), ParticipantRole.ADMIN).block();
        CreateOrUpdateProductRequest upcoming = giveawayRequest();
        upcoming.getGiveaway().setStartAt(Instant.now().plusSeconds(7200));
        upcoming.getGiveaway().setEndAt(Instant.now().plusSeconds(10800));
        Long secondId = productService.createProduct(upcoming, admin.getId(), ParticipantRole.ADMIN).block();

        assertThat(productService.findActiveGiveaway().block().productId()).isEqualTo(firstId);
        assertThat(productService.findPublicGiveawayById(firstId).block()).isNotNull();
        assertThat(productRepository.findById(firstId).block().getGiveawayEnabled()).isTrue();
        assertThat(productRepository.findById(secondId).block().getStatus())
                .isEqualTo(ProductStatus.AWAITING_GIVEAWAY);

        databaseClient.sql("""
                UPDATE product SET giveaway_start_at = CURRENT_TIMESTAMP - INTERVAL '2 hours',
                                   giveaway_end_at = CURRENT_TIMESTAMP - INTERVAL '1 hour'
                WHERE id = :id
                """)
                .bind("id", firstId).fetch().rowsUpdated().block();
        databaseClient.sql("UPDATE product SET giveaway_start_at = CURRENT_TIMESTAMP - INTERVAL '1 minute' WHERE id = :id")
                .bind("id", secondId).fetch().rowsUpdated().block();
        assertThat(productService.findActiveGiveaway().block().productId()).isEqualTo(secondId);
        assertThat(productService.findActiveGiveaway().block().status()).isEqualTo(ProductStatus.ACTIVE);
        assertThat(productService.findAdminGiveawayDetails(firstId, admin.getId()).block().product().getStatus())
                .isEqualTo(ProductStatus.TIME_EXPIRED);
        assertThat(productService.findAdminGiveawayDetails(secondId, admin.getId()).block().product().getStatus())
                .isEqualTo(ProductStatus.ACTIVE);
        assertThat(productService.findGiveawayHistory(admin.getId(), 0, 50).map(Product::getId).collectList().block())
                .containsExactly(secondId, firstId);
    }

    @Test
    void overlappingGiveawayScheduleIsRejectedWithoutChangingCurrentGiveaway() {
        Long firstId = productService.createProduct(giveawayRequest(), admin.getId(), ParticipantRole.ADMIN).block();
        CreateOrUpdateProductRequest overlapping = giveawayRequest();
        overlapping.getGiveaway().setStartAt(Instant.now().plusSeconds(1800));
        overlapping.getGiveaway().setEndAt(Instant.now().plusSeconds(5400));

        StepVerifier.create(productService.createProduct(overlapping, admin.getId(), ParticipantRole.ADMIN))
                .expectError(ApiException.class).verify();
        assertThat(productService.findActiveGiveaway().block().productId()).isEqualTo(firstId);
        assertThat(productRepository.findById(firstId).block().getGiveawayEnabled()).isTrue();
    }

    @Test
    void adminCanConvertBotProductAndSeeItInHistory() {
        Participant bot = participant("giveaway_bot", ParticipantRole.USER, true);
        Product product = ordinaryProduct(bot.getId(), ProductAvailabilityType.EXTERNAL_PRODUCT);
        CreateOrUpdateProductRequest request = giveawayRequest();
        productService.updateAgentProduct(product.getId(), request, bot.getId()).block();

        assertThat(productService.findAdminGiveaway(product.getId(), admin.getId()).block().getAvailability())
                .isEqualTo(ProductAvailabilityType.GIVEAWAY);
        assertThat(imageService.isActualEntity(product.getId(), ImageTag.PRODUCT, admin.getId()).block()).isTrue();
        assertThat(productService.findGiveawayHistory(admin.getId(), 0, 50).map(Product::getId).collectList().block())
                .containsExactly(product.getId());
    }

    @Test
    void giveawayWithoutSalePriceCanReturnToCatalogAfterPriceIsSet() {
        CreateOrUpdateProductRequest request = giveawayRequest();
        request.setPrice(null);
        request.setCurrency(null);
        Long id = productService.createProduct(request, admin.getId(), ParticipantRole.ADMIN).block();
        assertThat(productRepository.findById(id).block().getPrice()).isZero();

        CreateOrUpdateProductRequest restore = new CreateOrUpdateProductRequest();
        restore.setAvailability(ProductAvailabilityType.PURCHASABLE);
        StepVerifier.create(productService.updateProduct(id, restore, admin.getId(), ParticipantRole.ADMIN))
                .expectError(ApiException.class).verify();

        restore.setPrice(100f);
        restore.setCount(1);
        productService.updateProduct(id, restore, admin.getId(), ParticipantRole.ADMIN).block();
        Product result = productRepository.findById(id).block();
        assertThat(result.getAvailability()).isEqualTo(ProductAvailabilityType.PURCHASABLE);
        assertThat(result.getGiveawayEnabled()).isFalse();
        assertThat(productService.findGiveawayHistory(admin.getId(), 0, 50).map(Product::getId).collectList().block())
                .containsExactly(id);
    }

    private CreateOrUpdateProductRequest giveawayRequest() {
        CreateOrUpdateProductRequest request = new CreateOrUpdateProductRequest();
        request.setName("Giveaway Figure");
        request.setPrice(100f);
        request.setCurrency(Currency.RUB);
        request.setAvailability(ProductAvailabilityType.GIVEAWAY);
        GiveawaySettingsRequest settings = new GiveawaySettingsRequest();
        settings.setEnabled(true);
        settings.setTelegramUrl("https://t.me/figure_draw");
        settings.setStartAt(Instant.now().minusSeconds(60));
        settings.setEndAt(Instant.now().plusSeconds(3600));
        settings.setWinnersCount(1);
        settings.setRules("Join the Telegram group");
        settings.setHomeText("Figure draw");
        request.setGiveaway(settings);
        return request;
    }

    private Participant participant(String login, ParticipantRole role, boolean agent) {
        return participantRepository.save(Participant.builder()
                .login(login).mail(login + "@example.com").fullName(login).phoneNumber("+70000000001")
                .status(ParticipantStatus.ACTIVE).password("pass").role(role)
                .deadlineSending(3).deadlinePayment(7).sellerStatus(SellerStatus.DEFAULT)
                .isAgent(agent).createdAt(Instant.now()).build()).block();
    }

    private Product ordinaryProduct(Long ownerId, ProductAvailabilityType availability) {
        return productRepository.save(Product.builder()
                .name("Ordinary Figure").price(100f).currency(Currency.RUB).count(1)
                .participantId(ownerId).status(ProductStatus.ACTIVE)
                .availability(availability).externalUrl("https://example.com/item")
                .expirationDate(Instant.now().plusSeconds(3600)).build()).block();
    }
}
