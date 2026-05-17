package com.model_store.service.impl;

import com.model_store.exception.ApiErrors;
import com.model_store.exception.constant.ErrorCode;
import com.model_store.model.CustomUserDetails;
import com.model_store.model.IssueAgentTokensResponse;
import com.model_store.service.AgentTokenService;
import com.model_store.service.JwtService;
import com.model_store.service.ParticipantService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.userdetails.ReactiveUserDetailsService;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAmount;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentTokenServiceImpl implements AgentTokenService {
    private final ParticipantService participantService;
    private final ReactiveUserDetailsService userDetailsService;
    private final JwtService jwtService;

    @Override
    public Mono<IssueAgentTokensResponse> issueAgentTokens(Long participantId, Integer accessTokenTtlMinutes, Integer refreshTokenTtlDays) {
        log.info("Issuing agent tokens: participantId={}, accessTtl={}min, refreshTtl={}days",
                participantId, accessTokenTtlMinutes, refreshTokenTtlDays);
        validateAgentTokenTtl(accessTokenTtlMinutes, refreshTokenTtlDays);
        return participantService.findActualById(participantId)
                .switchIfEmpty(Mono.error(ApiErrors.notFound(ErrorCode.PARTICIPANT_NOT_FOUND, "Пользователь не найден")))
                .flatMap(p -> userDetailsService.findByUsername(p.getMail()))
                .map(userDetails -> buildTokensResponse((CustomUserDetails) userDetails, accessTokenTtlMinutes, refreshTokenTtlDays))
                .doOnSuccess(r -> log.info("Agent tokens issued: participantId={}, accessExpiresAt={}", participantId, r.getAccessTokenExpiresAt()));
    }

    private IssueAgentTokensResponse buildTokensResponse(CustomUserDetails userDetails,
                                                         Integer accessTokenTtlMinutes,
                                                         Integer refreshTokenTtlDays) {
        TemporalAmount accessLifetime = java.time.Duration.ofMinutes(accessTokenTtlMinutes);
        TemporalAmount refreshLifetime = java.time.Period.ofDays(refreshTokenTtlDays);

        String accessToken = jwtService.generateAgentToken(userDetails, accessLifetime);
        String refreshToken = jwtService.generateAgentRefreshToken(userDetails, refreshLifetime);

        LocalDateTime now = LocalDateTime.now();
        Instant accessExpiresAt = now.plus(accessLifetime).atZone(ZoneId.systemDefault()).toInstant();
        Instant refreshExpiresAt = now.plus(refreshLifetime).atZone(ZoneId.systemDefault()).toInstant();

        return IssueAgentTokensResponse.builder()
                .accessToken(accessToken)
                .accessTokenExpiresAt(accessExpiresAt)
                .refreshToken(refreshToken)
                .refreshTokenExpiresAt(refreshExpiresAt)
                .build();
    }

    private static void validateAgentTokenTtl(Integer accessTokenTtlMinutes, Integer refreshTokenTtlDays) {
        if (accessTokenTtlMinutes < 15 || accessTokenTtlMinutes > 1440) {
            throw ApiErrors.badRequest(
                    ErrorCode.INVALID_REQUEST,
                    "accessTokenTtlMinutes должен быть в диапазоне 15-1440 минут"
            );
        }
        if (refreshTokenTtlDays < 30 || refreshTokenTtlDays > 365) {
            throw ApiErrors.badRequest(
                    ErrorCode.INVALID_REQUEST,
                    "refreshTokenTtlDays должен быть в диапазоне 30-365 дней"
            );
        }
    }
}
