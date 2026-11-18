package com.model_store.modern.catalog.giveaway.admin

import com.fasterxml.jackson.databind.ObjectMapper
import io.jsonwebtoken.Jwts
import org.junit.jupiter.api.Assertions.*
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import java.net.URI
import java.net.http.*
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.time.Duration
import java.time.Instant
import java.util.*

/** New generated local keys only. Never opens the repository's RSA resources. */
internal class Giveaway221Keys : AutoCloseable {
    val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val directory = Files.createTempDirectory("giveaway221-keys")
    val publicPath = write("public.pem", "PUBLIC KEY", pair.public.encoded)
    val privatePath = write("private.pem", "PRIVATE KEY", pair.private.encoded)
    private fun write(file: String, type: String, bytes: ByteArray): Path =
        Files.writeString(directory.resolve(file), "-----BEGIN $type-----\n" +
            Base64.getMimeEncoder(64, byteArrayOf(10)).encodeToString(bytes) + "\n-----END $type-----\n")
    fun token(id: Long, role: String = "ADMIN", type: String = "access"): String = Jwts.builder()
        .claim("id", id).claim("login", "giveaway221").claim("role", role).claim("type", type)
        .apply { if (type == "agent_access") claim("issuedBy", "admin") }
        .expiration(Date.from(Instant.now().plusSeconds(3600))).signWith(pair.private).compact()
    override fun close() {
        Files.deleteIfExists(privatePath); Files.deleteIfExists(publicPath); Files.deleteIfExists(directory)
    }
}

/** Reusable real HTTP contract for Spring tests and an actual frozen executable JAR. */
internal class CurrentGiveawayAdminContract(
    val jdbc: JdbcTemplate, private val port: Int, val keys: Giveaway221Keys,
) : AutoCloseable {
    val json = ObjectMapper()
    private val http = HttpClient.newBuilder().proxy(java.net.ProxySelector.of(null))
        .connectTimeout(Duration.ofSeconds(3)).build()
    fun request(method: String, path: String, jwt: String?, body: String? = null): HttpResponse<String> =
        http.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
            .timeout(Duration.ofSeconds(20)).apply {
                if (jwt != null) header("Authorization", "Bearer $jwt")
                if (body != null) header("Content-Type", "application/json")
                method(method, body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody())
            }.build(), HttpResponse.BodyHandlers.ofString())
    fun ok(response: HttpResponse<String>): String {
        assertEquals(200, response.statusCode(), response.body())
        return response.body()
    }
    fun denied(response: HttpResponse<String>) {
        assertEquals(403, response.statusCode(), response.body())
        assertEquals("ACCESS_DENIED", json.readTree(response.body())["code"].asText())
    }
    fun participant(role: String = "ADMIN", agent: Boolean = false): Long {
        val login = "giveaway221-${UUID.randomUUID()}"
        return jdbc.queryForObject("""INSERT INTO participant(login,mail,password,status,role,is_agent)
            VALUES (?,?,?,'ACTIVE',?::participant_role,?) RETURNING id""", Long::class.java,
            login, "$login@example.test", BCryptPasswordEncoder().encode("local221-password"), role, agent)!!
    }
    fun login(id: Long): String {
        val mail = jdbc.queryForObject("SELECT mail FROM participant WHERE id=?", String::class.java, id)
        val response = request("POST", "/auth/login", null,
            json.writeValueAsString(mapOf("mail" to mail, "password" to "local221-password")))
        // Never include issued credentials in assertion messages or logs.
        assertEquals(200, response.statusCode(), "Local fixture login failed")
        return requireNotNull(json.readTree(response.body())["access_token"]?.asText())
    }
    fun product(owner: Long, giveaway: Boolean = true): Long {
        val id = jdbc.queryForObject("""INSERT INTO product(name,price,currency,participant_id,availability)
            VALUES (?,10,'RUB',?,'PURCHASABLE') RETURNING id""", Long::class.java,
            "Giveaway221-${UUID.randomUUID()}", owner)!!
        if (giveaway) jdbc.update("""UPDATE product SET availability='GIVEAWAY',giveaway_enabled=false,
            giveaway_telegram_url='https://t.me/local221',giveaway_start_at=now()+interval '60 days',
            giveaway_end_at=now()+interval '61 days',giveaway_winners_count=1,giveaway_rules='rules',
            giveaway_home_text='home' WHERE id=?""", id)
        return id
    }
    fun ready(owner: Long) {
        jdbc.update("INSERT INTO transfer(sending,price,currency,participant_id) VALUES ('PRODUCT_PICKUP',0,'RUB',?)", owner)
        jdbc.update("INSERT INTO social_network(type,login,participant_id) VALUES ('TELEGRAM','local221',?)", owner)
    }
    fun giveawayBody(extra: String = ""): String = """{"name":"new221-${UUID.randomUUID()}",
        "availability":"GIVEAWAY","giveaway":{"enabled":false,"telegramUrl":"https://t.me/local221",
        "startAt":"${Instant.now().plusSeconds(60L * 86400)}","endAt":"${Instant.now().plusSeconds(61L * 86400)}",
        "winnersCount":1,"rules":"rules","homeText":"home"}$extra}"""
    fun snapshot(id: Long) = jdbc.queryForMap("SELECT * FROM product WHERE id=?", id)
    fun schema(moment: String) {
        val rows = jdbc.queryForList("SELECT installed_rank,version,description,checksum,success FROM flyway_schema_history ORDER BY installed_rank")
        assertEquals(30, rows.size, "Fresh schema must include V1.29 and all accepted migrations")
        assertTrue(rows.all { it["success"] == true })
        assertTrue(rows.any { it["version"] == "1.29" })
        println("22.1 $moment Flyway manifest=$rows")
    }

    fun revokedAccessMatrix() {
        for (assignment in listOf("role='USER'", "status='BLOCKED'", "status='DELETED'",
            "status='WAITING_VERIFY'", "is_agent=true")) {
            val admin = participant()
            ready(admin)
            val prize = product(admin)
            val ordinary = product(admin, false)
            val jwt = login(admin)
            val before = listOf(snapshot(prize), snapshot(ordinary))
            val count = jdbc.queryForObject("SELECT count(*) FROM product WHERE participant_id=?", Long::class.java, admin)
            jdbc.update("UPDATE participant SET $assignment WHERE id=?", admin)
            denied(request("GET", "/admin/actions/giveaways/history", jwt))
            denied(request("GET", "/admin/actions/giveaways/$prize", jwt))
            denied(request("PUT", "/admin/actions/giveaways/$prize", jwt, """{"winnersCount":2}"""))
            denied(request("POST", "/products", jwt, giveawayBody()))
            denied(request("PUT", "/product/$ordinary", jwt, giveawayBody()))
            denied(request("PUT", "/product/$prize", jwt, """{"availability":"PURCHASABLE","price":10}"""))
            denied(request("POST", "/products", jwt,
                """{"name":"external221","availability":"EXTERNAL_PRODUCT","price":10,"currency":"RUB","externalUrl":"https://example.test"}"""))
            denied(request("PUT", "/product/$ordinary", jwt, """{"name":"changed221"}"""))
            // Early gate precedes malformed JSON, path and query binding for administrative actions.
            denied(request("GET", "/admin/actions/giveaways/history?page=bad", jwt))
            denied(request("PUT", "/admin/actions/giveaways/bad", jwt, "{"))
            denied(request("POST", "/products", jwt, "{"))
            denied(request("PUT", "/product/bad", jwt, "{"))
            assertEquals(before, listOf(snapshot(prize), snapshot(ordinary)), assignment)
            assertEquals(count, jdbc.queryForObject("SELECT count(*) FROM product WHERE participant_id=?", Long::class.java, admin))
        }
    }

    fun positiveAndTokenContract() {
        val admin = participant()
        ready(admin)
        val jwt = login(admin)
        val id = ok(request("POST", "/products", jwt, giveawayBody())).toLong()
        ok(request("GET", "/admin/actions/giveaways/history", jwt))
        ok(request("GET", "/admin/actions/giveaways/$id", jwt))
        ok(request("PUT", "/admin/actions/giveaways/$id", jwt, """{"winnersCount":3}"""))
        ok(request("PUT", "/product/$id", jwt, """{"availability":"PURCHASABLE","price":10}"""))
        ok(request("PUT", "/product/$id", jwt, giveawayBody()))
        val other = participant("USER")
        val foreign = product(other)
        for (unknown in listOf(foreign, Long.MAX_VALUE)) {
            assertEquals(404, request("GET", "/admin/actions/giveaways/$unknown", jwt).statusCode())
            assertEquals(404, request("PUT", "/product/$unknown", jwt, giveawayBody()).statusCode())
        }
        val agent = participant("USER", true)
        val issued = request("POST", "/admin/actions/agents/$agent/token", jwt)
        assertEquals(200, issued.statusCode(), "Local agent token issuance failed")
        val realAgent = requireNotNull(json.readTree(issued.body())["accessToken"]?.asText())
        val syntheticAgentAdmin = keys.token(agent, "ADMIN", "agent_access")
        // The real issued USER is stopped by the existing admin authority filter before MVC.
        for ((method, path, body) in listOf(Triple("GET", "/admin/actions/giveaways/history", null),
            Triple("GET", "/admin/actions/giveaways/$id", null),
            Triple("PUT", "/admin/actions/giveaways/$id", """{"winnersCount":5}"""))) {
            assertEquals(403, request(method, path, realAgent, body).statusCode(), "Issued agent USER must fail the admin authority gate")
        }
        denied(request("POST", "/products", realAgent, giveawayBody()))
        denied(request("PUT", "/product/$id", realAgent, giveawayBody()))
        for (token in listOf(syntheticAgentAdmin, keys.token(Long.MAX_VALUE))) {
            denied(request("GET", "/admin/actions/giveaways/history", token))
            denied(request("GET", "/admin/actions/giveaways/$id", token))
            denied(request("PUT", "/admin/actions/giveaways/$id", token, """{"winnersCount":5}"""))
            denied(request("POST", "/products", token, giveawayBody()))
            denied(request("PUT", "/product/$id", token, giveawayBody()))
        }
        val nonpositiveCaller = keys.token(0)
        assertEquals(401, request("GET", "/admin/actions/giveaways/history", nonpositiveCaller).statusCode())
        assertEquals(401, request("POST", "/products", nonpositiveCaller, giveawayBody()).statusCode())
        assertEquals(401, request("PUT", "/product/$id", nonpositiveCaller, giveawayBody()).statusCode())
        assertEquals(401, request("GET", "/admin/actions/giveaways/history", keys.token(admin, type = "refresh")).statusCode())
        assertEquals(401, request("GET", "/admin/actions/giveaways/history", null).statusCode())
        assertEquals(1, jdbc.queryForObject("SELECT giveaway_winners_count FROM product WHERE id=?", Int::class.java, id))
        // USER creation/update keeps its pre-existing eligibility rather than acquiring CurrentAdminLock.
        ready(other)
        val userToken = login(other)
        jdbc.update("UPDATE participant SET status='WAITING_VERIFY' WHERE id=?", other)
        val ordinary = ok(request("POST", "/products", userToken,
            """{"name":"user221-${UUID.randomUUID()}","availability":"PURCHASABLE","price":10,"currency":"RUB","count":1}""")).toLong()
        ok(request("PUT", "/product/$ordinary", userToken, """{"name":"user221-updated"}"""))
    }
    override fun close() = http.close()
}
