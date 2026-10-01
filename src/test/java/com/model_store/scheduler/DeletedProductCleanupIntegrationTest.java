package com.model_store.scheduler;

import com.model_store.model.base.Image;
import com.model_store.model.constant.ImageStatus;
import com.model_store.model.constant.ImageTag;
import com.model_store.model.constant.ProductStatus;
import com.model_store.repository.ImageRepository;
import com.model_store.service.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.r2dbc.core.DatabaseClient;

import static org.assertj.core.api.Assertions.assertThat;

class DeletedProductCleanupIntegrationTest extends IntegrationTest {

    @Autowired private DatabaseClient databaseClient;
    @Autowired private ImageRepository imageRepository;
    @Autowired private ProductCleanupScheduler scheduler;

    @BeforeEach
    void cleanUp() {
        databaseClient.sql("TRUNCATE TABLE participant RESTART IDENTITY CASCADE")
                .fetch().rowsUpdated().block();
        databaseClient.sql("TRUNCATE TABLE image RESTART IDENTITY CASCADE")
                .fetch().rowsUpdated().block();
    }

    @Test
    void activeImageOfDeletedProductIsSelectedForMinioCleanup() {
        Long productId = createPurchasableProduct(ProductStatus.DELETED, createParticipant())
                .map(product -> product.getId()).block();
        Image image = imageRepository.save(Image.builder()
                .entityId(productId)
                .tag(ImageTag.PRODUCT)
                .status(ImageStatus.ACTIVE)
                .filename("deleted-product.jpg")
                .build()).block();

        assertThat(imageRepository.findImagesToDelete().map(Image::getId).collectList().block())
                .contains(image.getId());
        assertThat(productRepository.findDeletedWithoutImages(200).collectList().block())
                .doesNotContain(productId);
    }

    @Test
    void cleanupRemovesDeletedProductAndItsListReferences() {
        Long participantId = createParticipant();
        Long productId = createPurchasableProduct(ProductStatus.DELETED, participantId)
                .map(product -> product.getId()).block();
        for (String table : new String[]{"product_favorite", "product_basket", "product_cart"}) {
            databaseClient.sql("INSERT INTO " + table + " (participant_id, product_id) VALUES (:participantId, :productId)")
                    .bind("participantId", participantId)
                    .bind("productId", productId)
                    .fetch().rowsUpdated().block();
        }

        scheduler.cleanup().block();

        assertThat(productRepository.findById(productId).block()).isNull();
        for (String table : new String[]{"product_favorite", "product_basket", "product_cart"}) {
            Long count = databaseClient.sql("SELECT count(*) AS n FROM " + table + " WHERE product_id = :productId")
                    .bind("productId", productId)
                    .map((row, metadata) -> row.get("n", Long.class))
                    .one().block();
            assertThat(count).isZero();
        }
    }

    @Test
    void cleanupRemovesProductReferencedByOrderButKeepsHistory() {
        Long participantId = createParticipant();
        Long productId = createPurchasableProduct(ProductStatus.DELETED, participantId)
                .map(product -> product.getId()).block();
        Long addressId = databaseClient.sql("INSERT INTO address DEFAULT VALUES RETURNING id")
                .map((row, metadata) -> row.get("id", Long.class)).one().block();
        Long transferId = databaseClient.sql("""
                        INSERT INTO transfer (sending, participant_id)
                        VALUES ('PRODUCT_PICKUP', :participantId)
                        RETURNING id
                        """)
                .bind("participantId", participantId)
                .map((row, metadata) -> row.get("id", Long.class)).one().block();
        Long orderId = databaseClient.sql("""
                        INSERT INTO "order" (seller_id, customer_id, count, status, product_id,
                                             product_name, product_unit_price, product_currency,
                                             product_availability, address_id, transfer_id)
                        VALUES (:participantId, :participantId, 1, 'BOOKED', :productId,
                                'Test Product', 100, 'RUB', 'PURCHASABLE',
                                :addressId, :transferId)
                        RETURNING id
                        """)
                .bind("participantId", participantId)
                .bind("productId", productId)
                .bind("addressId", addressId)
                .bind("transferId", transferId)
                .map((row, metadata) -> row.get("id", Long.class)).one().block();

        databaseClient.sql("""
                        INSERT INTO review (order_id, product_id, reviewer_id, seller_id, rating)
                        VALUES (:orderId, :productId, :participantId, :participantId, 5)
                        """)
                .bind("orderId", orderId)
                .bind("productId", productId)
                .bind("participantId", participantId)
                .fetch().rowsUpdated().block();

        scheduler.cleanup().block();

        assertThat(productRepository.findById(productId).block()).isNull();
        assertThat(databaseClient.sql("SELECT product_id FROM \"order\" WHERE id = :id")
                .bind("id", orderId)
                .map((row, metadata) -> row.get("product_id", Long.class)).one().block()).isEqualTo(productId);
        assertThat(databaseClient.sql("SELECT count(*) AS n FROM review WHERE product_id = :id")
                .bind("id", productId)
                .map((row, metadata) -> row.get("n", Long.class)).one().block()).isEqualTo(1L);
    }

    private Long createParticipant() {
        return databaseClient.sql("""
                        INSERT INTO participant (login, password, status)
                        VALUES ('cleanup-test', 'password', 'ACTIVE')
                        RETURNING id
                        """)
                .map((row, metadata) -> row.get("id", Long.class))
                .one().block();
    }
}
