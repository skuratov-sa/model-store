package com.model_store.modern.identity.auth

import com.amazonaws.services.s3.AmazonS3
import com.model_store.modern.identity.auth.api.AuthController
import com.model_store.modern.identity.auth.api.AuthLoginRequest
import com.model_store.modern.identity.auth.application.AuthUseCases
import com.model_store.modern.identity.auth.domain.AuthAccount
import com.model_store.modern.identity.auth.domain.AuthStatus
import com.model_store.modern.identity.auth.domain.AuthFailure
import com.model_store.modern.identity.verification.application.VerificationMail
import com.model_store.modern.identity.verification.application.VerificationCodes
import com.model_store.modern.shared.domain.Actor
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtException
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.Date
import jakarta.servlet.Filter
import org.springframework.web.context.WebApplicationContext
import com.model_store.modern.identity.auth.api.AuthErrorHandler
import com.model_store.configuration.property.ApplicationProperties
import com.model_store.model.CustomUserDetails
import com.model_store.model.constant.ParticipantStatus
import com.model_store.repository.ParticipantRepository
import com.model_store.service.impl.JwtServiceImpl
import org.mockito.Mockito.mock
import org.springframework.security.core.userdetails.ReactiveUserDetailsService
import org.springframework.web.server.ResponseStatusException

@SpringBootTest
@ActiveProfiles("modern")
class AuthCompatibilityTest {
    @field:MockitoBean lateinit var s3: AmazonS3
    @field:MockitoBean lateinit var mail: VerificationMail
    @Autowired lateinit var auth: AuthUseCases
    @Autowired lateinit var controller: AuthController
    @Autowired lateinit var decoder: JwtDecoder
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var webContext: WebApplicationContext
    @Autowired lateinit var errors: AuthErrorHandler
    @Autowired lateinit var codes: VerificationCodes

    @Test
    fun `login issues legacy compatible claims and profile returns the same JSON values`() {
        val id = account("ACTIVE")
        val mail = mail(id)
        val tokens = auth.login(mail, "password")
        assertEquals(setOf("access_token", "refresh_token"), tokens.keys)
        tokens.values.forEach { token ->
            val header = String(Base64.getUrlDecoder().decode(token.substringBefore('.')))
            assertTrue(header.contains("\"alg\":\"RS256\""))
        }
        val access = decoder.decode(tokens.getValue("access_token"))
        assertEquals("access", access.getClaimAsString("type"))
        assertEquals(id, (access.claims["id"] as Number).toLong())
        assertEquals("USER", access.getClaimAsString("role"))
        assertEquals(mail, access.getClaimAsString("email"))
        assertEquals("Full Name", access.getClaimAsString("fullName"))
        assertEquals(jdbc.queryForObject("SELECT login FROM participant WHERE id = ?", String::class.java, id), access.subject)
        assertNull(access.claims["imageId"])
        assertTrue(kotlin.math.abs(Duration.ofMinutes(30).seconds - (access.expiresAt!!.epochSecond - Instant.now().epochSecond)) <= 2)
        val profile = controller.profile(UsernamePasswordAuthenticationToken(Actor(id, "user$id", "USER"), access, emptyList()))
        assertEquals(access.expiresAt!!.epochSecond, profile["exp"])
        assertEquals(id, (profile["id"] as Number).toLong())
        assertEquals("USER", profile["role"])
        assertFalse(profile.containsKey("password"))
        val mismatchedActor = UsernamePasswordAuthenticationToken(Actor(id + 1, "other", "USER"), access, emptyList())
        assertEquals(401, assertThrows(ResponseStatusException::class.java) { controller.profile(mismatchedActor) }.statusCode.value())
        val invalidClaims = Jwt.withTokenValue("not-displayed").header("alg", "RS256")
            .claim("id", "not-a-number").claim("role", "USER").claim("type", "access").build()
        assertEquals(401, assertThrows(ResponseStatusException::class.java) {
            controller.profile(UsernamePasswordAuthenticationToken(Actor(id, "user$id", "USER"), invalidClaims, emptyList()))
        }.statusCode.value())

        assertThrows(JwtException::class.java) { decoder.decode(tokens.getValue("refresh_token")) }
        val refreshClaims = Jwts.parser().verifyWith(keyPair.public).build()
            .parseSignedClaims(tokens.getValue("refresh_token")).payload
        assertEquals(mail, refreshClaims.subject)
        assertEquals("refresh", refreshClaims["type"])
        assertEquals(setOf("sub", "exp", "type"), refreshClaims.keys)
        assertTrue(kotlin.math.abs(Duration.ofDays(30).seconds - (refreshClaims.expiration.time / 1000 - Instant.now().epochSecond)) <= 2)
        val refreshed = auth.refresh("Bearer " + tokens.getValue("refresh_token"))
        assertEquals("access", decoder.decode(refreshed).getClaimAsString("type"))
        val imageId = jdbc.queryForObject(
            """INSERT INTO image(filename, tag, status, entity_id)
                VALUES ('auth-test.png', 'PARTICIPANT', 'ACTIVE', ?) RETURNING id""",
            Long::class.java, id,
        )!!
        val withImage = decoder.decode(auth.login(mail, "password").getValue("access_token"))
        assertEquals(imageId, (withImage.claims["imageId"] as Number).toLong())
    }

    @Test
    fun `legacy signed refresh remains usable and bad token types and signatures fail`() {
        val id = account("ACTIVE")
        val mail = mail(id)
        val legacyRefresh = Jwts.builder().subject(mail)
            .expiration(Date.from(Instant.now().plusSeconds(3600)))
            .claim("type", "refresh").signWith(keyPair.private).compact()
        assertEquals(id, (decoder.decode(auth.refresh(legacyRefresh)).claims["id"] as Number).toLong())
        val access = auth.login(mail, "password").getValue("access_token")
        val verify = Jwts.builder().claim("type", "verify").claim("id", id)
            .expiration(Date.from(Instant.now().plusSeconds(3600))).signWith(keyPair.private).compact()
        val expired = Jwts.builder().subject(mail).claim("type", "refresh")
            .expiration(Date.from(Instant.now().minusSeconds(60))).signWith(keyPair.private).compact()
        val expiredAccess = Jwts.builder().subject("expired").claim("type", "access")
            .claim("id", id).claim("role", "USER")
            .expiration(Date.from(Instant.now().minusSeconds(120))).signWith(keyPair.private).compact()
        val noExpiry = Jwts.builder().subject(mail).claim("type", "refresh").signWith(keyPair.private).compact()
        val forged = legacyRefresh.dropLast(1) + if (legacyRefresh.last() == 'A') "B" else "A"
        val hmac = Jwts.builder().subject(mail).claim("type", "refresh")
            .expiration(Date.from(Instant.now().plusSeconds(3600)))
            .signWith(Keys.hmacShaKeyFor(ByteArray(32) { 7 })).compact()
        val unsigned = "eyJhbGciOiJub25lIn0.eyJ0eXBlIjoicmVmcmVzaCJ9."
        listOf(access, verify, expired, noExpiry, forged, hmac, unsigned, "garbage").forEach {
            assertThrows(AuthFailure.InvalidRefresh::class.java) { auth.refresh(it) }
        }
        assertThrows(JwtException::class.java) { decoder.decode(verify) }
        assertThrows(JwtException::class.java) { decoder.decode(expiredAccess) }
        val malformedAccesses = listOf(
            Jwts.builder().subject("bad").claim("type", "access").claim("id", "42").claim("role", "USER"),
            Jwts.builder().subject("bad").claim("type", "access").claim("id", -1).claim("role", "USER"),
            Jwts.builder().subject("bad").claim("type", "access").claim("id", id).claim("role", ""),
            Jwts.builder().subject("bad").claim("type", "agent_access").claim("id", id).claim("role", "ADMIN"),
        )
        malformedAccesses.forEach { builder ->
            val token = builder.expiration(Date.from(Instant.now().plusSeconds(3600))).signWith(keyPair.private).compact()
            assertThrows(JwtException::class.java) { decoder.decode(token) }
        }
    }

    @Test
    fun `legacy service issued access and refresh are accepted by modern implementation`() {
        val id = account("ACTIVE")
        val mail = mail(id)
        val login = jdbc.queryForObject("SELECT login FROM participant WHERE id = ?", String::class.java, id)!!
        val properties = ApplicationProperties().apply {
            privateKeyPath = privatePath.toString()
            publicKeyPath = publicPath.toString()
        }
        val legacy = JwtServiceImpl(
            mock(ReactiveUserDetailsService::class.java), properties, mock(ParticipantRepository::class.java),
        )
        val user = CustomUserDetails.builder().id(id).login(login).email(mail).fullName("Full Name")
            .role("USER").password("unused-hash").status(ParticipantStatus.ACTIVE).build()
        val legacyAccess = legacy.generateAccessToken(user, Duration.ofMinutes(30))
        val legacyRefresh = legacy.generateRefreshToken(user)
        assertEquals(id, (decoder.decode(legacyAccess).claims["id"] as Number).toLong())
        val modernClaims = legacy.parseAccessToken(auth.login(mail, "password").getValue("access_token"))
        val legacyClaims = legacy.parseAccessToken(legacyAccess)
        assertEquals(legacyClaims.keys - "exp", modernClaims.keys - "exp")
        (legacyClaims.keys - "exp").forEach { claim -> assertEquals(legacyClaims[claim], modernClaims[claim]) }
        assertEquals("access", decoder.decode(auth.refresh(legacyRefresh)).getClaimAsString("type"))
    }

    @Test
    fun `status and password checks match legacy login while verified ID issues tokens`() {
        val active = account("ACTIVE")
        assertThrows(BadCredentialsException::class.java) { auth.login(mail(active), "wrong") }
        assertThrows(BadCredentialsException::class.java) { auth.login("missing-${System.nanoTime()}@test.invalid", "password") }
        val waiting = account("WAITING_VERIFY")
        assertThrows(AuthFailure.WaitingVerify::class.java) { auth.login(mail(waiting), "password") }
        assertThrows(AuthFailure.InvalidRefresh::class.java) { auth.issue(waiting) }
        jdbc.update("UPDATE participant SET status = 'ACTIVE'::participant_status WHERE id = ?", waiting)
        assertEquals(waiting, (decoder.decode(auth.issue(waiting).getValue("access_token")).claims["id"] as Number).toLong())
        listOf("BLOCKED", "DELETED").forEach { status ->
            val id = account(status)
            assertThrows(AuthFailure.Blocked::class.java) { auth.login(mail(id), "password") }
            assertThrows(AuthFailure.InvalidRefresh::class.java) { auth.issue(id) }
            val legacyRefresh = Jwts.builder().subject(mail(id)).claim("type", "refresh")
                .expiration(Date.from(Instant.now().plusSeconds(3600))).signWith(keyPair.private).compact()
            // Legacy ordinary refresh re-reads this account without checking its status.
            assertEquals(id, (decoder.decode(auth.refresh(legacyRefresh)).claims["id"] as Number).toLong())
            val agentRefresh = Jwts.builder().subject(mail(id)).claim("type", "refresh").claim("issuedBy", "admin")
                .expiration(Date.from(Instant.now().plusSeconds(3600))).signWith(keyPair.private).compact()
            assertThrows(AuthFailure.InvalidRefresh::class.java) { auth.refresh(agentRefresh) }
        }
        val agent = account("ACTIVE")
        jdbc.update("UPDATE participant SET is_agent = true WHERE id = ?", agent)
        val agentRefresh = Jwts.builder().subject(mail(agent)).claim("type", "refresh").claim("issuedBy", "admin")
            .expiration(Date.from(Instant.now().plusSeconds(3600))).signWith(keyPair.private).compact()
        val agentAccess = decoder.decode(auth.refresh(agentRefresh))
        assertEquals("agent_access", agentAccess.getClaimAsString("type"))
        assertEquals("admin", agentAccess.getClaimAsString("issuedBy"))
        assertTrue(kotlin.math.abs(Duration.ofHours(24).seconds - (agentAccess.expiresAt!!.epochSecond - Instant.now().epochSecond)) <= 2)
        assertThrows(AuthFailure.InvalidRefresh::class.java) { auth.refresh(agentAccess.tokenValue) }
        assertFalse(AuthAccount(agent, "agent", mail(agent), null, "secret-hash", "USER", AuthStatus.ACTIVE, null, true)
            .toString().contains("secret-hash"))
    }

    @Test
    fun `MVC response shapes and secured profile use verified access only`() {
        val id = account("ACTIVE")
        val mail = mail(id)
        val endpoint = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(errors).build()
        val login = endpoint.perform(post("/auth/login").contentType("application/json")
            .content("""{"mail":"$mail","password":"password"}""")).andReturn().response
        assertEquals(200, login.status)
        assertTrue(login.contentAsString.contains("access_token"))
        assertTrue(login.contentAsString.contains("refresh_token"))
        val badLogin = endpoint.perform(post("/auth/login").contentType("application/json")
            .content("""{"mail":"$mail","password":"wrong"}""")).andReturn().response
        assertEquals(401, badLogin.status)
        assertTrue(badLogin.contentAsString.contains("BAD_CREDENTIALS"))
        assertFalse(badLogin.contentAsString.contains("wrong"))
        assertFalse(AuthLoginRequest(mail, "password").toString().contains("password=password"))
        val badRefresh = endpoint.perform(post("/auth/refresh").header("X-Refresh-Token", "garbage"))
            .andReturn().response
        assertEquals(401, badRefresh.status)
        assertTrue(badRefresh.contentAsString.contains("TOKEN_INVALID_OR_EXPIRED"))

        val access = auth.login(mail, "password").getValue("access_token")
        val refresh = auth.login(mail, "password").getValue("refresh_token")
        val securedBuilder = MockMvcBuilders.webAppContextSetup(webContext)
        securedBuilder.addFilters<DefaultMockMvcBuilder>(webContext.getBean("springSecurityFilterChain") as Filter)
        val secured = securedBuilder.build()
        // Shared converter currently erases the verified Jwt; integration 01.2 must change this to 200.
        assertEquals(503, secured.perform(get("/auth/profile").header("Authorization", "Bearer $access"))
            .andReturn().response.status)
        listOf(refresh, "garbage").forEach { token ->
            assertEquals(401, secured.perform(get("/auth/profile").header("Authorization", "Bearer $token"))
                .andReturn().response.status)
        }
        assertEquals(401, secured.perform(get("/auth/profile")).andReturn().response.status)
        // Exact public route matchers belong to integration 01.2.
        assertEquals(401, secured.perform(post("/auth/login").contentType("application/json")
            .content("""{"mail":"$mail","password":"password"}""")).andReturn().response.status)
        assertEquals(401, secured.perform(post("/auth/refresh").header("X-Refresh-Token", refresh))
            .andReturn().response.status)
        assertEquals(403, secured.perform(get("/modern/check/admin").header("Authorization", "Bearer $access"))
            .andReturn().response.status)
        jdbc.update("UPDATE participant SET role = 'ADMIN'::participant_role WHERE id = ?", id)
        val adminAccess = auth.login(mail, "password").getValue("access_token")
        assertEquals("ADMIN", decoder.decode(adminAccess).getClaimAsString("role"))
        assertEquals(200, secured.perform(get("/modern/check/admin").header("Authorization", "Bearer $adminAccess"))
            .andReturn().response.status)
        assertEquals(401, secured.perform(get("/modern/check/admin").header("Authorization", "Bearer $refresh"))
            .andReturn().response.status)
    }

    @Test
    fun `public verify-code activates participant and returns access and refresh`() {
        val id = account("WAITING_VERIFY")
        codes.store(id, "12345")
        val builder = MockMvcBuilders.webAppContextSetup(webContext)
        builder.addFilters<DefaultMockMvcBuilder>(webContext.getBean("springSecurityFilterChain") as Filter)
        val response = builder.build().perform(post("/auth/verify-code").contentType("application/json")
            .content("""{"userId":$id,"code":"12345"}""")).andReturn().response
        assertEquals(200, response.status)
        assertTrue(response.contentAsString.contains("access_token"))
        assertTrue(response.contentAsString.contains("refresh_token"))
        assertEquals("ACTIVE", jdbc.queryForObject("SELECT status::text FROM participant WHERE id = ?", String::class.java, id))
        val replay = builder.build().perform(post("/auth/verify-code").contentType("application/json")
            .content("""{"userId":$id,"code":"12345"}""")).andReturn().response
        assertEquals(400, replay.status)
        assertFalse(replay.contentAsString.contains("access_token"))
    }

    private fun account(status: String): Long {
        val marker = System.nanoTime()
        return jdbc.queryForObject(
            """INSERT INTO participant(login, mail, full_name, password, role, status, deadline_sending, deadline_payment)
                VALUES (?, ?, 'Full Name', ?, 'USER', ?::participant_status, 1, 1) RETURNING id""",
            Long::class.java, "user$marker", "user$marker@test.invalid", BCryptPasswordEncoder().encode("password"), status,
        )!!
    }

    private fun mail(id: Long) = jdbc.queryForObject("SELECT mail FROM participant WHERE id = ?", String::class.java, id)!!

    companion object {
        private val postgres = EmbeddedPostgres.start()
        private val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        private val privatePath = Files.createTempFile("auth-private-", ".pem").also {
            Files.writeString(it, "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(keyPair.private.encoded) + "\n-----END PRIVATE KEY-----")
        }
        private val publicPath = Files.createTempFile("auth-public-", ".pem").also {
            Files.writeString(it, "-----BEGIN PUBLIC KEY-----\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(keyPair.public.encoded) + "\n-----END PUBLIC KEY-----")
        }

        @JvmStatic @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            val url = "jdbc:postgresql://localhost:${postgres.port}/postgres"
            registry.add("spring.datasource.url") { url }
            registry.add("spring.datasource.username") { "postgres" }
            registry.add("spring.datasource.password") { "" }
            registry.add("spring.flyway.url") { url }
            registry.add("spring.flyway.user") { "postgres" }
            registry.add("spring.flyway.password") { "" }
            registry.add("app.private-key-path") { privatePath.toString() }
            registry.add("app.public-key-path") { publicPath.toString() }
        }
    }
}
