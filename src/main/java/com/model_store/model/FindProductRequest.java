package com.model_store.model;

import com.model_store.model.util.DateRange;
import com.model_store.model.util.PriceRange;
import lombok.Data;

@Data
public class FindProductRequest {
    private String productName;
    private PriceRange priceRange;
    private String originality;
    private DateRange dateRange;
}