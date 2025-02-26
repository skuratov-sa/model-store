package com.model_store.model.util;

import jakarta.validation.constraints.Min;
import lombok.Data;

@Data
public class PriceRange {
    @Min(value = 0, message = "PriceRange.minPrice не может быть меньше чем 0")
    private int minPrice;
    @Min(value = 0, message = "PriceRange.maxPrice не может быть меньше чем 0")
    private int maxPrice;
}