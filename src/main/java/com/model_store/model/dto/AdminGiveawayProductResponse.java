package com.model_store.model.dto;

import com.model_store.model.base.Product;
import java.util.List;

public record AdminGiveawayProductResponse(
        Product product,
        List<Long> imageIds,
        List<CategoryDto> categories
) {
}
