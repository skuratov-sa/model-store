package com.model_store.modern.identity.agent.infrastructure

import com.model_store.modern.identity.agent.application.AgentTokenSigner
import com.model_store.modern.identity.agent.application.AgentTokens
import com.model_store.modern.identity.agent.domain.AgentTokenLifetime
import com.model_store.modern.identity.auth.domain.AuthAccount
import io.jsonwebtoken.Jwts
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Base64
import java.util.Date

@Component
@Profile("modern")
class RsaAgentTokenSigner(@Value("\${app.private-key-path}") privateKeyPath: String) : AgentTokenSigner {
    private val privateKey: PrivateKey = KeyFactory.getInstance("RSA").generatePrivate(
        PKCS8EncodedKeySpec(Base64.getDecoder().decode(loadKey(privateKeyPath)
            .replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "")
            .replace(Regex("\\s+"), ""))),
    )

    override fun issue(account: AuthAccount, lifetime: AgentTokenLifetime): AgentTokens {
        val localNow = LocalDateTime.now()
        val zone = ZoneId.systemDefault()
        val now = localNow.atZone(zone).toInstant()
        val accessExpiresAt = localNow.plus(lifetime.access).atZone(zone).toInstant()
        val refreshExpiresAt = localNow.plus(lifetime.refresh).atZone(zone).toInstant()
        val access = Jwts.builder()
            .subject(account.login)
            .issuedAt(Date.from(now))
            .expiration(Date.from(accessExpiresAt))
            .claim("type", "agent_access")
            .claim("issuedBy", "admin")
            .claim("id", account.id)
            .claim("login", account.login)
            .claim("email", account.mail)
            .claim("fullName", account.fullName)
            // Legacy findByMail supplies 0 when no participant image exists.
            .claim("imageId", account.imageId ?: 0L)
            .claim("role", account.role)
            .signWith(privateKey).compact()
        val refresh = Jwts.builder()
            .subject(account.mail)
            .expiration(Date.from(refreshExpiresAt))
            .claim("type", "refresh")
            .claim("issuedBy", "admin")
            .signWith(privateKey).compact()
        return AgentTokens(access, accessExpiresAt, refresh, refreshExpiresAt)
    }

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
