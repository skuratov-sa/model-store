package com.model_store.model;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Builder
@Data
public class IssueAgentTokensResponse {
    private String accessToken;
    private Instant accessTokenExpiresAt;
    private String refreshToken;
    private Instant refreshTokenExpiresAt;
}
