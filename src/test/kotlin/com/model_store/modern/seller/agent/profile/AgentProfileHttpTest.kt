package com.model_store.modern.seller.agent.profile

import com.amazonaws.services.s3.AmazonS3
import com.fasterxml.jackson.databind.ObjectMapper
import com.model_store.modern.identity.agent.application.VerifyAgentAccess
import com.model_store.modern.identity.agent.domain.AgentTokenFailure
import com.model_store.modern.identity.auth.application.AuthUseCases
import com.model_store.modern.identity.verification.application.VerificationMail
import com.model_store.modern.seller.agent.profile.application.AgentProfileAccess
import com.model_store.modern.seller.agent.profile.application.AgentProfileDenied
import com.model_store.modern.shared.domain.Actor
import com.model_store.modern.shared.domain.TokenType
import io.jsonwebtoken.Jwts
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.UUID

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("modern")
class AgentProfileHttpTest {
    @field:MockitoBean lateinit var s3: AmazonS3
    @field:MockitoBean lateinit var mail: VerificationMail
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var auth: AuthUseCases
    @Autowired lateinit var json: ObjectMapper
    @Autowired lateinit var access: AgentProfileAccess
    @Autowired lateinit var verify: VerifyAgentAccess
    @Value("\${local.server.port}") private var port: Int = 0
    private val client = HttpClient.newHttpClient()

    @Test
    fun `admin CRUD keeps owner IDs and legacy JSON shapes`() {
        val admin = person("ADMIN", true, false)
        val user = person("USER", true, false)
        val agent = person("USER", true, true)
        val anotherAgent = person("USER", true, true)
        val token = auth.login(admin.mail, "password").getValue("access_token")
        val userToken = auth.login(user.mail, "password").getValue("access_token")
        val root = "/admin/actions/agents/${agent.id}"
        mapOf("accounts" to "ACCOUNT_NOT_FOUND", "transfers" to "TRANSFER_NOT_FOUND",
            "social-networks" to "SOCIAL_NETWORK_NOT_FOUND").forEach { (suffix, code) ->
            val empty = call("GET", "$root/$suffix", token)
            assertEquals(404, empty.statusCode(), empty.body())
            assertEquals(code, json.readTree(empty.body())["code"].asText())
        }
        val originalProfile = call("GET", "$root/profile", token)
        assertEquals(200, originalProfile.statusCode(), originalProfile.body())
        assertEquals(setOf("id", "login", "fullName", "phoneNumber", "imageId", "deadlineSending", "deadlinePayment", "status"),
            json.readTree(originalProfile.body()).fieldNames().asSequence().toSet())
        val imageId = jdbc.queryForObject(
            "INSERT INTO image(tag, status, uploaded_by) VALUES ('PARTICIPANT', 'ACTIVE', ?) RETURNING id",
            Long::class.java, agent.id)!!
        val profilePatch = call("PUT", "$root/profile", token,
            """{"fullName":"Updated Agent","deadlineSending":7,"imageId":$imageId}""")
        assertEquals(200, profilePatch.statusCode(), profilePatch.body())
        assertEquals("Updated Agent", json.readTree(profilePatch.body())["fullName"].asText())
        assertEquals(imageId, json.readTree(profilePatch.body())["imageId"].asLong())
        assertEquals(7, json.readTree(call("PUT", "$root/profile", token, "{}").body())["deadlineSending"].asInt())
        val missingImage = call("PUT", "$root/profile", token,
            """{"fullName":"No Save","imageId":999999999}""")
        assertEquals(404, missingImage.statusCode(), missingImage.body())
        assertEquals("NOT_FOUND", json.readTree(missingImage.body())["code"].asText())
        assertEquals("Updated Agent", json.readTree(call("GET", "$root/profile", token).body())["fullName"].asText())
        val invalidDeadline = call("PUT", "$root/profile", token,
            """{"deadlinePayment":100000}""")
        assertEquals(400, invalidDeadline.statusCode(), invalidDeadline.body())
        assertEquals("BAD_REQUEST", json.readTree(invalidDeadline.body())["code"].asText())
        val otherImage = jdbc.queryForObject(
            "INSERT INTO image(tag, status, uploaded_by) VALUES ('PARTICIPANT', 'ACTIVE', ?) RETURNING id",
            Long::class.java, agent.id)!!
        val rollback = call("PUT", "$root/profile", token,
            """{"phoneNumber":"${"x".repeat(41)}","imageId":$otherImage}""")
        assertEquals(409, rollback.statusCode(), rollback.body())
        assertFalse(rollback.body().contains("dbMessage"))
        assertEquals(imageId, json.readTree(call("GET", "$root/profile", token).body())["imageId"].asLong())
        assertEquals(200, call("GET", "$root/addresses", token).statusCode())

        val address = call("POST", "$root/addresses", token,
            """{"country":"RU","city":"Moscow","street":"Main","houseNumber":"1","index":123456}""")
        assertEquals(200, address.statusCode(), address.body())
        val addressId = address.body().toLong()
        val addresses = call("GET", "$root/addresses", token)
        assertEquals(200, addresses.statusCode())
        assertEquals("Moscow", json.readTree(addresses.body())[0]["city"].asText())
        assertEquals(setOf("id", "country", "city", "street", "houseNumber", "apartmentNumber", "index", "status", "fullAddress"),
            json.readTree(addresses.body())[0].fieldNames().asSequence().toSet())
        assertEquals(200, call("PUT", "$root/addresses/$addressId", token,
            """{"city":"Kazan"}""").statusCode())
        assertEquals(404, call("PUT", "/admin/actions/agents/${anotherAgent.id}/addresses/$addressId", token,
            """{"city":"Stolen"}""").statusCode())

        assertEquals(200, call("POST", "$root/accounts", token,
            """{"transferMoney":"BANK_CARD","entityValue":"card-1"}""").statusCode())
        val accountId = jdbc.queryForObject("SELECT id FROM account WHERE participant_id = ?", Long::class.java, agent.id)!!
        val accountList = call("GET", "$root/accounts", token)
        assertEquals(200, accountList.statusCode())
        assertEquals(agent.id, json.readTree(accountList.body())[0]["participantId"].asLong())
        assertEquals(setOf("id", "transferMoney", "username", "entityValue", "comment", "participantId"),
            json.readTree(accountList.body())[0].fieldNames().asSequence().toSet())
        val deniedList = call("GET", "$root/accounts", userToken)
        assertEquals(403, deniedList.statusCode())
        assertFalse(deniedList.body().contains("card-1"))
        assertEquals(409, call("POST", "$root/accounts", token,
            """{"transferMoney":"BANK_CARD","entityValue":"card-1"}""").statusCode())
        assertEquals(404, call("PUT", "/admin/actions/agents/${anotherAgent.id}/accounts/$accountId", token,
            """{"transferMoney":"CASH"}""").statusCode())
        assertEquals(200, call("PUT", "$root/accounts/$accountId", token,
            """{"transferMoney":"BANK_SBP","entityValue":"card-2"}""").statusCode())

        assertEquals(200, call("POST", "$root/transfers", token,
            """{"sending":"PRODUCT_PICKUP","price":100,"currency":"RUB"}""").statusCode())
        val transferId = jdbc.queryForObject("SELECT id FROM transfer WHERE participant_id = ?", Long::class.java, agent.id)!!
        val transferList = call("GET", "$root/transfers", token)
        assertEquals(200, transferList.statusCode())
        assertEquals(setOf("id", "sending", "price", "currency", "participantId", "status"),
            json.readTree(transferList.body())[0].fieldNames().asSequence().toSet())
        assertEquals(409, call("POST", "$root/transfers", token,
            """{"sending":"PRODUCT_PICKUP","price":100,"currency":"RUB"}""").statusCode())
        assertEquals(404, call("DELETE", "/admin/actions/agents/${anotherAgent.id}/transfers/$transferId", token).statusCode())
        assertEquals(200, call("PUT", "$root/transfers/$transferId", token,
            """{"sending":"PRODUCT_PICKUP","price":101,"currency":"RUB"}""").statusCode())

        assertEquals(200, call("POST", "$root/social-networks", token,
            """{"type":"TELEGRAM","login":"agent"}""").statusCode())
        val socialId = jdbc.queryForObject("SELECT id FROM social_network WHERE participant_id = ?", Long::class.java, agent.id)!!
        val socialList = call("GET", "$root/social-networks", token)
        assertEquals(200, socialList.statusCode())
        assertEquals(setOf("id", "type", "login", "participantId"),
            json.readTree(socialList.body())[0].fieldNames().asSequence().toSet())
        assertEquals(409, call("POST", "$root/social-networks", token,
            """{"type":"TELEGRAM","login":"other"}""").statusCode())
        assertEquals(404, call("PUT", "/admin/actions/agents/${anotherAgent.id}/social-networks/$socialId", token,
            """{"type":"VK","login":"stolen"}""").statusCode())
        assertEquals(200, call("PUT", "$root/social-networks/$socialId", token,
            """{"type":"VK","login":"agent"}""").statusCode())

        assertEquals(200, call("DELETE", "$root/addresses/$addressId", token).statusCode())
        assertEquals(200, call("DELETE", "$root/accounts/$accountId", token).statusCode())
        assertEquals(200, call("DELETE", "$root/transfers/$transferId", token).statusCode())
        assertEquals(200, call("DELETE", "$root/social-networks/$socialId", token).statusCode())
    }

    @Test
    fun `ordinary user forged role refresh and revoked agent cannot read private details`() {
        val admin = person("ADMIN", true, false)
        val user = person("USER", true, false)
        val agent = person("USER", true, true)
        val blocked = person("USER", false, true)
        val adminToken = auth.login(admin.mail, "password").getValue("access_token")
        val userTokens = auth.login(user.mail, "password")
        val path = "/admin/actions/agents/${agent.id}/profile"
        assertEquals(401, call("GET", path, null).statusCode())
        assertEquals(403, call("GET", path, userTokens.getValue("access_token")).statusCode())
        assertEquals(401, call("GET", path, userTokens.getValue("refresh_token")).statusCode())
        val verifyToken = Jwts.builder().subject(admin.login).claim("id", admin.id).claim("role", "ADMIN")
            .claim("type", "verify").expiration(Date.from(Instant.now().plusSeconds(3600)))
            .signWith(privateKey).compact()
        assertEquals(401, call("GET", path, verifyToken).statusCode())
        val expired = Jwts.builder().subject(admin.login).claim("id", admin.id).claim("role", "ADMIN")
            .claim("type", "access").expiration(Date.from(Instant.now().minusSeconds(60)))
            .signWith(privateKey).compact()
        assertEquals(401, call("GET", path, expired).statusCode())
        val tokenParts = adminToken.split('.')
        val changed = if (tokenParts[2][0] == 'A') 'B' else 'A'
        val tampered = "${tokenParts[0]}.${tokenParts[1]}.$changed${tokenParts[2].substring(1)}"
        assertEquals(401, call("GET", path, tampered).statusCode())
        assertEquals(404, call("GET", "/admin/actions/agents/${user.id}/profile", adminToken).statusCode())
        assertEquals("BLOCKED", json.readTree(call("GET", "/admin/actions/agents/${blocked.id}/profile", adminToken).body())["status"].asText())
        assertEquals(200, call("GET", "/admin/actions/agents/${blocked.id}/addresses", adminToken).statusCode())
        assertEquals(200, call("PUT", "/admin/actions/agents/${blocked.id}/profile", adminToken,
            """{"fullName":"Blocked agent"}""").statusCode())
        jdbc.update("UPDATE participant SET status = 'DELETED'::participant_status WHERE id = ?", blocked.id)
        assertEquals("DELETED", json.readTree(call("GET", "/admin/actions/agents/${blocked.id}/profile", adminToken).body())["status"].asText())
        assertEquals(200, call("POST", "/admin/actions/agents/${blocked.id}/addresses", adminToken,
            """{"country":"RU","city":"Moscow"}""").statusCode())
        val forgedRole = Jwts.builder().subject(user.login).claim("id", user.id).claim("role", "ADMIN")
            .claim("type", "access").expiration(Date.from(Instant.now().plusSeconds(3600)))
            .signWith(privateKey).compact()
        val forgedResponse = call("GET", path, forgedRole)
        assertEquals(403, forgedResponse.statusCode(), forgedResponse.body())
        val agentTokenBody = call("POST", "/admin/actions/agents/${agent.id}/token", adminToken)
        assertEquals(200, agentTokenBody.statusCode())
        val agentToken = json.readTree(agentTokenBody.body())["accessToken"].asText()
        assertEquals(403, call("GET", path, agentToken).statusCode()) // shared chain is ADMIN-only
        val forgedAgentAdmin = Jwts.builder().subject(agent.login).claim("id", agent.id).claim("role", "ADMIN")
            .claim("type", "agent_access").claim("issuedBy", "admin")
            .expiration(Date.from(Instant.now().plusSeconds(3600))).signWith(privateKey).compact()
        assertEquals(403, call("GET", path, forgedAgentAdmin).statusCode())
        assertEquals(agent.id, verify.requireActiveAgent(Actor(agent.id, agent.login, "USER", TokenType.AGENT_ACCESS)))
        jdbc.update("UPDATE participant SET status = 'BLOCKED'::participant_status WHERE id = ?", agent.id)
        assertThrows(AgentTokenFailure.InvalidAgentAccess::class.java) {
            verify.requireActiveAgent(Actor(agent.id, agent.login, "USER", TokenType.AGENT_ACCESS))
        }
        assertThrows(AgentProfileDenied::class.java) {
            access.requireTarget(Actor(agent.id, agent.login, "USER", TokenType.AGENT_ACCESS), agent.id)
        }
        assertEquals(200, call("GET", path, adminToken).statusCode())
        jdbc.update("UPDATE participant SET role = 'USER'::participant_role WHERE id = ?", admin.id)
        assertEquals(403, call("GET", path, adminToken).statusCode())
        assertEquals(403, call("GET", "/admin/actions/agents/${blocked.id}/addresses", adminToken).statusCode())
        jdbc.update("UPDATE participant SET role = 'ADMIN'::participant_role WHERE id = ?", admin.id)
        jdbc.update("UPDATE participant SET status = 'BLOCKED'::participant_status WHERE id = ?", admin.id)
        assertEquals(403, call("GET", path, adminToken).statusCode())
    }

    private fun person(role: String, active: Boolean, agent: Boolean): Person {
        val login = "profile${UUID.randomUUID().toString().replace("-", "")}"
        val mail = "$login@example.test"
        val status = if (active) "ACTIVE" else "BLOCKED"
        val id = jdbc.queryForObject(
            """INSERT INTO participant(login, mail, full_name, password, role, status, is_agent, deadline_sending, deadline_payment)
                VALUES (?, ?, 'Agent Profile', ?, ?::participant_role, ?::participant_status, ?, 1, 1) RETURNING id""",
            Long::class.java, login, mail,
            org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("password"), role, status, agent,
        )!!
        return Person(id, login, mail)
    }

    private fun call(method: String, path: String, token: String?, body: String = ""): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI("http://localhost:$port$path"))
        if (token != null) builder.header("Authorization", "Bearer $token")
        if (body.isNotEmpty()) builder.header("Content-Type", "application/json")
        return client.send(builder.method(method, HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString())
    }

    private data class Person(val id: Long, val login: String, val mail: String)

    companion object {
        private val postgres = EmbeddedPostgres.start()
        private val privateKey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(
            Base64.getDecoder().decode(Files.readString(Path.of("src/test/resources/keys/test_private_key.pem"))
                .replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "")
                .replace(Regex("\\s+"), ""))))

        @JvmStatic @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) {
            val url = "jdbc:postgresql://localhost:${postgres.port}/postgres"
            registry.add("spring.datasource.url") { url }
            registry.add("spring.datasource.username") { "postgres" }
            registry.add("spring.datasource.password") { "" }
            registry.add("spring.flyway.url") { url }
            registry.add("spring.flyway.user") { "postgres" }
            registry.add("spring.flyway.password") { "" }
            registry.add("app.private-key-path") { "keys/test_private_key.pem" }
            registry.add("app.public-key-path") { "keys/test_public_key.pem" }
        }
    }
}
