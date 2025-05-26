package com.model_store.model.base;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "review")
public class Review {
    @Id
    private Long id;

    private Long orderProductId;

    private Long reviewerId;

    private Long sellerId;

    private Integer rating;

    private String comment;

    private Instant createdAt = Instant.now();
}