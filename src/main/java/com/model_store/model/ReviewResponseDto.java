package com.model_store.model;

import lombok.Data;

import java.time.Instant;

@Data
public class ReviewResponseDto {
    private Long id;
    private int rating;
    private String comment;
    private String reviewerName;
    private Instant createdAt;
}