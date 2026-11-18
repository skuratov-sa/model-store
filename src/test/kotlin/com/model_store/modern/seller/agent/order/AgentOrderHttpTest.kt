package com.model_store.modern.seller.agent.order

import com.amazonaws.services.s3.AmazonS3
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.model_store.modern.shared.JavaReleaseOrderContract
import io.jsonwebtoken.Jwts
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.io.ClassPathResource
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Date
import java.util.concurrent.atomic.AtomicLong

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["app.public-key-path=keys/test_public_key.pem", "app.private-key-path=keys/test_private_key.pem"])
abstract class AgentOrderFixture {
    @Value("\${local.server.port}") var port: Int = 0
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var json: ObjectMapper
    @Autowired lateinit var environment: Environment
    protected val javaRelease get() = "modern" !in environment.activeProfiles
    @MockitoBean lateinit var s3: AmazonS3
    @MockitoBean lateinit var mail: JavaMailSender
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()

    @AfterEach
    fun closeHttpClient() {
        client.shutdownNow()
        assertTrue(client.awaitTermination(Duration.ofSeconds(3)), "HTTP client did not stop")
    }

    data class Fixture(val buyer: Long, val seller: Long, val product: Long, val address: Long, val transfer: Long, val order: Long)

    fun fixture(availability: String = "PURCHASABLE", prepayment: Float = 0f): Fixture {
        val buyer = participant(); val seller = participant()
        val address = jdbc.queryForObject("INSERT INTO address(country,city) VALUES ('RU','Moscow') RETURNING id", Long::class.java)!!
        val transfer = jdbc.queryForObject("INSERT INTO transfer(sending,price,currency,participant_id) VALUES ('PRODUCT_PICKUP',0,'RUB',?) RETURNING id", Long::class.java, seller)!!
        val product = jdbc.queryForObject("""INSERT INTO product(name,price,currency,participant_id,status,availability,count,prepayment_amount,used)
            VALUES (?,10,'RUB',?,'ACTIVE',CAST(? AS product_availability),3,?,false) RETURNING id""", Long::class.java, "lc${sequence.incrementAndGet()}", seller, availability, prepayment)!!
        val order = jdbc.queryForObject("""INSERT INTO "order"(seller_id,customer_id,count,status,product_id,address_id,transfer_id,total_price,
            prepayment_amount,product_name,product_unit_price,product_currency,product_availability)
            VALUES (?,?,1,'BOOKED',?,?,?,10,?,'snapshot',10,'RUB',CAST(? AS product_availability)) RETURNING id""",
            Long::class.java, seller, buyer, product, address, transfer, prepayment, availability)!!
        return Fixture(buyer,seller,product,address,transfer,order)
    }
    fun case(f: Fixture, kind: String, previous: String) = jdbc.queryForObject("""INSERT INTO order_case(order_id,kind,state,opened_by,previous_order_status)
        VALUES (?,?,'OPEN',?,CAST(? AS order_status)) RETURNING id""", Long::class.java, f.order,kind,f.buyer,previous)!!
    fun participant(role: String = "USER"): Long {
        val name = "lc${sequence.incrementAndGet()}"
        return jdbc.queryForObject("INSERT INTO participant(login,mail,password,status,role) VALUES (?,?,'hash','ACTIVE',CAST(? AS participant_role)) RETURNING id", Long::class.java,name,"$name@example.test",role)!!
    }
    fun image(uploader: Long, tag: String = "ORDER", state: String = "TEMPORARY", entity: Long? = null): Long = jdbc.queryForObject(
        """INSERT INTO image(filename,tag,status,entity_id,uploaded_by) VALUES (?,CAST(? AS image_tag),CAST(? AS image_status),?,?) RETURNING id""",
        Long::class.java, "lc${sequence.incrementAndGet()}.jpg",tag,state,entity,uploader)!!
    fun stock(id: Long): Int? = jdbc.queryForObject("SELECT count FROM product WHERE id=?", Int::class.java,id)
    fun status(id: Long): String = jdbc.queryForObject("SELECT status::text FROM \"order\" WHERE id=?", String::class.java,id)!!
    fun count(sql: String) = jdbc.queryForObject(sql, Int::class.java)!!
    fun list(id: Long, seller: Boolean): List<JsonNode> {
        val response = request("GET", "/order/${if (seller) "seller" else "customer"}", id)
        ok(response); return json.readTree(response.body()).toList()
    }
    fun request(method: String, path: String, actor: Long, body: String? = null, role: String = "USER", type: String = "access"): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).timeout(Duration.ofSeconds(10))
            .header("Authorization", "Bearer ${token(actor,role,type)}")
        if (body != null) builder.header("Content-Type", "application/json")
        return client.send(builder.method(method, body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString())
    }
    fun ok(response: HttpResponse<String>) { assertEquals(200,response.statusCode(),response.body()) }
    fun error(response: HttpResponse<String>, status: Int, code: String, message: String) {
        assertEquals(status,response.statusCode(),response.body())
        val body=json.readTree(response.body())
        assertEquals(setOf("code","message","status","timestamp","details"),body.fieldNames().asSequence().toSet(),response.body())
        assertEquals(code,body["code"].asText()); assertEquals(message,body["message"].asText()); assertEquals(status,body["status"].asInt())
        assertTrue(body["details"].isNull); assertDoesNotThrow { java.time.OffsetDateTime.parse(body["timestamp"].asText()) }
    }
    fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8)

    fun agentFixture(prepayment: Float = 0f): Fixture = fixture("PURCHASABLE", prepayment).also {
        jdbc.update("UPDATE participant SET is_agent=true WHERE id=?", it.seller)
    }
    fun admin() = participant("ADMIN")
    fun action(f: Fixture, admin: Long, action: String, query: String = "") = request("POST",
        "/admin/actions/agents/${f.seller}/orders/${f.order}/$action$query", admin, role = "ADMIN")
    fun queue(admin: Long, agent: Long? = null, query: String = "") = request("GET",
        "/admin/actions/${agent?.let { "agents/$it/orders" } ?: "agent-orders"}$query", admin, role = "ADMIN")
    fun audits(id: Long) = count("SELECT count(*) FROM admin_agent_order_action WHERE order_id=$id")
    fun histories(id: Long) = count("SELECT count(*) FROM order_status_history WHERE order_id=$id")

    companion object {
        // Private instance for this contract family; never a shared application/production database.
        val postgres: EmbeddedPostgres = EmbeddedPostgres.start()
        val sequence = AtomicLong()
        val privateKey: PrivateKey by lazy {
            val pem = ClassPathResource("keys/test_private_key.pem").inputStream.bufferedReader().use { it.readText() }
                .replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "").replace(Regex("\\s+"), "")
            KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)))
        }
        fun token(id: Long, role: String = "USER", type: String = "access") = Jwts.builder().claim("id",id).claim("login","lifecycle")
            .claim("role",role).claim("type",type).apply { if(type=="agent_access") claim("issuedBy","admin") }
            .expiration(Date.from(Instant.now().plusSeconds(3600))).signWith(privateKey).compact()
        @JvmStatic @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) {
            val url="jdbc:postgresql://localhost:${postgres.port}/postgres"
            registry.add("spring.datasource.url") { url }; registry.add("spring.datasource.username") { "postgres" }; registry.add("spring.datasource.password") { "" }
            registry.add("spring.flyway.url") { url }; registry.add("spring.flyway.user") { "postgres" }; registry.add("spring.flyway.password") { "" }
            registry.add("spring.r2dbc.url") { "r2dbc:postgresql://localhost:${postgres.port}/postgres" }; registry.add("spring.r2dbc.username") { "postgres" }; registry.add("spring.r2dbc.password") { "" }
        }
    }
}

abstract class AgentOrderHttpContract : AgentOrderFixture() {
    @Test
    fun `human binding precedence and unvalidated admin shipment query preserve legacy contract`() {
        val a=admin(); val f=agentFixture()
        for(path in listOf("/admin/actions/agent-orders?status=unknown","/admin/actions/agent-orders?page=1.5",
            "/admin/actions/agent-orders?size=2147483648","/admin/actions/agents/x/orders")) {
            error(request("GET",path,a,role="ADMIN"),400,"BAD_REQUEST","Type mismatch.")
        }
        error(queue(a,Long.MAX_VALUE,"?page=-1"),400,"INVALID_REQUEST","Некорректная страница или размер страницы")
        error(queue(a,Long.MAX_VALUE),404,"PARTICIPANT_NOT_FOUND","Бот не найден")
        jdbc.update("UPDATE \"order\" SET status='ASSEMBLING' WHERE id=?",f.order)
        val before=histories(f.order)
        error(action(f,a,"SHIP","?deliveryUrl=${"x".repeat(1001)}"),500,"INTERNAL_ERROR","Внутренняя ошибка")
        assertEquals("ASSEMBLING",status(f.order)); assertEquals(before,histories(f.order)); assertEquals(0,audits(f.order))
        val url="javascript:any legacy string ?x=1&y=2"
        ok(action(f,a,"SHIP","?deliveryUrl=${encode(url)}&comment=${encode("  raw comment  ")}"))
        assertEquals(url,jdbc.queryForObject("SELECT delivery_url FROM \"order\" WHERE id=?",String::class.java,f.order))
        assertEquals("  raw comment  ",jdbc.queryForObject("SELECT comment FROM \"order\" WHERE id=?",String::class.java,f.order))
        assertEquals(1,audits(f.order)); assertEquals(before+1,histories(f.order))
    }

    @Test
    fun `four delegated actions retain seller and record actual admin exactly once`() {
        val a = admin()
        for (prepayment in listOf(0f, 5f)) {
            val f = agentFixture(prepayment)
            val before = histories(f.order)
            ok(action(f, a, "CONFIRM", "?comment=accepted"))
            assertEquals(if (prepayment > 0) "AWAITING_PREPAYMENT" else "AWAITING_PAYMENT", status(f.order))
            assertEquals(a, jdbc.queryForObject("SELECT admin_id FROM admin_agent_order_action WHERE order_id=?", Long::class.java, f.order))
            assertEquals(f.seller, jdbc.queryForObject("SELECT seller_id FROM \"order\" WHERE id=?", Long::class.java, f.order))
            error(action(f,a,"CONFIRM"),400,"BAD_REQUEST","Нельзя выполнить операцию с данными условиями")
            assertEquals(1,audits(f.order)); assertEquals(before+1,histories(f.order))
            if(prepayment>0) {
                jdbc.update("UPDATE \"order\" SET status='AWAITING_PREPAYMENT_APPROVAL' WHERE id=?", f.order)
                ok(action(f,a,"CONFIRM_PREPAYMENT"))
                assertEquals("AWAITING_PAYMENT",status(f.order))
            }
            jdbc.update("UPDATE \"order\" SET status='ASSEMBLING' WHERE id=?",f.order)
            ok(action(f,a,"SHIP")) // Admin route intentionally accepts absent deliveryUrl.
            assertEquals("ON_THE_WAY",status(f.order))
            assertNull(jdbc.queryForObject("SELECT delivery_url FROM \"order\" WHERE id=?",String::class.java,f.order))
        }
        val f=agentFixture(); val before=histories(f.order)
        ok(action(f,a,"CANCEL","?comment=cancelled"))
        assertEquals("CANCELLED",status(f.order)); assertEquals(4,stock(f.product))
        error(action(f,a,"CANCEL"),400,"BAD_REQUEST","Нельзя выполнить операцию с данными условиями")
        assertEquals(4,stock(f.product)); assertEquals(1,audits(f.order)); assertEquals(before+1,histories(f.order))
    }

    @Test
    fun `queue includes full legacy DTO sellerLogin history status target and pagination`() {
        val a=admin(); val f=agentFixture(); val other=agentFixture()
        jdbc.update("UPDATE \"order\" SET seller_id=? WHERE id=?",f.seller,other.order)
        jdbc.update("UPDATE \"order\" SET created_at='2090-01-01' WHERE id IN (?,?)",f.order,other.order)
        val response=queue(a,f.seller,query="?size=1&page=1"); ok(response)
        val view=json.readTree(response.body()).single()
        assertEquals(f.order,view["orderId"].asLong())
        JavaReleaseOrderContract.assertResponse(view, javaRelease)
        assertEquals(f.buyer,view["userInfo"]["id"].asLong())
        assertEquals("snapshot",view["product"]["name"].asText())
        assertEquals("BOOKED",view["histories"].last()["status"].asText())
        assertEquals(jdbc.queryForObject("SELECT login FROM participant WHERE id=?",String::class.java,f.seller),view["sellerLogin"].asText())
        for(state in listOf("ACTIVE","BLOCKED","DELETED")) {
            jdbc.update("UPDATE participant SET status=CAST(? AS participant_status) WHERE id=?",state,f.seller)
            ok(queue(a,f.seller)); ok(action(f,a,"CONFIRM"))
            jdbc.update("UPDATE \"order\" SET status='BOOKED' WHERE id=?",f.order)
        }
        assertEquals("[]",queue(a,f.seller,"?status=COMPLETED").body())
        assertEquals("[]",queue(a,f.seller,"?page=2147483647&size=100").body())
        error(queue(a,f.seller,"?size=101"),400,"INVALID_REQUEST","Некорректная страница или размер страницы")
        jdbc.update("UPDATE \"order\" SET status='COMPLETED' WHERE id=?", f.order)
        val reviewId = jdbc.queryForObject("""INSERT INTO review(order_id,product_id,reviewer_id,seller_id,rating)
            VALUES (?,?,?,?,5) RETURNING id""", Long::class.java, f.order, f.product, f.buyer, f.seller)!!
        val reviewedResponse = queue(a, f.seller, "?status=COMPLETED"); ok(reviewedResponse)
        val reviewed = json.readTree(reviewedResponse.body()).single()
        assertEquals(f.order, reviewed["orderId"].asLong())
        JavaReleaseOrderContract.assertResponse(reviewed, javaRelease, reviewId = reviewId)
    }

    @Test
    fun `target order action precedence foreign orders and audit rollback on long SQL comment`() {
        val a=admin(); val f=agentFixture(); val ordinary=fixture()
        error(request("POST","/admin/actions/agents/${ordinary.seller}/orders/${f.order}/WRONG",a,role="ADMIN"),
            404,"PARTICIPANT_NOT_FOUND","Бот не найден")
        error(request("POST","/admin/actions/agents/${f.seller}/orders/${ordinary.order}/WRONG",a,role="ADMIN"),
            404,"INVALID_REQUEST","Заказ бота не найден")
        error(action(f,a,"wrong"),400,"INVALID_REQUEST","Неизвестное действие с заказом")
        val before=histories(f.order)
        error(action(f,a,"CANCEL","?comment=${"x".repeat(501)}"),500,"INTERNAL_ERROR","Внутренняя ошибка")
        assertEquals("BOOKED",status(f.order)); assertEquals(before,histories(f.order))
        assertEquals(0,audits(f.order)); assertEquals(3,stock(f.product))
    }

    @Test
    fun `issued agent and refresh credentials cannot perform admin actions or ordinary bot actions`() {
        val a=admin(); val f=agentFixture()
        val issued=request("POST","/admin/actions/agents/${f.seller}/token",a,role="ADMIN"); ok(issued)
        val tokens=json.readTree(issued.body())
        for(field in listOf("accessToken","refreshToken")) {
            val raw=tokens[field].asText()
            HttpClient.newHttpClient().use { client ->
                val r=client.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/admin/actions/agents/${f.seller}/orders/${f.order}/CONFIRM"))
                    .header("Authorization","Bearer $raw").POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString())
                assertEquals(if(field=="accessToken") 403 else 401,r.statusCode(),r.body())
            }
        }
        assertEquals(403,request("POST","/admin/actions/agents/${f.seller}/orders/${f.order}/CONFIRM",f.seller,role="ADMIN").statusCode())
        assertEquals(403,request("POST","/order/${f.order}/AWAITING_PREPAYMENT",f.seller).statusCode())
        assertEquals(403,request("GET","/admin/actions/agent-orders",f.buyer).statusCode())
        assertEquals(0,audits(f.order)); assertEquals("BOOKED",status(f.order))
    }
}

@ActiveProfiles("legacy")
class AgentOrderLegacyHttpTest : AgentOrderHttpContract()

@ActiveProfiles("modern")
class AgentOrderModernHttpTest : AgentOrderHttpContract() {
    @Test
    fun `real refreshed agent token and malformed claim types remain denied on admin routes`() {
        val a=admin(); val f=agentFixture()
        val issued=request("POST","/admin/actions/agents/${f.seller}/token",a,role="ADMIN"); ok(issued)
        val tokens=json.readTree(issued.body())
        HttpClient.newHttpClient().use { client ->
            val refreshed=client.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/auth/refresh"))
                .header("X-Refresh-Token",tokens["refreshToken"].asText()).POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString())
            assertEquals(200,refreshed.statusCode(),refreshed.body())
            val raw=refreshed.body().removeSurrounding("\"")
            val parser=Jwts.parser().verifyWith(KeyFactory.getInstance("RSA").generatePublic(java.security.spec.X509EncodedKeySpec(
                Base64.getDecoder().decode(ClassPathResource("keys/test_public_key.pem").inputStream.bufferedReader().use { it.readText() }
                    .replace("-----BEGIN PUBLIC KEY-----","").replace("-----END PUBLIC KEY-----","").replace(Regex("\\s+"),"")))) as java.security.PublicKey).build()
            assertEquals("agent_access",parser.parseSignedClaims(raw).payload["type"])
            val route="http://127.0.0.1:$port/admin/actions/agents/${f.seller}/orders/${f.order}/CONFIRM"
            val invalid=listOf(raw,token(f.seller,"ADMIN","agent_access"),token(a,"ADMIN","refresh"),"invalid.jwt")+
                listOf<Any>(listOf("ADMIN"),42,true).map { role -> Jwts.builder().claim("id",a).claim("login","x")
                    .claim("role",role).claim("type","access").expiration(Date.from(Instant.now().plusSeconds(3600)))
                    .signWith(privateKey).compact() }+
                listOf(Jwts.builder().claim("id",a).claim("role","ADMIN").claim("type",listOf("access"))
                    .expiration(Date.from(Instant.now().plusSeconds(3600))).signWith(privateKey).compact(),
                    Jwts.builder().claim("id",a).claim("role","ADMIN").claim("type","access")
                        .expiration(Date.from(Instant.now().minusSeconds(3600))).signWith(privateKey).compact())+
                listOf<Any>("1",1.5,listOf(a)).map { badId -> Jwts.builder().claim("id",badId)
                    .claim("role","ADMIN").claim("type","access").expiration(Date.from(Instant.now().plusSeconds(3600)))
                    .signWith(privateKey).compact() }+
                listOf(Jwts.builder().claim("id",a).claim("role","ADMIN").claim("type","access")
                    .expiration(Date.from(Instant.now().plusSeconds(3600))).signWith(java.security.KeyPairGenerator.getInstance("RSA")
                        .apply { initialize(2048) }.generateKeyPair().private).compact())
            for((index,jwt) in invalid.withIndex()) {
                val response=client.send(HttpRequest.newBuilder(URI(route)).header("Authorization","Bearer $jwt")
                    .POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString())
                assertEquals(if(index<2) 403 else 401,response.statusCode(),response.body())
            }
        }
        assertEquals(0,audits(f.order)); assertEquals("BOOKED",status(f.order))
    }

    @Test
    fun `stale admin claim denied for reads writes and absent target`() {
        for(change in listOf("role='USER'","status='BLOCKED'","is_agent=true")) {
            val a=admin(); val f=agentFixture()
            jdbc.update("UPDATE participant SET $change WHERE id=?",a)
            for(response in listOf(queue(a),action(f,a,"CONFIRM"))) {
                if(change=="is_agent=true") { assertEquals(403,response.statusCode()); assertEquals("",response.body()) }
                else error(response,403,"ACCESS_DENIED","Доступ запрещён")
            }
            assertEquals(403,request("POST","/admin/actions/agents/9223372036854775807/orders/1/CONFIRM",a,role="ADMIN").statusCode())
            assertEquals("BOOKED",status(f.order)); assertEquals(0,audits(f.order))
        }
    }
}
