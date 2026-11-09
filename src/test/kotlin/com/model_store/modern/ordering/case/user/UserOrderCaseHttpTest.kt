package com.model_store.modern.ordering.`case`.user

import com.amazonaws.services.s3.AmazonS3
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.jsonwebtoken.Jwts
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.io.ClassPathResource
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
abstract class UserCaseHttpContract {
    @Value("\${local.server.port}") var port: Int = 0
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var json: ObjectMapper
    @MockitoBean lateinit var s3: AmazonS3
    @MockitoBean lateinit var mail: JavaMailSender
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()

    @AfterEach
    fun closeHttpClient() {
        client.shutdownNow()
        assertTrue(client.awaitTermination(Duration.ofSeconds(3)), "HTTP client did not stop")
    }

    data class Fixture(val buyer: Long, val seller: Long, val product: Long, val address: Long, val transfer: Long, val order: Long)

    @Test
    fun `ACCESS bot actor gets legacy empty forbidden for every case route but human counterparty works`() {
        for(botBuyer in listOf(false,true)) {
            val f=paid()
            val bot=if(botBuyer) f.buyer else f.seller
            val human=if(botBuyer) f.seller else f.buyer
            jdbc.update("UPDATE participant SET is_agent=true WHERE id=?",bot)
            val routes=listOf("GET" to "/order/${f.order}/cases",
                "POST" to "/order/orders/${f.order}/DISPUTED?comment=x",
                "POST" to "/order/${f.order}/cancellation-request?comment=x",
                "POST" to "/order/${f.order}/payment-appeal?comment=x")
            for((method,path) in routes) {
                val response=request(method,path,bot)
                assertEquals(403,response.statusCode(),response.body()); assertEquals("",response.body())
                if(method=="POST") {
                    val missing=request(method,path.substringBefore('?'),bot)
                    assertEquals(403,missing.statusCode(),missing.body()); assertEquals("",missing.body())
                }
            }
            ok(request("GET","/order/${f.order}/cases",human))
            ok(request("POST","/order/orders/${f.order}/DISPUTED?comment=x",human))
            assertEquals(1,count("SELECT count(*) FROM order_case WHERE order_id=${f.order}"))
        }
    }

    @Test
    fun `dispute exact route both parties evidence history and full JSON contract`() {
        for (sellerOpens in listOf(false,true)) {
            val f=fixture("PREORDER",5f)
            val who=if(sellerOpens) f.seller else f.buyer
            val proof=image(f.buyer,state="ACTIVE",entity=f.order)
            jdbc.update("UPDATE \"order\" SET status='AWAITING_PAYMENT', image_payment_proof_id=? WHERE id=?",proof,f.order)
            val first=image(who); val second=image(who,entity=f.order)
            val before=histories(f.order)
            val response=request("POST","/order/orders/${f.order}/DISPUTED?comment=%20reason%20&imageIds=$second,$first",who)
            ok(response); val id=response.body().toLong()
            assertEquals("DISPUTED",status(f.order)); assertEquals(before+1,histories(f.order))
            assertEquals(" reason ",jdbc.queryForObject("SELECT comment FROM \"order\" WHERE id=?",String::class.java,f.order))
            val details=request("GET","/order/${f.order}/cases",who); ok(details)
            val detail=json.readTree(details.body()).single()
            assertEquals(setOf("orderCase","orderStatus","buyerId","sellerId","productId","count","totalPrice","prepaymentAmount","currency","paymentProofImageId","orderImages","caseImages"),detail.fieldNames().asSequence().toSet())
            val c=detail["orderCase"]
            assertEquals(setOf("id","version","orderId","kind","state","openedBy","openingComment","previousOrderStatus","telegramUrl","resolvedBy","outcome","resolutionComment","createdAt","resolvedAt"),c.fieldNames().asSequence().toSet())
            assertEquals(id,c["id"].asLong()); assertEquals(0,c["version"].asLong()); assertEquals(who,c["openedBy"].asLong())
            assertEquals("reason",c["openingComment"].asText()); assertEquals("AWAITING_PAYMENT",c["previousOrderStatus"].asText())
            for(key in listOf("telegramUrl","resolvedBy","outcome","resolutionComment","resolvedAt")) assertTrue(c[key].isNull)
            assertEquals(listOf(first,second),detail["caseImages"].map { it.asLong() })
            assertEquals(listOf(proof,first,second),detail["orderImages"].map { it.asLong() })
            assertEquals(f.buyer,detail["buyerId"].asLong()); assertEquals(f.seller,detail["sellerId"].asLong())
            assertEquals(proof,detail["paymentProofImageId"].asLong())
            assertEquals("RUB",detail["currency"].asText()); assertEquals(10f,detail["totalPrice"].floatValue())
            parity("dispute-$sellerOpens",detail)
            error(request("POST","/order/orders/${f.order}/DISPUTED?comment=again",who),409,"ORDER_CASE_ALREADY_OPEN","По заказу уже открыто обращение")
            assertEquals(1,count("SELECT count(*) FROM order_case WHERE order_id=${f.order}"))
            error(request("GET","/order/${f.order}/cases",participant()),400,"BAD_REQUEST",invalid)
        }
    }

    @Test
    fun `cancellation request payment dismisses case and dispute payment reuses ordinary lifecycle`() {
        for(prepaid in listOf(false,true)) {
            val f=fixture("PREORDER",if(prepaid) 5f else 0f)
            val state=if(prepaid) "AWAITING_PREPAYMENT" else "AWAITING_PAYMENT"
            jdbc.update("UPDATE \"order\" SET status=CAST(? AS order_status) WHERE id=?",state,f.order)
            val before=histories(f.order)
            val created=request("POST","/order/${f.order}/cancellation-request?comment=cancel",f.seller); ok(created)
            assertEquals(state,status(f.order)); assertEquals(before,histories(f.order))
            val proof=image(f.buyer)
            ok(request("POST","/order/${f.order}/${if(prepaid) "AWAITING_PREPAYMENT_APPROVAL" else "ASSEMBLING"}?imageId=$proof",f.buyer))
            val c=json.readTree(request("GET","/order/${f.order}/cases",f.buyer).body()).single()["orderCase"]
            assertEquals("RESOLVED",c["state"].asText()); assertEquals("REJECTED",c["outcome"].asText())
            assertEquals("Получено подтверждение оплаты",c["resolutionComment"].asText()); assertEquals(1,c["version"].asLong())
            parity("dismiss-$prepaid",c)
        }
        val f=paid()
        ok(request("POST","/order/orders/${f.order}/DISPUTED?comment=dispute",f.seller))
        val before=histories(f.order); val replacement=image(f.buyer)
        ok(request("POST","/order/${f.order}/ASSEMBLING?imageId=$replacement",f.buyer))
        assertEquals("DISPUTED",status(f.order)); assertEquals(before,histories(f.order))
        assertEquals(listOf(replacement),json.readTree(request("GET","/order/${f.order}/cases",f.buyer).body()).single()["caseImages"].map { it.asLong() })
    }

    @Test
    fun `payment appeal buyer only once even after resolved and deleted product retained snapshot`() {
        val f=fixture()
        jdbc.update("UPDATE \"order\" SET status='CANCELLED' WHERE id=?",f.order)
        jdbc.update("DELETE FROM product WHERE id=?",f.product)
        error(request("POST","/order/${f.order}/payment-appeal?comment=paid",f.seller),400,"BAD_REQUEST",invalid)
        val evidence=image(f.buyer)
        ok(request("POST","/order/${f.order}/payment-appeal?comment=paid&imageIds=$evidence",f.buyer))
        val detail=json.readTree(request("GET","/order/${f.order}/cases",f.seller).body()).single()
        assertEquals(f.product,detail["productId"].asLong()); assertEquals(0f,detail["prepaymentAmount"].floatValue()); assertEquals("RUB",detail["currency"].asText())
        assertTrue(detail["paymentProofImageId"].isNull); assertEquals("CANCELLED",detail["orderCase"]["previousOrderStatus"].asText())
        parity("appeal",detail)
        jdbc.update("UPDATE order_case SET state='RESOLVED',outcome='BUYER',resolved_at=now() WHERE order_id=?",f.order)
        error(request("POST","/order/${f.order}/payment-appeal?comment=again",f.buyer),409,"PAYMENT_APPEAL_ALREADY_EXISTS","Обращение по оплате уже создано")
    }

    @Test
    fun `missing empty blank foreign and invalid image inputs rollback entire transition`() {
        val f=paid()
        assertEquals("[]",request("GET","/order/${f.order}/cases",f.buyer).body())
        error(request("POST","/order/orders/${f.order}/DISPUTED?comment=%20%20",f.buyer),400,"BAD_REQUEST","Укажите причину обращения")
        assertEquals(400,request("POST","/order/orders/${f.order}/DISPUTED",f.buyer).statusCode())
        error(request("POST","/order/orders/${f.order}/DISPUTED?comment=x",participant()),400,"BAD_REQUEST",invalid)
        error(request("GET","/order/9223372036854775807/cases",f.buyer),400,"BAD_REQUEST",invalid)
        val good=image(f.buyer)
        val other=fixture()
        val invalidImages=listOf(-1L,Long.MAX_VALUE,image(f.seller),image(f.buyer,tag="PRODUCT"),
            image(f.buyer,state="ACTIVE"),image(f.buyer,state="DELETE"),image(f.buyer,entity=other.order),good)
        val before=histories(f.order)
        invalidImages.forEach { bad ->
            error(request("POST","/order/orders/${f.order}/DISPUTED?comment=x&imageIds=$good,$bad",f.buyer),400,"BAD_REQUEST",invalid)
            assertEquals("AWAITING_PAYMENT",status(f.order)); assertEquals(before,histories(f.order))
            assertEquals(0,count("SELECT count(*) FROM order_case WHERE order_id=${f.order}"))
            assertEquals("TEMPORARY",jdbc.queryForObject("SELECT status::text FROM image WHERE id=?",String::class.java,good))
        }
        ok(request("POST","/order/orders/${f.order}/DISPUTED?comment=x&imageIds=",f.buyer))
        assertEquals(0,count("SELECT count(*) FROM order_case_image WHERE case_id IN (SELECT id FROM order_case WHERE order_id=${f.order})"))
    }

    @Test
    fun `state matrix cancellation and dispute null evidence precedence and list ordering`() {
        val states=listOf("BOOKED","AWAITING_PREPAYMENT","AWAITING_PREPAYMENT_APPROVAL","AWAITING_PAYMENT","ASSEMBLING","ON_THE_WAY","COMPLETED","CANCELLED","FAILED","DISPUTED")
        for(state in states) for(proof in listOf(false,true)) {
            val f=fixture("PREORDER",5f)
            val id=if(proof) image(f.buyer,state="ACTIVE",entity=f.order) else null
            jdbc.update("UPDATE \"order\" SET status=CAST(? AS order_status),image_payment_proof_id=? WHERE id=?",state,id,f.order)
            val response=request("POST","/order/orders/${f.order}/DISPUTED?comment=x",f.buyer)
            if(proof && state in setOf("AWAITING_PREPAYMENT_APPROVAL","AWAITING_PAYMENT","ASSEMBLING","ON_THE_WAY")) ok(response)
            else error(response,400,"ORDER_CASE_INVALID_TRANSITION",invalid)
            val c=fixture("PREORDER",5f)
            jdbc.update("UPDATE \"order\" SET status=CAST(? AS order_status),image_payment_proof_id=? WHERE id=?",state,id,c.order)
            val cancellation=request("POST","/order/${c.order}/cancellation-request?comment=x",c.buyer)
            if(!proof && state=="AWAITING_PREPAYMENT") ok(cancellation) else error(cancellation,400,"ORDER_CASE_INVALID_TRANSITION",invalid)
        }
        val f=paid()
        val old=case(f,"CANCELLATION_REQUEST","AWAITING_PAYMENT")
        jdbc.update("UPDATE order_case SET state='RESOLVED',outcome='REJECTED',resolved_at=now(),created_at='2000-01-01' WHERE id=?",old)
        ok(request("POST","/order/orders/${f.order}/DISPUTED?comment=x",f.buyer))
        assertEquals(listOf("OPEN","RESOLVED"),json.readTree(request("GET","/order/${f.order}/cases",f.buyer).body()).map { it["orderCase"]["state"].asText() })
    }

    fun paid(): Fixture = fixture("PREORDER",5f).also { f ->
        val proof=image(f.buyer,state="ACTIVE",entity=f.order)
        jdbc.update("UPDATE \"order\" SET status='AWAITING_PAYMENT',image_payment_proof_id=? WHERE id=?",proof,f.order)
    }
    fun histories(id: Long)=count("SELECT count(*) FROM order_status_history WHERE order_id=$id")
    val invalid="Операция недоступна для текущего состояния заказа или обращения"
    fun parity(scenario: String, node: JsonNode) {
        fun normalize(n: JsonNode, field: String=""): JsonNode {
            if(n.isNull) return n
            if(field in setOf("id","orderId","buyerId","sellerId","productId","openedBy","resolvedBy","paymentProofImageId")) return json.nodeFactory.textNode("<id>")
            if(field in setOf("createdAt","resolvedAt")) return json.nodeFactory.textNode("<time>")
            if(n.isObject) return json.createObjectNode().also { o -> n.fields().forEachRemaining { (k,v) -> o.set<JsonNode>(k,normalize(v,k)) } }
            if(n.isArray) return json.createArrayNode().also { a -> n.forEach { a.add(normalize(it,if(field in setOf("caseImages","orderImages")) "id" else "")) } }
            return n
        }
        val normalized=normalize(node)
        paritySnapshots.putIfAbsent(scenario,normalized)?.let { assertEquals(it,normalized,"Full legacy/modern JSON parity $scenario") }
    }

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

    companion object {
        // Private instance for this contract family; never a shared application/production database.
        val postgres: EmbeddedPostgres = EmbeddedPostgres.start()
        val sequence = AtomicLong()
        private val paritySnapshots=java.util.concurrent.ConcurrentHashMap<String,JsonNode>()
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

@ActiveProfiles("legacy")
class UserOrderCaseLegacyHttpTest : UserCaseHttpContract()

@ActiveProfiles("modern")
class UserOrderCaseModernHttpTest : UserCaseHttpContract() {
    @Autowired lateinit var cases: com.model_store.modern.ordering.`case`.user.application.UserOrderCases
    @Autowired lateinit var transactions: org.springframework.transaction.PlatformTransactionManager

    fun actor(id: Long) = com.model_store.modern.shared.domain.Actor(id,"user","USER",com.model_store.modern.shared.domain.TokenType.ACCESS)

    fun waitForParticipantWaiters(holder: Int, expected: Int): List<Int> =
        waitForWaiters(holder,expected,"SELECT id FROM participant WHERE id=? FOR UPDATE")

    fun waitForWaiters(holder: Int, expected: Int, expectedSql: String): List<Int> {
        val deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
        var waiting=emptyList<Pair<Int,String>>()
        do {
            jdbc.execute("SELECT pg_stat_clear_snapshot()")
            waiting=jdbc.query("""WITH RECURSIVE blocked(pid) AS (
                SELECT pid FROM pg_stat_activity WHERE ?=ANY(pg_blocking_pids(pid))
                UNION SELECT a.pid FROM pg_stat_activity a JOIN blocked b ON b.pid=ANY(pg_blocking_pids(a.pid))
                ) SELECT a.pid,a.query FROM pg_stat_activity a JOIN blocked b ON b.pid=a.pid
                WHERE a.wait_event_type='Lock' AND a.query LIKE ?""",
                { r,_ -> r.getInt(1) to r.getString(2) },holder,expectedSql.replace("?","%"))
            if(waiting.size>=expected) break
            Thread.sleep(20)
        } while(System.nanoTime()<deadline)
        assertEquals(expected,waiting.size,"Exact holder session $holder must block $expected participant queries")
        for((pid,sql) in waiting) {
            assertNotEquals(holder,pid)
            assertEquals(expectedSql,sql.replace(Regex("\\$[0-9]+"),"?"))
            val direct=jdbc.queryForObject("SELECT pg_blocking_pids(?)::text",String::class.java,pid)
            println("case lock proof: waiter=$pid rootHolder=$holder directBlockers=$direct sql=$sql")
        }
        return waiting.map { it.first }
    }

    @Test
    fun `changed parties after scalar observation refuse without following new participant locks`() {
        val replacement=participant() // Lower than both original party IDs.
        val f=paid(); val proof=image(f.seller)
        val pool=java.util.concurrent.Executors.newSingleThreadExecutor()
        var pending: java.util.concurrent.Future<HttpResponse<String>>?=null
        try {
            org.springframework.transaction.support.TransactionTemplate(transactions).execute {
                val holder=jdbc.queryForObject("SELECT pg_backend_pid()",Int::class.java)!!
                jdbc.queryForObject("SELECT id FROM participant WHERE id=? FOR UPDATE",Long::class.java,replacement)
                jdbc.queryForObject("SELECT id FROM participant WHERE id=? FOR UPDATE",Long::class.java,f.buyer)
                pending=pool.submit<HttpResponse<String>> {
                    request("POST","/order/orders/${f.order}/DISPUTED?comment=x&imageIds=$proof",f.seller)
                }
                waitForParticipantWaiters(holder,1)
                // Caller remains seller, so rejection specifically requires association recheck.
                jdbc.update("UPDATE \"order\" SET customer_id=? WHERE id=?",replacement,f.order)
            }
            error(pending!!.get(10,java.util.concurrent.TimeUnit.SECONDS),400,"BAD_REQUEST",invalid)
            assertEquals("AWAITING_PAYMENT",status(f.order))
            assertEquals(0,count("SELECT count(*) FROM order_case WHERE order_id=${f.order}"))
            assertEquals("TEMPORARY",jdbc.queryForObject("SELECT status::text FROM image WHERE id=?",String::class.java,proof))
        } finally { pool.shutdownNow() }
    }

    @Test
    fun `agent flag revoked while waiting is rechecked under participant lock`() {
        val f=paid(); val before=histories(f.order)
        val pool=java.util.concurrent.Executors.newSingleThreadExecutor()
        lateinit var pending: java.util.concurrent.Future<HttpResponse<String>>
        try {
            org.springframework.transaction.support.TransactionTemplate(transactions).execute {
                val holder=jdbc.queryForObject("SELECT pg_backend_pid()",Int::class.java)!!
                jdbc.queryForObject("SELECT id FROM participant WHERE id=? FOR UPDATE",Long::class.java,f.buyer)
                pending=pool.submit<HttpResponse<String>> {
                    request("POST","/order/orders/${f.order}/DISPUTED?comment=x",f.buyer)
                }
                waitForParticipantWaiters(holder,1)
                jdbc.update("UPDATE participant SET is_agent=true WHERE id=?",f.buyer)
            }
            val response=pending.get(10,java.util.concurrent.TimeUnit.SECONDS)
            assertEquals(403,response.statusCode(),response.body()); assertEquals("",response.body())
            assertEquals("AWAITING_PAYMENT",status(f.order)); assertEquals(before,histories(f.order))
            assertEquals(0,count("SELECT count(*) FROM order_case WHERE order_id=${f.order}"))
        } finally { pool.shutdownNow() }
    }

    @Test
    fun `opening dispute races real payment without lost proof history or orphan case`() {
        val f=paid(); val next=image(f.buyer); val evidence=image(f.seller)
        val gate=java.util.concurrent.CyclicBarrier(2)
        val pool=java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            lateinit var dispute: java.util.concurrent.Future<HttpResponse<String>>
            lateinit var payment: java.util.concurrent.Future<HttpResponse<String>>
            org.springframework.transaction.support.TransactionTemplate(transactions).execute {
                val holder=jdbc.queryForObject("SELECT pg_backend_pid()",Int::class.java)!!
                jdbc.queryForObject("SELECT id FROM participant WHERE id=? FOR UPDATE",Long::class.java,f.buyer)
                dispute=pool.submit<HttpResponse<String>> {
                    gate.await(5,java.util.concurrent.TimeUnit.SECONDS)
                    request("POST","/order/orders/${f.order}/DISPUTED?comment=x&imageIds=$evidence",f.seller)
                }
                payment=pool.submit<HttpResponse<String>> {
                    gate.await(5,java.util.concurrent.TimeUnit.SECONDS)
                    request("POST","/order/${f.order}/ASSEMBLING?imageId=$next",f.buyer)
                }
                waitForParticipantWaiters(holder,2)
            }
            ok(dispute.get(15,java.util.concurrent.TimeUnit.SECONDS)); ok(payment.get(15,java.util.concurrent.TimeUnit.SECONDS))
            assertEquals("DISPUTED",status(f.order))
            assertEquals(next,jdbc.queryForObject("SELECT image_payment_proof_id FROM \"order\" WHERE id=?",Long::class.java,f.order))
            assertEquals(1,count("SELECT count(*) FROM order_case WHERE order_id=${f.order} AND state='OPEN'"))
            assertEquals(2,count("SELECT count(*) FROM image WHERE id IN ($next,$evidence) AND status='ACTIVE'"))
            val previous=jdbc.queryForObject("SELECT previous_order_status::text FROM order_case WHERE order_id=?",String::class.java,f.order)
            assertEquals(if(previous=="AWAITING_PAYMENT") 1 else 0,count("SELECT count(*) FROM order_case_image WHERE image_id=$next"))
        } finally { pool.shutdownNow() }
    }

    @Test
    fun `busy image returns bounded conflict and late case relation error rolls back every owner`() {
        val f=paid(); val img=image(f.buyer); val before=histories(f.order)
        val pool=java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            org.springframework.transaction.support.TransactionTemplate(transactions).execute {
                jdbc.queryForObject("SELECT id FROM image WHERE id=? FOR UPDATE",Long::class.java,img)
                val busy=pool.submit<HttpResponse<String>> {
                    request("POST","/order/orders/${f.order}/DISPUTED?comment=x&imageIds=$img",f.buyer)
                }.get(10,java.util.concurrent.TimeUnit.SECONDS)
                error(busy,409,"IMAGE_BUSY","Изображение занято. Повторите операцию")
            }
        } finally { pool.shutdownNow() }
        assertEquals("AWAITING_PAYMENT",status(f.order)); assertEquals(before,histories(f.order))
        val other=fixture(); val old=case(other,"DISPUTE","ON_THE_WAY")
        // Deliberately inconsistent fixture forces a failure after successful media activation.
        jdbc.update("INSERT INTO order_case_image(case_id,image_id,uploaded_by) VALUES (?,?,?)",old,img,f.buyer)
        val failed=request("POST","/order/orders/${f.order}/DISPUTED?comment=x&imageIds=$img",f.buyer)
        assertEquals(409,failed.statusCode(),failed.body()); assertEquals("DUPLICATE_KEY",json.readTree(failed.body())["code"].asText())
        assertEquals("AWAITING_PAYMENT",status(f.order)); assertEquals(before,histories(f.order))
        assertEquals(0,count("SELECT count(*) FROM order_case WHERE order_id=${f.order}"))
        assertEquals("TEMPORARY",jdbc.queryForObject("SELECT status::text FROM image WHERE id=?",String::class.java,img))
    }

    @Test
    fun `JWT party rights ADMIN retains party restriction and agent token rejected`() {
        val f=paid()
        val admin=participant("ADMIN")
        error(request("POST","/order/orders/${f.order}/DISPUTED?comment=x",admin,role="ADMIN"),400,"BAD_REQUEST",invalid)
        assertEquals(403,request("GET","/order/${f.order}/cases",f.buyer,type="agent_access").statusCode())
        assertEquals(403,request("POST","/order/orders/${f.order}/DISPUTED?comment=x",f.buyer,type="agent_access").statusCode())
        assertEquals(401,request("GET","/order/${f.order}/cases",f.buyer,type="refresh").statusCode())
        error(request("GET","/order/${f.order}/cases",f.buyer,role="OTHER"),403,"ACCESS_DENIED","Доступ запрещён")
        jdbc.update("UPDATE participant SET role='ADMIN' WHERE id=?",f.buyer)
        ok(request("POST","/order/orders/${f.order}/DISPUTED?comment=x",f.buyer,role="ADMIN"))
        ok(request("GET","/order/${f.order}/cases",f.buyer,role="ADMIN"))
    }

    @Test
    fun `concurrent opening second case single commit no partial evidence`() {
        val f=paid(); val a=image(f.buyer); val b=image(f.seller)
        val gate=java.util.concurrent.CyclicBarrier(2)
        val pool=java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            var futures=emptyList<java.util.concurrent.Future<HttpResponse<String>>>()
            org.springframework.transaction.support.TransactionTemplate(transactions).execute {
                val holder=jdbc.queryForObject("SELECT pg_backend_pid()",Int::class.java)!!
                jdbc.queryForObject("SELECT id FROM participant WHERE id=? FOR UPDATE",Long::class.java,f.buyer)
                futures=listOf(f.buyer to a,f.seller to b).map { (who,id) -> pool.submit<HttpResponse<String>> {
                    gate.await(5,java.util.concurrent.TimeUnit.SECONDS)
                    request("POST","/order/orders/${f.order}/DISPUTED?comment=x&imageIds=$id",who)
                } }
                waitForParticipantWaiters(holder,2)
            }
            val results=futures.map { it.get(15,java.util.concurrent.TimeUnit.SECONDS) }
            assertEquals(listOf(200,409),results.map { it.statusCode() }.sorted())
            assertEquals(1,count("SELECT count(*) FROM order_case WHERE order_id=${f.order}"))
            assertEquals(1,count("SELECT count(*) FROM image WHERE id IN ($a,$b) AND status='ACTIVE'"))
            assertEquals("DISPUTED",status(f.order))
        } finally { pool.shutdownNow() }
    }

    @Test
    fun `case and evidence rollback with later caller failure and guard rejects no or readonly tx`() {
        val f=paid(); val image=image(f.buyer); val before=histories(f.order)
        val target=org.springframework.test.util.AopTestUtils.getTargetObject<com.model_store.modern.ordering.`case`.user.application.UserOrderCases>(cases)
        assertThrows(com.model_store.modern.ordering.lifecycle.application.LifecycleTransactionRequired::class.java) {
            target.open(actor(f.buyer),f.order,com.model_store.modern.ordering.`case`.user.domain.CaseKind.DISPUTE,"x")
        }
        assertThrows(com.model_store.modern.ordering.lifecycle.application.LifecycleTransactionRequired::class.java) {
            org.springframework.transaction.support.TransactionTemplate(org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.dataSource!!))
                .execute { target.open(actor(f.buyer),f.order,com.model_store.modern.ordering.`case`.user.domain.CaseKind.DISPUTE,"x") }
        }
        val foreign=org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean().apply {
            setDataSource(jdbc.dataSource!!)
            setPackagesToScan("com.model_store.modern.ordering.case.user")
            persistenceUnitName="case-audit-foreign"
            jpaVendorAdapter=org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter()
            setJpaPropertyMap(mapOf("hibernate.hbm2ddl.auto" to "none"))
            afterPropertiesSet()
        }
        try {
            assertThrows(com.model_store.modern.ordering.lifecycle.application.LifecycleTransactionRequired::class.java) {
                org.springframework.transaction.support.TransactionTemplate(org.springframework.orm.jpa.JpaTransactionManager(foreign.`object`!!))
                    .execute { target.open(actor(f.buyer),f.order,com.model_store.modern.ordering.`case`.user.domain.CaseKind.DISPUTE,"x") }
            }
        } finally { foreign.destroy() }
        val tx=org.springframework.transaction.support.TransactionTemplate(transactions)
        tx.isReadOnly=true
        assertThrows(com.model_store.modern.ordering.lifecycle.application.LifecycleTransactionRequired::class.java) {
            tx.execute { cases.open(actor(f.buyer),f.order,com.model_store.modern.ordering.`case`.user.domain.CaseKind.DISPUTE,"x") }
        }
        tx.isReadOnly=false
        assertThrows(IllegalStateException::class.java) { tx.execute {
            cases.open(actor(f.buyer),f.order,com.model_store.modern.ordering.`case`.user.domain.CaseKind.DISPUTE,"x",listOf(image))
            throw IllegalStateException("later failure")
        } }
        assertEquals("AWAITING_PAYMENT",status(f.order)); assertEquals(before,histories(f.order))
        assertEquals(0,count("SELECT count(*) FROM order_case WHERE order_id=${f.order}"))
        assertEquals("TEMPORARY",jdbc.queryForObject("SELECT status::text FROM image WHERE id=?",String::class.java,image))
    }

    @Test
    fun `payment cancellation expiry races serialize with case opening`() {
        val pool=java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            for(scenario in listOf("payment","cancel","expiry")) {
                val f=fixture()
                if(scenario=="payment") jdbc.update("UPDATE \"order\" SET status='AWAITING_PAYMENT' WHERE id=?",f.order)
                if(scenario=="expiry") jdbc.update("UPDATE \"order\" SET created_at=now()-interval '2 days' WHERE id=?",f.order)
                val proof=image(f.buyer)
                val gate=java.util.concurrent.CyclicBarrier(2)
                lateinit var opening: java.util.concurrent.Future<HttpResponse<String>>
                lateinit var other: java.util.concurrent.Future<Int>
                org.springframework.transaction.support.TransactionTemplate(transactions).execute {
                    val holder=jdbc.queryForObject("SELECT pg_backend_pid()",Int::class.java)!!
                    jdbc.queryForObject("SELECT id FROM participant WHERE id=? FOR UPDATE",Long::class.java,f.buyer)
                    if(scenario=="expiry") jdbc.queryForObject("SELECT id FROM \"order\" WHERE id=? FOR UPDATE",Long::class.java,f.order)
                    opening=pool.submit<HttpResponse<String>> {
                        gate.await(5,java.util.concurrent.TimeUnit.SECONDS)
                        request("POST","/order/${f.order}/${if(scenario=="payment") "cancellation-request" else "payment-appeal"}?comment=x",f.buyer)
                    }
                    other=pool.submit<Int> {
                        gate.await(5,java.util.concurrent.TimeUnit.SECONDS)
                        when(scenario) {
                            "payment" -> request("POST","/order/${f.order}/ASSEMBLING?imageId=$proof",f.buyer).statusCode()
                            "cancel" -> request("POST","/order/${f.order}/CANCELLED",f.buyer,"{}").statusCode()
                            else -> { jdbc.execute("SELECT process_expired_booked_orders()"); 200 }
                        }
                    }
                    waitForParticipantWaiters(holder,if(scenario=="expiry") 1 else 2)
                    if(scenario=="expiry") waitForWaiters(holder,1,"SELECT process_expired_booked_orders()")
                }
                val result=opening.get(15,java.util.concurrent.TimeUnit.SECONDS)
                assertEquals(200,other.get(15,java.util.concurrent.TimeUnit.SECONDS))
                if(scenario=="payment") {
                    assertTrue(result.statusCode() in setOf(200,400),result.body())
                    assertEquals("ASSEMBLING",status(f.order))
                    assertEquals(0,count("SELECT count(*) FROM order_case WHERE order_id=${f.order} AND state='OPEN'"))
                } else {
                    if(result.statusCode()==200) {
                        assertEquals("DISPUTED",status(f.order))
                        assertEquals("PAYMENT_APPEAL",jdbc.queryForObject("SELECT kind FROM order_case WHERE order_id=?",String::class.java,f.order))
                    } else {
                        error(result,400,"ORDER_CASE_INVALID_TRANSITION",invalid)
                        assertEquals("CANCELLED",status(f.order))
                    }
                    assertEquals(4,stock(f.product))
                }
            }
        } finally { pool.shutdownNow() }
    }
}
