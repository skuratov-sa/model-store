package com.model_store.model;

import com.model_store.model.page.Pageable;
import com.model_store.model.util.DateRange;
import com.model_store.model.util.PriceRange;
import lombok.Data;

@Data
public class FindProductRequest {
    private String productName;
    private Long categoryId;
    private String originality;
    private Long participantId;
    private PriceRange priceRange;
    private DateRange dateRange;
    private Pageable pageable;
}