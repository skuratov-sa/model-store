package com.model_store.service;

import com.model_store.model.IssueAgentTokensResponse;
import reactor.core.publisher.Mono;

public interface AgentTokenService {
    Mono<IssueAgentTokensResponse> issueAgentTokens(Long participantId, Integer accessTokenTtlMinutes, Integer refreshTokenTtlDays);
}
