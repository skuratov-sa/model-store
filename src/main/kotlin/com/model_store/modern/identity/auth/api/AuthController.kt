package com.model_store.modern.identity.auth.api

import com.model_store.modern.identity.auth.application.AuthUseCases
import com.model_store.modern.shared.domain.Actor
import com.model_store.modern.shared.domain.TokenType
import org.springframework.context.annotation.Profile
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import org.springframework.http.HttpStatus
import java.time.Instant

data class AuthLoginRequest(val mail: String? = null, val password: String? = null) {
    override fun toString(): String = "AuthLoginRequest(mail=$mail, password=[REDACTED])"
}

@RestController
@Profile("modern")
@RequestMapping("/auth")
class AuthController(private val auth: AuthUseCases) {
    @PostMapping("/login")
    fun login(@RequestBody request: AuthLoginRequest): Map<String, String> = auth.login(request.mail, request.password)

    @PostMapping("/refresh")
    fun refresh(@RequestHeader("X-Refresh-Token") token: String): ResponseEntity<String> =
        ResponseEntity.ok(auth.refresh(token))

    @GetMapping("/profile")
    fun profile(authentication: Authentication): Map<String, Any?> {
        val actor = authentication.principal as? Actor ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        // Integration 01.2 must retain the verified Jwt in authentication credentials.
        val jwt = authentication.credentials as? Jwt
            ?: throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Verified JWT claims unavailable")
        val id = (jwt.claims["id"] as? Number)?.toLong()
        val type = TokenType.fromClaim(jwt.claims["type"] as? String)
        if (id == null || id != actor.participantId || type == null || type != actor.tokenType ||
            jwt.claims["role"] != actor.role) throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        return jwt.claims.mapValues { (_, value) -> if (value is Instant) value.epochSecond else value }
    }
}
