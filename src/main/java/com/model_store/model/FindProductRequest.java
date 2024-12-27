package com.model_store.model;

import com.model_store.model.util.DateRange;
import com.model_store.model.util.PriceRange;
import lombok.Data;
import org.springframework.data.domain.Pageable;

@Data
public class FindProductRequest {
    private String productName;
    private String category;
    private PriceRange priceRange;
    private String originality;
    private DateRange dateRange;
    private Pageable pageable;
}