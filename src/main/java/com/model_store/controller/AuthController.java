package com.model_store.controller;

import com.model_store.exception.ApiErrors;
import com.model_store.model.CustomUserDetails;
import com.model_store.model.VerifyCodeRequest;
import com.model_store.model.dto.LoginRequest;
import com.model_store.service.EmailService;
import com.model_store.service.JwtService;
import com.model_store.service.ParticipantService;
import com.model_store.service.VerificationCodeService;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.ReactiveUserDetailsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static com.model_store.exception.constant.ErrorCode.ACCOUNT_BLOCKED;
import static com.model_store.exception.constant.ErrorCode.ACCOUNT_DELETED;
import static com.model_store.exception.constant.ErrorCode.TOKEN_INVALID_OR_EXPIRED;
import static com.model_store.exception.constant.ErrorCode.WAITING_VERIFY;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {
    private final ReactiveAuthenticationManager authenticationManager;
    private final ReactiveUserDetailsService userDetailsService;
    private final JwtService jwtService;
    private final VerificationCodeService verificationCodeService;
    private final ParticipantService participantService;
    private final EmailService emailService;

    @PostMapping("/login")
    public Mono<ResponseEntity<Map<String, String>>> login(@RequestBody Mono<LoginRequest> request) {
        return request.flatMap(req ->
                authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(req.getMail(), req.getPassword()))
                        .flatMap(auth -> userDetailsService.findByUsername(req.getMail()))
                        .flatMap(userDetails -> {
                            CustomUserDetails customUserDetails = (CustomUserDetails) userDetails;
                            return switch (customUserDetails.getStatus()) {
                                case ACTIVE -> Mono.just(ResponseEntity.ok(getTokensResponse(customUserDetails)));
                                case WAITING_VERIFY -> Mono.error(
                                        ApiErrors.authException(WAITING_VERIFY, "Необходимо подтвердить почту")
                                );
                                case BLOCKED -> Mono.error(
                                        ApiErrors.authException(ACCOUNT_BLOCKED, "Пользователь заблокирован")
                                );
                                case DELETED -> Mono.error(
                                        ApiErrors.authException(ACCOUNT_DELETED, "Учетная запись была удалена")
                                );
                            };
                        })
        );
    }

    @PostMapping("/verification/resend")
    public Mono<Long> resend(@RequestParam String email) {
       return emailService.sendVerificationCode(email);
    }

    @PostMapping("/password/reset")
    public Mono<Void> resetPassword(@RequestParam String email) {
        return emailService.sendPasswordReset(email);
    }

    @PostMapping("/verify-code")
    public Mono<ResponseEntity<Map<String, String>>> verifyCode(@RequestBody VerifyCodeRequest request) {
        return verificationCodeService.checkAndConsumeCode(request.userId(), request.code())
                .then(participantService.activateUser(request.userId()))
                .map(participant -> {
                    CustomUserDetails userDetails = CustomUserDetails.fromUser(participant, null);
                    Map<String, String> tokens = getTokensResponse(userDetails);

                    return ResponseEntity.ok(tokens);
                })
                .onErrorResume(e -> Mono.just(
                        ResponseEntity.badRequest().body(Map.of("error", "Неверный код подтверждения"))
                ));
    }

    @PostMapping("/refresh")
    public Mono<ResponseEntity<String>> refreshToken(@RequestHeader("X-Refresh-Token") String refreshToken) {
        return jwtService.refreshAccessToken(refreshToken)
                .map(ResponseEntity::ok)
                .onErrorMap(ex -> ApiErrors.authException(TOKEN_INVALID_OR_EXPIRED, "Token недействителен или срок его действия истек"));
    }

    @GetMapping("/profile")
    public Mono<ResponseEntity<Map<String, Object>>> getCurrentUser(@RequestHeader("Authorization") String token) {
        return Mono.justOrEmpty(token)
                .map(jwtService::parseAccessToken)
                .map(claims -> {
                    Map<String, Object> claimsMap = new HashMap<>(claims);
                    return ResponseEntity.ok(claimsMap);
                }).onErrorMap(ex -> ApiErrors.authException(TOKEN_INVALID_OR_EXPIRED, "Token недействителен или срок его действия истек"));

    }

    @NotNull
    private Map<String, String> getTokensResponse(CustomUserDetails userDetails) {
        String accessToken = jwtService.generateAccessToken(userDetails, Duration.ofMinutes(30));
        String refreshToken = jwtService.generateRefreshToken(userDetails);

        Map<String, String> tokens = new HashMap<>();
        tokens.put("access_token", accessToken);
        tokens.put("refresh_token", refreshToken);
        return tokens;
    }
}