package com.model_store.model.page;

import com.model_store.model.constant.SortByType;
import jakarta.validation.constraints.Min;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.Instant;

@Data
@AllArgsConstructor
public class Pageable {
    @Min(value = 0, message = "Pageable.size не может быть меньше чем 0")
    private int size;
    private Instant lastCreatedAt;
    private Float lastPrice;
    private Long lastId;
    private SortByType sortBy;
}