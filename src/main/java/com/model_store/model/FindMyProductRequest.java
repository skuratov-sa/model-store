package com.model_store.model;

import com.model_store.model.page.Pageable;
import com.model_store.model.util.DateRange;
import com.model_store.model.util.PriceRange;
import jakarta.validation.Valid;
import com.model_store.model.constant.CatalogFilterFlag;
import java.util.List;
import lombok.Data;

@Data
public class FindMyProductRequest {
    private String name;
    private Long categoryId;
    private List<CatalogFilterFlag> catalogFlags;
    private String originality;
    private @Valid PriceRange priceRange;
    private DateRange dateRange;
    private @Valid Pageable pageable;
}
