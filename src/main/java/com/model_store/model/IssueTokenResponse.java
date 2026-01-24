package com.model_store.model;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Builder
@Data
public class IssueTokenResponse {
    private String token;
    private Instant expiresAt;
}
