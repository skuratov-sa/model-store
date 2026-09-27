package com.model_store.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.model_store.model.page.Pageable;
import com.model_store.model.util.DateRange;
import com.model_store.model.util.PriceRange;
import jakarta.validation.Valid;
import lombok.Data;

@Data
@JsonIgnoreProperties("includeAdult")
public class FindProductRequest {
    private String name;
    private Long categoryId;
    private String originality;
    private Long participantId;
    private @Valid PriceRange priceRange;
    private DateRange dateRange;
    private @Valid Pageable pageable;
}
