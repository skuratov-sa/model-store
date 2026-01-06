package com.model_store.service.impl;

import com.model_store.model.constant.ProductStatus;
import com.model_store.service.IntegrationTest;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

public class ProductServiceImplTest extends IntegrationTest {

    @Test
    void testGetProductById() {
        var resultMono = createTestParticipant()
                .flatMap(participant -> createPurchasableProduct(ProductStatus.ACTIVE, participant.getId()))
                .flatMap(product -> productService.getProductById(product.getId()));

        StepVerifier.create(resultMono)
                .assertNext(productResponse -> {
                    assert productResponse.getName().equals("Test Product");
                    assert productResponse.getStatus() == ProductStatus.ACTIVE;
                    assert productResponse.getParticipantId().equals(1L); // id тестового участника
                })
                .verifyComplete();
    }


    @Test
    void findById() {
    }

    @Test
    void shortInfoById() {
    }

    @Test
    void findNamesBySearch() {
    }

    @Test
    void findByParams() {
    }

    @Test
    void findMyByParams() {
    }

    @Test
    void findActualProduct() {
    }

    @Test
    void createProduct() {
    }

    @Test
    void updateProduct() {
    }

    @Test
    void deleteProduct() {
    }

    @Test
    void updateProductStatus() {
    }

    @Test
    void save() {
    }

    @Test
    void extendExpirationDate() {
    }
}