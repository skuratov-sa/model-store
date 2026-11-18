package com.model_store.model.page;

import com.model_store.model.constant.SortByType;
import jakarta.validation.constraints.Min;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class Pageable {
    @Min(value = 0, message = "Pageable.size не может быть меньше чем 0")
    private int size;
    private Instant lastCreatedAt;
    private Float lastPrice;
    private Long lastId;
    private SortByType sortBy;
    // Use the last product's sellerRating; null/0 means a seller without reviews.
    private Float lastSellerRating;

    public Pageable(int size, Instant lastCreatedAt, Float lastPrice, Long lastId, SortByType sortBy) {
        this(size, lastCreatedAt, lastPrice, lastId, sortBy, null);
    }
}
