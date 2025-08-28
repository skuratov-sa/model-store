package com.model_store.controller;

import com.model_store.exception.InvalidTokenException;
import com.model_store.model.CustomUserDetails;
import com.model_store.model.VerifyCodeRequest;
import com.model_store.model.dto.LoginRequest;
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
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {
    private final ReactiveAuthenticationManager authenticationManager;
    private final ReactiveUserDetailsService userDetailsService;
    private final JwtService jwtService;
    private final VerificationCodeService verificationCodeService;
    private final ParticipantService participantService;

    @PostMapping("/login")
    public Mono<ResponseEntity<Map<String, String>>> login(@RequestBody Mono<LoginRequest> request) {
        return request.flatMap(req ->
                authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(req.getMail(), req.getPassword()))
                        .flatMap(auth -> userDetailsService.findByUsername(req.getMail()))
                        .map(userDetails -> {
                            CustomUserDetails customUserDetails = (CustomUserDetails) userDetails;
                            Map<String, String> tokens = getTokensResponse(customUserDetails);
                            return ResponseEntity.ok(tokens);
                        })
        );
    }

    @PostMapping("/verify-code")
    public Mono<ResponseEntity<Map<String, String>>> verifyCode(@RequestBody VerifyCodeRequest request) {
        return verificationCodeService.verifyCode(request.userId(), request.code())
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
                .onErrorMap(ex -> new InvalidTokenException());
    }

    @GetMapping("/profile")
    public Mono<ResponseEntity<Map<String, Object>>> getCurrentUser(@RequestHeader("Authorization") String token) {
        return Mono.justOrEmpty(token)
                .map(jwtService::parseAccessToken)
                .map(claims -> {
                    Map<String, Object> claimsMap = new HashMap<>(claims);
                    return ResponseEntity.ok(claimsMap);
                }).onErrorMap(ex -> new InvalidTokenException());
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