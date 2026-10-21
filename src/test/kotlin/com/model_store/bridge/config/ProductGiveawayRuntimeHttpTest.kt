package com.model_store.bridge.config

import com.amazonaws.services.s3.AmazonS3
import com.fasterxml.jackson.databind.ObjectMapper
import com.model_store.service.ProductService
import com.model_store.service.impl.ProductServiceImpl
import io.jsonwebtoken.Jwts
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.springframework.aop.framework.AopProxyUtils
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.TestExecutionListeners
import org.springframework.test.context.TestContext
import org.springframework.test.context.support.AbstractTestExecutionListener
import org.springframework.test.context.support.DirtiesContextTestExecutionListener
import org.springframework.test.annotation.DirtiesContext
import java.net.URI
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.io.IOException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.concurrent.ConcurrentHashMap

/** Runs identical real HTTP writes and rollback checks for both legacy settings and full modern. */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestExecutionListeners(listeners = [RuntimeDatabaseCleanupListener::class],
    mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
@TestPropertySource(properties = ["server.address=127.0.0.1", "spring.config.import="])
@Execution(ExecutionMode.SAME_THREAD)
abstract class ProductGiveawayRuntimeContract {
    @Value("\${local.server.port}") private var port: Int = 0
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var json: ObjectMapper
    @Autowired lateinit var context: ApplicationContext
    @MockitoBean lateinit var s3: AmazonS3
    @MockitoBean lateinit var mail: JavaMailSender
    private val modern get() = context.environment.matchesProfiles("modern")

    @Test
    fun `runtime selects one product graph without changing internal legacy consumers`() {
        assertEquals(this is ProductGiveawayFullModernHttpTest, modern)
        if (this is ProductGiveawayDefaultHttpTest) assertTrue(context.environment.activeProfiles.isEmpty())
        val legacy = context.getBeansOfType(ProductService::class.java)
        if (modern) {
            assertTrue(legacy.isEmpty())
            assertFalse(context.containsBean("legacyScenarioSafetyGuard"))
            assertTrue(context.containsBean("productCommandController"))
            assertTrue(context.containsBean("publicGiveawayController"))
        } else {
            assertEquals(1, legacy.size)
            val service = legacy.values.single()
            assertEquals(ProductServiceImpl::class.java, AopProxyUtils.ultimateTargetClass(service))
            assertTrue(context.containsBean("legacyScenarioSafetyGuard"))
            for (name in listOf("productSearchController", "productDetailController", "productCommandController",
                "publicGiveawayController")) assertFalse(context.containsBean(name), name)
            for (name in listOf("orderServiceImpl", "orderCaseService", "basketServiceImpl",
                "favoriteServiceImpl", "adminAgentService")) {
                val bean = context.getBean(name)
                val target = org.springframework.test.util.AopTestUtils.getUltimateTargetObject<Any>(bean)
                val field = target.javaClass.getDeclaredField("productService").apply { isAccessible = true }
                assertSame(service, field.get(target), name)
            }
            val agent = participant("agent")
            val admin = participant("agentAdmin", "ADMIN")
            jdbc.update("UPDATE participant SET is_agent=true WHERE id=?", agent)
            val body = """{"name":"Bridge23 agent","price":15,"currency":"RUB","externalUrl":"https://example.test/agent"}"""
            error(request("POST", "/agent/products", body, token(agent)), 403, "ACCESS_DENIED")
            val id = ok(request("POST", "/agent/products", body, token(agent, type = "agent_access"))).toLong()
            val adminToken = token(admin, "ADMIN")
            val items = json.readTree(ok(request("GET", "/admin/actions/agents/$agent/products", bearer = adminToken)))
            assertTrue(items.any { it["id"].asLong() == id })
            assertEquals(id, json.readTree(ok(request("GET", "/admin/actions/products/$id", bearer = adminToken)))["id"].asLong())
            ok(request("PUT", "/admin/actions/agents/$agent/products/$id", """{"name":"Bridge23 agent edited"}""", adminToken))
            ok(request("POST", "/admin/actions/agents/$agent/products/$id/extend", bearer = adminToken))
            assertEquals("Bridge23 agent edited", jdbc.queryForObject("SELECT name FROM product WHERE id=?", String::class.java, id))
        }
    }

    @Test
    fun `guest queries and giveaway headers security and admin writes retain runtime contracts`() {
        val admin = participant("admin", "ADMIN")
        val user = participant("user")
        val adminToken = token(admin, "ADMIN")
        val start = Instant.now().plusSeconds(30 * 86400).toString()
        val end = Instant.now().plusSeconds(31 * 86400).toString()
        val body = """{"name":"Bridge23 prize","availability":"GIVEAWAY","giveaway":{
            "telegramUrl":"https://t.me/bridge23","startAt":"$start","endAt":"$end",
            "winnersCount":2,"rules":"Rules","homeText":"Home"}}"""
        assertEquals(401, request("POST", "/products", body).statusCode())
        error(request("POST", "/products", body, token(user)), 403, "ACCESS_DENIED")
        val id = ok(request("POST", "/products", body, adminToken)).toLong()
        val public = request("GET", "/giveaways/products/$id")
        assertEquals(200, public.statusCode(), public.body())
        assertEquals("noindex, nofollow", public.headers().firstValue("X-Robots-Tag").orElse(null))
        assertTrue(public.headers().firstValue("Content-Type").orElse("").contains("application/json"))
        val node = json.readTree(public.body())
        assertEquals(setOf("productId", "name", "description", "imageIds", "telegramUrl", "startAt",
            "endAt", "winnersCount", "rules", "homeText", "status"), node.fieldNames().asSequence().toSet())
        assertEquals(id, node["productId"].asLong())
        assertEquals("AWAITING_GIVEAWAY", node["status"].asText())
        assertEquals(200, request("GET", "/giveaways/active").statusCode())
        val missing = request("GET", "/giveaways/products/${Long.MAX_VALUE}")
        assertEquals(404, missing.statusCode())
        assertFalse(missing.headers().firstValue("X-Robots-Tag").isPresent)

        val history = "/admin/actions/giveaways/history"
        assertEquals(200, request("GET", history, bearer = adminToken).statusCode())
        assertEquals(403, request("GET", history, bearer = token(user)).statusCode())
        for (type in listOf("refresh", "verify", "agent_access")) {
            val expected = when (type) {
                "refresh" -> 401
                "verify" -> if (modern) 401 else 200
                else -> if (modern) 403 else 200
            }
            assertEquals(expected, request("GET", history,
                bearer = token(admin, "ADMIN", type)).statusCode(), type)
        }
        val forged = java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().private
        assertEquals(401, request("GET", history, bearer = token(admin, "ADMIN", signer = forged)).statusCode())
        val invalid = request("PUT", "/admin/actions/giveaways/$id", """{"winnersCount":0}""", adminToken)
        error(invalid, 400, "INVALID_REQUEST")
        assertEquals(2, jdbc.queryForObject("SELECT giveaway_winners_count FROM product WHERE id=?", Int::class.java, id))
        ok(request("PUT", "/admin/actions/giveaways/$id", """{"winnersCount":3}""", adminToken))
        val details = json.readTree(ok(request("GET", "/admin/actions/giveaways/$id", bearer = adminToken)))
        assertEquals(3, details["product"]["giveawayWinnersCount"].asInt())
        assertEquals(setOf("product", "imageIds", "categories"), details.fieldNames().asSequence().toSet())

        val seller = participant("search")
        val item = product(seller, "Bridge23 query")
        val search = """{"name":"Bridge23 query","pageable":{"size":10,"sortBy":"DATE_DESC","lastId":0}}"""
        for (bearer in listOf(null, token(user), token(admin, "ADMIN"), token(user, type = "agent_access"),
            token(user, type = "refresh"), token(user, type = "verify"), token(user, signer = forged))) {
            val result = json.readTree(ok(request("POST", "/products/find", search, bearer)))
            assertTrue(result.any { it["id"].asLong() == item })
        }
        val names = json.readTree(ok(request("POST", "/products/names/find?name=Bridge23")))
        assertTrue(names.any { it.asText() == "Bridge23 query" })
        assertEquals(item, json.readTree(ok(request("GET", "/product/$item")))["id"].asLong())
    }

    @Test
    fun `ordinary product category failure rolls back product and image before valid writes`() {
        val owner = participant("owner")
        ready(owner)
        val image = jdbc.queryForObject("""INSERT INTO image(tag,status,uploaded_by)
            VALUES ('PRODUCT','TEMPORARY',?) RETURNING id""", Long::class.java, owner)!!
        val body = """{"name":"Bridge23 rollback","price":10,"currency":"RUB","availability":"PURCHASABLE",
            "imageIds":[$image],"categoryIds":[9223372036854775807]}"""
        val rejected = request("POST", "/products", body, token(owner))
        error(rejected, 400, "INVALID_REFERENCE")
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM product WHERE name='Bridge23 rollback'", Int::class.java))
        assertEquals("TEMPORARY", jdbc.queryForObject("SELECT status::text FROM image WHERE id=?", String::class.java, image))
        assertNull(jdbc.queryForObject("SELECT entity_id FROM image WHERE id=?", Long::class.java, image))
        val created = """{"name":"Bridge23 ordinary","price":10,"currency":"RUB","availability":"PURCHASABLE","count":3}"""
        val id = ok(request("POST", "/products", created, token(owner))).toLong()
        ok(request("PUT", "/product/$id", """{"name":"Bridge23 edited"}""", token(owner)))
        ok(request("POST", "/products/extend/$id", bearer = token(owner)))
        val mine = json.readTree(ok(request("POST", "/products/my", """{"pageable":{"size":10,"sortBy":"DATE_DESC"}}""", token(owner))))
        assertTrue(mine.any { it["id"].asLong() == id })
        val admin = participant("status", "ADMIN")
        ok(request("PUT", "/admin/actions/product/$id?productStatus=BLOCKED", bearer = token(admin, "ADMIN")))
        assertEquals("BLOCKED", jdbc.queryForObject("SELECT status::text FROM product WHERE id=?", String::class.java, id))
    }

    @Test
    fun `later booking failure rolls back stock basket and earlier order then booking succeeds`() {
        val buyer = participant("buyer")
        val seller = participant("seller")
        val transfer = ready(seller)
        val first = product(seller, "Bridge23 first")
        val second = product(seller, "Bridge23 second")
        val address = jdbc.queryForObject("INSERT INTO address(country,city) VALUES ('RU','Moscow') RETURNING id", Long::class.java)!!
        jdbc.update("INSERT INTO participant_address(participant_id,address_id) VALUES (?,?)", buyer, address)
        ok(request("POST", "/basket?productId=$first&count=1", bearer = token(buyer)))
        ok(request("POST", "/favorites?productId=$first", bearer = token(buyer)))
        val search = """{"pageable":{"size":10,"sortBy":"DATE_DESC"}}"""
        assertTrue(json.readTree(ok(request("POST", "/basket/find", search, token(buyer)))).any { it["product"]["id"].asLong() == first })
        assertTrue(json.readTree(ok(request("POST", "/favorites/find", search, token(buyer)))).any { it["id"].asLong() == first })
        fun line(id: Long, count: Int) = """{"productId":$id,"count":$count,"addressId":$address,"transferId":$transfer}"""
        val failed = request("POST", "/order/BOOKED", "[${line(first, 1)},${line(second, 99)}]", token(buyer))
        assertEquals(400, failed.statusCode(), failed.body())
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM \"order\" WHERE customer_id=?", Int::class.java, buyer))
        assertEquals(2, jdbc.queryForObject("SELECT count FROM product WHERE id=?", Int::class.java, first))
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM product_basket WHERE participant_id=? AND product_id=?", Int::class.java, buyer, first))
        val booked = json.readTree(ok(request("POST", "/order/BOOKED", "[${line(first, 1)}]", token(buyer))))
        assertEquals(1, booked.size())
        assertEquals(1, jdbc.queryForObject("SELECT count FROM product WHERE id=?", Int::class.java, first))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM product_basket WHERE participant_id=? AND product_id=?", Int::class.java, buyer, first))
    }

    private fun participant(suffix: String, role: String = "USER"): Long {
        val name = "b23${javaClass.simpleName.take(16)}$suffix"
        return jdbc.queryForObject("""INSERT INTO participant(login,mail,password,status,role,deadline_sending,deadline_payment)
            VALUES (?,?,'hash','ACTIVE',?::participant_role,3,7) RETURNING id""", Long::class.java, name, "$name@example.test", role)!!
    }

    private fun ready(owner: Long): Long {
        jdbc.update("INSERT INTO social_network(type,login,participant_id) VALUES ('TELEGRAM','bridge23',?)", owner)
        return jdbc.queryForObject("""INSERT INTO transfer(sending,price,currency,participant_id)
            VALUES ('PRODUCT_PICKUP',0,'RUB',?) RETURNING id""", Long::class.java, owner)!!
    }

    private fun product(owner: Long, name: String) = jdbc.queryForObject("""INSERT INTO product(name,price,currency,
        participant_id,status,availability,count,used) VALUES (?,10,'RUB',?,'ACTIVE','PURCHASABLE',2,false) RETURNING id""",
        Long::class.java, name, owner)!!

    private fun ok(response: HttpResponse<String>): String {
        assertEquals(200, response.statusCode(), response.body())
        return response.body()
    }

    private fun error(response: HttpResponse<String>, status: Int, code: String) {
        assertEquals(status, response.statusCode(), response.body())
        val node = json.readTree(response.body())
        assertEquals(code, node["code"].asText())
        assertEquals(status, node["status"].asInt())
        assertEquals(setOf("code", "message", "status", "timestamp", "details"), node.fieldNames().asSequence().toSet())
    }

    private fun request(method: String, path: String, body: String? = null, bearer: String? = null): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).timeout(Duration.ofSeconds(10))
        if (bearer != null) builder.header("Authorization", "Bearer $bearer")
        if (body != null) builder.header("Content-Type", "application/json")
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).proxy(loopbackOnlyProxy).build().use { client ->
            client.send(builder.method(method, body?.let(HttpRequest.BodyPublishers::ofString)
                ?: HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString())
        }
    }

    private fun token(id: Long, role: String = "USER", type: String = "access", signer: PrivateKey = privateKey): String =
        Jwts.builder().claim("id", id).claim("role", role).claim("login", "bridge23").claim("type", type)
            .claim("issuedBy", "admin").expiration(Date.from(Instant.now().plusSeconds(3600))).signWith(signer).compact()

    companion object {
        private val databases = ConcurrentHashMap<Class<*>, EmbeddedPostgres>()

        fun registerDatabase(testClass: Class<*>, postgres: EmbeddedPostgres): EmbeddedPostgres = postgres.also {
            check(databases.putIfAbsent(testClass, it) == null)
        }

        fun closeDatabase(testClass: Class<*>) { databases.remove(testClass)?.close() }

        private val loopbackOnlyProxy = object : ProxySelector() {
            override fun select(uri: URI): List<Proxy> = listOf(Proxy.NO_PROXY)
            override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) = Unit
        }
        private val privateKey: PrivateKey by lazy {
            val pem = ClassPathResource("keys/test_private_key.pem").inputStream.bufferedReader().use { it.readText() }
                .replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "").replace(Regex("\\s+"), "")
            KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)))
        }

        fun database(registry: DynamicPropertyRegistry, postgres: EmbeddedPostgres) {
            for (prefix in listOf("spring.datasource", "spring.flyway")) {
                registry.add("$prefix.url") { "jdbc:postgresql://127.0.0.1:${postgres.port}/postgres" }
            }
            registry.add("spring.datasource.username") { "postgres" }
            registry.add("spring.datasource.password") { "" }
            registry.add("spring.flyway.user") { "postgres" }
            registry.add("spring.flyway.password") { "" }
            registry.add("spring.r2dbc.url") { "r2dbc:postgresql://127.0.0.1:${postgres.port}/postgres" }
            registry.add("spring.r2dbc.username") { "postgres" }
            registry.add("spring.r2dbc.password") { "" }
        }

    }
}

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["app.public-key-path=keys/test_public_key.pem", "app.private-key-path=keys/test_private_key.pem",
        "spring.profiles.active="])
class ProductGiveawayDefaultHttpTest : ProductGiveawayRuntimeContract() {
    companion object {
        private val postgres = registerDatabase(ProductGiveawayDefaultHttpTest::class.java, EmbeddedPostgres.start())
        @JvmStatic @DynamicPropertySource fun db(registry: DynamicPropertyRegistry) = database(registry, postgres)
    }
}

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["app.public-key-path=keys/test_public_key.pem", "app.private-key-path=keys/test_private_key.pem",
        "app.migration.scenarios.product=legacy", "app.migration.scenarios.giveaway=legacy"])
@ActiveProfiles("legacy")
class ProductGiveawayExplicitLegacyHttpTest : ProductGiveawayRuntimeContract() {
    companion object {
        private val postgres = registerDatabase(ProductGiveawayExplicitLegacyHttpTest::class.java, EmbeddedPostgres.start())
        @JvmStatic @DynamicPropertySource fun db(registry: DynamicPropertyRegistry) = database(registry, postgres)
    }
}

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["app.public-key-path=keys/test_public_key.pem", "app.private-key-path=keys/test_private_key.pem",
        "app.migration.scenarios.product=modern", "app.migration.scenarios.giveaway=modern"])
@ActiveProfiles("modern")
class ProductGiveawayFullModernHttpTest : ProductGiveawayRuntimeContract() {
    companion object {
        private val postgres = registerDatabase(ProductGiveawayFullModernHttpTest::class.java, EmbeddedPostgres.start())
        @JvmStatic @DynamicPropertySource fun db(registry: DynamicPropertyRegistry) = database(registry, postgres)
    }
}

/** afterTestClass callbacks run in reverse order: close the dirty Spring context before its PG. */
class RuntimeDatabaseCleanupListener : AbstractTestExecutionListener() {
    override fun getOrder(): Int = DirtiesContextTestExecutionListener().order - 1
    override fun afterTestClass(testContext: TestContext) =
        ProductGiveawayRuntimeContract.closeDatabase(testContext.testClass)
}
