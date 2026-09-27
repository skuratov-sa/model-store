package com.model_store.service.impl;

import com.model_store.mapper.ProductMapper;
import com.model_store.mapper.ProductMapperImpl;
import com.model_store.model.base.Product;
import com.model_store.model.constant.ProductAvailabilityType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProductMapperUrlTest {
    private final ProductMapper mapper = new ProductMapperImpl();

    @Test
    void externalUrlIsAbsentFromPublicProductResponses() {
        Product product = Product.builder()
                .id(1L)
                .externalUrl("https://t.me/source")
                .availability(ProductAvailabilityType.EXTERNAL_PRODUCT)
                .build();

        assertThat(mapper.toProductDto(product, List.of(), null, "agent", 0f, 0).getExternalUrl())
                .isNull();
        assertThat(mapper.toGetProductResponse(product, List.of(), List.of(), List.of(),
                "agent", 0f, 0).getExternalUrl()).isNull();
        assertThat(product.getExternalUrl()).isEqualTo("https://t.me/source");
    }
}
