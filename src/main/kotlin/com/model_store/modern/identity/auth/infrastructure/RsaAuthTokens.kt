package com.model_store.modern.identity.auth.infrastructure

import com.model_store.modern.identity.auth.application.AuthTokens
import com.model_store.modern.identity.auth.domain.AuthAccount
import com.model_store.modern.identity.auth.domain.AuthFailure
import com.model_store.modern.identity.auth.domain.AuthStatus
import io.jsonwebtoken.JwtException
import io.jsonwebtoken.Jwts
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.Date

@Component
@Profile("modern")
class RsaAuthTokens(
    @Value("\${app.private-key-path}") privateKeyPath: String,
    @Value("\${app.public-key-path}") publicKeyPath: String,
) : AuthTokens {
    private val privateKey: PrivateKey = KeyFactory.getInstance("RSA").generatePrivate(
        PKCS8EncodedKeySpec(Base64.getDecoder().decode(clean(loadKey(privateKeyPath)))),
    )
    private val publicKey: PublicKey = KeyFactory.getInstance("RSA").generatePublic(
        X509EncodedKeySpec(Base64.getDecoder().decode(clean(loadKey(publicKeyPath)))),
    )

    override fun issue(account: AuthAccount): Map<String, String> = mapOf(
        "access_token" to access(account, false),
        "refresh_token" to Jwts.builder()
            .subject(account.mail)
            .expiration(Date.from(Instant.now().plus(Duration.ofDays(30))))
            .claim("type", "refresh")
            .signWith(privateKey)
            .compact(),
    )

    override fun refresh(token: String, findAccount: (String) -> AuthAccount?): String {
        val raw = token.removePrefix("Bearer ")
        val claims = try {
            Jwts.parser().verifyWith(publicKey).build().parseSignedClaims(raw).payload
        } catch (_: JwtException) {
            throw AuthFailure.InvalidRefresh()
        } catch (_: IllegalArgumentException) {
            throw AuthFailure.InvalidRefresh()
        }
        if (claims["type"] != "refresh") throw AuthFailure.InvalidRefresh()
        if (claims.expiration?.after(Date()) != true) throw AuthFailure.InvalidRefresh()
        val mail = claims.subject?.takeIf { it.isNotBlank() } ?: throw AuthFailure.InvalidRefresh()
        val issuedBy = claims["issuedBy"]
        if (issuedBy != null && issuedBy != "admin") throw AuthFailure.InvalidRefresh()
        val account = findAccount(mail) ?: throw AuthFailure.InvalidRefresh()
        val agent = issuedBy == "admin"
        if (agent && (!account.isAgent || account.status != AuthStatus.ACTIVE)) throw AuthFailure.InvalidRefresh()
        // Ordinary legacy refresh re-reads the participant but does not recheck status.
        return access(account, agent)
    }

    private fun access(account: AuthAccount, agent: Boolean): String {
        val now = Instant.now()
        val builder = Jwts.builder()
            .subject(account.login)
            .expiration(Date.from(now.plus(if (agent) Duration.ofHours(24) else Duration.ofMinutes(30))))
            .claim("type", if (agent) "agent_access" else "access")
            .claim("id", account.id)
            .claim("login", account.login)
            .claim("email", account.mail)
            .claim("fullName", account.fullName)
            .claim("imageId", account.imageId)
            .claim("role", account.role)
        if (agent) builder.issuedAt(Date.from(now)).claim("issuedBy", "admin")
        return builder.signWith(privateKey).compact()
    }

    private fun clean(pem: String) = pem
        .replace("-----BEGIN PRIVATE KEY-----", "")
        .replace("-----END PRIVATE KEY-----", "")
        .replace("-----BEGIN PUBLIC KEY-----", "")
        .replace("-----END PUBLIC KEY-----", "")
        .replace(Regex("\\s+"), "")

    private fun loadKey(keyPath: String): String {
        val path = Path.of(keyPath)
        return when {
            Files.isRegularFile(path) -> Files.readString(path)
            path.isAbsolute -> error("JWT key not found: $keyPath")
            ClassPathResource(keyPath).exists() ->
                ClassPathResource(keyPath).inputStream.bufferedReader().use { it.readText() }
            Files.isRegularFile(Path.of("src", "main", "resources").resolve(path)) ->
                Files.readString(Path.of("src", "main", "resources").resolve(path))
            else -> error("JWT key not found: $keyPath")
        }
    }
}
