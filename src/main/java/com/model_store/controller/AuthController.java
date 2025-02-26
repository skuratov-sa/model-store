package com.model_store.controller;

import com.model_store.exception.InvalidTokenException;
import com.model_store.model.CustomUserDetails;
import com.model_store.model.dto.LoginRequest;
import com.model_store.service.JwtService;
import lombok.RequiredArgsConstructor;
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

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {
    private final ReactiveAuthenticationManager authenticationManager;
    private final ReactiveUserDetailsService userDetailsService;
    private final JwtService jwtService;

    @PostMapping("/login")
    public Mono<ResponseEntity<Map<String, String>>> login(@RequestBody Mono<LoginRequest> request) {
        return request.flatMap(req ->
                authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(req.getLogin(), req.getPassword()))
                        .flatMap(auth -> userDetailsService.findByUsername(req.getLogin()))
                        .map(userDetails -> {
                            CustomUserDetails customUserDetails = (CustomUserDetails) userDetails;
                            String accessToken = jwtService.generateAccessToken(customUserDetails);
                            String refreshToken = jwtService.generateRefreshToken(customUserDetails);

                            Map<String, String> tokens = new HashMap<>();
                            tokens.put("access_token", accessToken);
                            tokens.put("refresh_token", refreshToken);
                            return ResponseEntity.ok(tokens);
                        })
        );
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

}