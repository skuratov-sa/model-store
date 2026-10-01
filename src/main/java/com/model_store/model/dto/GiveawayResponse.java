package com.model_store.model.dto;

import com.model_store.model.constant.ProductStatus;
import java.time.Instant;
import java.util.List;

public record GiveawayResponse(
        Long productId,
        String name,
        String description,
        List<Long> imageIds,
        String telegramUrl,
        Instant startAt,
        Instant endAt,
        Integer winnersCount,
        String rules,
        String homeText,
        ProductStatus status
) {
}
