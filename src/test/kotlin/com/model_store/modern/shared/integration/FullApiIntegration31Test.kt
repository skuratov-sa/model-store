package com.model_store.modern.shared.integration

import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.model.PutObjectRequest
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.model_store.modern.media.image.maintenance.application.ImageCleanupObjects
import io.jsonwebtoken.Jwts
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.mockito.ArgumentMatchers.argThat
import org.mockito.Mockito.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.*
import java.security.KeyFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Duration
import java.time.Instant
import java.util.*
import javax.imageio.ImageIO

/** Only initial actors/delivery/historical imported agent rows are SQL fixtures.
 * All human order transitions in these scenarios go through the public HTTP API. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = [
    "spring.config.import=", "app.public-key-path=keys/test_public_key.pem",
    "app.private-key-path=keys/test_private_key.pem", "logging.level.root=WARN"])
@ActiveProfiles("modern")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
abstract class Integration31Fixture {
    @Value("\${local.server.port}") var port: Int = 0
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var json: ObjectMapper
    @Autowired lateinit var context: ConfigurableApplicationContext
    @MockitoBean lateinit var s3: AmazonS3
    @MockitoBean lateinit var mail: JavaMailSender
    @MockitoBean lateinit var objects: ImageCleanupObjects
    private lateinit var http: HttpClient
    @BeforeEach fun startClient() { http=HttpClient.newBuilder().proxy(java.net.ProxySelector.of(null))
        .connectTimeout(Duration.ofSeconds(3)).build() }
    @AfterEach fun stopClient() { http.shutdownNow(); assertTrue(http.awaitTermination(Duration.ofSeconds(5))) }

    fun disposePostgresWithContext(pg: EmbeddedPostgres) {
        // Let Spring's AFTER_CLASS listener close schedulers/context before disposing the private DB.
        val beans=context.beanFactory as org.springframework.beans.factory.support.DefaultListableBeanFactory
        beans.registerSingleton("integration31Postgres",pg)
        beans.registerDisposableBean("integration31Postgres",org.springframework.beans.factory.DisposableBean {pg.close()})
        beans.registerDependentBean("integration31Postgres","dataSource")
    }

    fun person(role: String = "USER", agent: Boolean = false): Long {
        val name = UUID.randomUUID().toString()
        return jdbc.queryForObject("""INSERT INTO participant(login,mail,full_name,password,status,role,is_agent,deadline_sending,deadline_payment)
            VALUES (?,?,'Integration reviewer','fixture','ACTIVE',?::participant_role,?,3,7) RETURNING id""", Long::class.java, name,"$name@example.test",role,agent)!!
    }
    data class Offer(val buyer: Long, val seller: Long, val product: Long, val address: Long, val transfer: Long)
    fun delivery(buyer: Long, seller: Long): Pair<Long,Long> {
        val address = jdbc.queryForObject("INSERT INTO address(country,city) VALUES ('RU','Moscow') RETURNING id", Long::class.java)!!
        jdbc.update("INSERT INTO participant_address(participant_id,address_id) VALUES (?,?)",buyer,address)
        val transfer = jdbc.queryForObject("INSERT INTO transfer(sending,price,currency,participant_id) VALUES ('PRODUCT_PICKUP',0,'RUB',?) RETURNING id",Long::class.java,seller)!!
        jdbc.update("INSERT INTO social_network(type,login,participant_id) VALUES ('TELEGRAM','integration31',?)",seller)
        return address to transfer
    }
    fun offer(preorder: Boolean = false): Offer {
        val buyer=person(); val seller=person(); val (address,transfer)=delivery(buyer,seller)
        val image=upload(seller,"PRODUCT")
        val product=ok(request("POST","/products",seller,"""{"name":"integration31","price":20,"currency":"RUB",
            "availability":"${if(preorder) "PREORDER" else "PURCHASABLE"}","prepaymentAmount":${if(preorder) 5 else 0},
            "count":4,"used":false,"imageIds":[$image]}""")).asLong()
        assertEquals("ACTIVE",jdbc.queryForObject("SELECT status::text FROM image WHERE id=?",String::class.java,image))
        val metadata=ok(request("GET","/images/metadata?ids=$image",null))
        assertEquals(image,metadata.single()["id"].asLong())
        assertTrue(metadata.single()["originalUrl"].asText().contains("/original/"))
        ok(request("GET","/product/$product",null))
        return Offer(buyer,seller,product,address,transfer)
    }
    fun book(f: Offer): Long = ok(request("POST","/order/BOOKED",f.buyer,
        """[{"productId":${f.product},"count":1,"addressId":${f.address},"transferId":${f.transfer},"comment":"book31"}]""")).single().asLong()
    fun upload(actor: Long, tag: String = "ORDER"): Long {
        val bytes=ByteArrayOutputStream().also { ImageIO.write(BufferedImage(8,8,BufferedImage.TYPE_INT_RGB),"png",it) }.toByteArray()
        val boundary="integration31${UUID.randomUUID()}"
        val body=ByteArrayOutputStream().apply {
            write("--$boundary\r\nContent-Disposition: form-data; name=\"files\"; filename=\"proof.png\"\r\nContent-Type: image/png\r\n\r\n".toByteArray())
            write(bytes); write("\r\n--$boundary--\r\n".toByteArray())
        }.toByteArray()
        val response=http.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/images?tag=$tag"))
            .timeout(Duration.ofSeconds(20)).header("Authorization","Bearer ${token(actor)}")
            .header("Content-Type","multipart/form-data; boundary=$boundary")
            .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),HttpResponse.BodyHandlers.ofString())
        val id=ok(response).single().asLong()
        val row=jdbc.queryForMap("SELECT filename,tag::text,status::text,uploaded_by,entity_id,width,height FROM image WHERE id=?",id)
        assertEquals(tag,row["tag"]); assertEquals("TEMPORARY",row["status"])
        assertEquals(actor,(row["uploaded_by"] as Number).toLong()); Assertions.assertNull(row["entity_id"])
        assertEquals(8,(row["width"] as Number).toInt()); assertEquals(8,(row["height"] as Number).toInt())
        for(variant in listOf("original","medium","thumbnail")) verify(s3).putObject(argThat<PutObjectRequest> {
            it.key=="$variant/${row["filename"]}" && it.metadata.contentType=="image/jpeg" && it.metadata.contentLength>0
        })
        return id
    }
    fun request(method: String,path: String,actor: Long?,body: String? = null,role: String = "USER",type: String = "access"): HttpResponse<String> {
        val builder=HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).timeout(Duration.ofSeconds(20))
        if(actor!=null) builder.header("Authorization","Bearer ${token(actor,role,type)}")
        if(body!=null) builder.header("Content-Type","application/json")
        return http.send(builder.method(method,body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString())
    }
    fun ok(response: HttpResponse<String>): JsonNode { assertEquals(200,response.statusCode(),response.body()); return if(response.body().isEmpty()) json.nullNode() else json.readTree(response.body()) }
    fun error(response: HttpResponse<String>,status: Int,code: String): JsonNode {
        assertEquals(status,response.statusCode(),response.body()); val node=json.readTree(response.body())
        assertEquals(setOf("code","message","status","timestamp","details"),node.fieldNames().asSequence().toSet())
        assertEquals(code,node["code"].asText()); assertEquals(status,node["status"].asInt()); assertTrue(node["details"].isNull)
        val message=when(code) {
            "ORDER_NOT_TERMINATED" -> "Нельзя оставить отзыв пока заказ еще активен"
            "ORDER_CASE_ALREADY_RESOLVED" -> "Обращение уже завершено"
            "REVIEW_ALREADY_EXIST" -> "Вы уже оставили отзыв для этого заказа"
            "ORDER_NOT_FOUND" -> "Нет данных о заказе товара"
            "PAYMENT_APPEAL_ALREADY_EXISTS" -> "Обращение по оплате уже создано"
            "ACCESS_DENIED" -> "Доступ запрещён"
            "INTERNAL_ERROR" -> "Внутренняя ошибка"
            "IMAGE_NOT_FOUND" -> "Подтверждение оплаты не найдено"
            else -> kotlin.error("No exact error-message oracle for $code")
        }
        assertEquals(message,node["message"].asText())
        assertDoesNotThrow { java.time.OffsetDateTime.parse(node["timestamp"].asText()) }; return node
    }
    fun number(sql: String): Int = jdbc.queryForObject(sql,Int::class.java)!!
    fun status(id: Long) = jdbc.queryForObject("SELECT status::text FROM \"order\" WHERE id=?",String::class.java,id)
    fun stock(id: Long) = jdbc.queryForObject("SELECT count FROM product WHERE id=?",Int::class.java,id)
    fun history(id: Long) = number("SELECT count(*) FROM order_status_history WHERE order_id=$id")
    fun audits(id: Long) = number("SELECT count(*) FROM admin_agent_order_action WHERE order_id=$id")
    fun importedAgentOrder(): Pair<Offer,Long> {
        // Modern booking intentionally excludes agents. Historical agent orders are a supported initial dataset.
        val buyer=person(); val seller=person(agent=true); val (address,transfer)=delivery(buyer,seller)
        val product=jdbc.queryForObject("""INSERT INTO product(name,price,currency,participant_id,status,availability,count,used)
            VALUES ('historical31',20,'RUB',?,'ACTIVE','PURCHASABLE',3,false) RETURNING id""",Long::class.java,seller)!!
        val order=jdbc.queryForObject("""INSERT INTO "order"(seller_id,customer_id,count,status,product_id,address_id,transfer_id,total_price,
            prepayment_amount,product_name,product_unit_price,product_currency,product_availability)
            VALUES (?,?,1,'BOOKED',?,?,?,20,0,'historical31',20,'RUB','PURCHASABLE') RETURNING id""",Long::class.java,seller,buyer,product,address,transfer)!!
        return Offer(buyer,seller,product,address,transfer) to order
    }
    companion object {
        val key by lazy {
            val pem=ClassPathResource("keys/test_private_key.pem").inputStream.bufferedReader().use {it.readText()}
                .replace("-----BEGIN PRIVATE KEY-----","").replace("-----END PRIVATE KEY-----","").replace(Regex("\\s+"),"")
            KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)))
        }
        fun token(id: Long,role: String="USER",type: String="access"): String = Jwts.builder().claim("id",id)
            .claim("login","fixture31").claim("role",role).claim("type",type).claim("issuedBy","admin")
            .issuedAt(Date.from(Instant.now())).expiration(Date.from(Instant.now().plusSeconds(600))).signWith(key).compact()
        fun properties(registry: DynamicPropertyRegistry,pg: EmbeddedPostgres) {
            registry.add("spring.datasource.url") {"jdbc:postgresql://127.0.0.1:${pg.port}/postgres"}
            registry.add("spring.datasource.username") {"postgres"}; registry.add("spring.datasource.password") {""}
            registry.add("spring.flyway.url") {"jdbc:postgresql://127.0.0.1:${pg.port}/postgres"}
            registry.add("spring.flyway.user") {"postgres"}; registry.add("spring.flyway.password") {""}
        }
    }
}

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FullApiIntegration31Test : Integration31Fixture() {
    @AfterAll fun closeFixtures() = disposePostgresWithContext(pg)
    @Test fun `HTTP catalog media booking prepayment dispute admin resolution review and replays`() {
        val f=offer(preorder=true); val admin=person("ADMIN"); val stranger=person()
        val prepared=ok(request("GET","/order?productId=${f.product}",f.buyer))
        assertEquals(f.address,prepared["addresses"].single()["id"].asLong())
        val order=book(f); assertEquals(4,stock(f.product)); assertEquals(1,history(order))
        error(request("POST","/reviews",f.buyer,"""{"orderId":$order,"rating":5}"""),400,"ORDER_NOT_TERMINATED")
        ok(request("POST","/order/$order/AWAITING_PREPAYMENT?comment=agree",f.seller))
        val foreignProof=upload(stranger); val beforeForeign=history(order)
        error(request("POST","/order/$order/AWAITING_PREPAYMENT_APPROVAL?imageId=$foreignProof",f.buyer),404,"IMAGE_NOT_FOUND")
        assertEquals("AWAITING_PREPAYMENT",status(order)); assertEquals(beforeForeign,history(order)); assertEquals(4,stock(f.product))
        assertEquals("TEMPORARY",jdbc.queryForObject("SELECT status::text FROM image WHERE id=?",String::class.java,foreignProof))
        Assertions.assertNull(jdbc.queryForObject("SELECT entity_id FROM image WHERE id=?",Long::class.java,foreignProof))
        val first=upload(f.buyer)
        ok(request("POST","/order/$order/AWAITING_PREPAYMENT_APPROVAL?imageId=$first",f.buyer))
        ok(request("POST","/order/$order/AWAITING_PAYMENT",f.seller))
        val proof=upload(f.buyer)
        ok(request("POST","/order/$order/ASSEMBLING?imageId=$proof",f.buyer))
        assertEquals("ACTIVE",jdbc.queryForObject("SELECT status::text FROM image WHERE id=?",String::class.java,proof))
        assertEquals(order,jdbc.queryForObject("SELECT entity_id FROM image WHERE id=?",Long::class.java,proof))
        ok(request("POST","/order/$order/ON_THE_WAY?deliveryUrl=https%3A%2F%2Fexample.test%2Ftracking",f.seller))
        val case=ok(request("POST","/order/orders/$order/DISPUTED?comment=delivery",f.buyer)).asLong()
        assertEquals("DISPUTED",status(order)); val before=history(order)
        val denied=request("POST","/admin/actions/order-cases/$case/resolve?outcome=SELLER&comment=ok",stranger)
        assertEquals(403,denied.statusCode()); assertEquals("",denied.body())
        val detail=ok(request("GET","/admin/actions/order-cases/$case",admin,role="ADMIN"))
        assertEquals(order,detail["orderCase"]["orderId"].asLong()); assertEquals(proof,detail["paymentProofImageId"].asLong())
        ok(request("POST","/admin/actions/order-cases/$case/resolve?outcome=SELLER&comment=ok",admin,role="ADMIN"))
        assertEquals("COMPLETED",status(order)); assertEquals(before+1,history(order)); assertEquals(4,stock(f.product))
        error(request("POST","/admin/actions/order-cases/$case/resolve?outcome=SELLER&comment=retry",admin,role="ADMIN"),409,"ORDER_CASE_ALREADY_RESOLVED")
        val review=ok(request("POST","/reviews",f.buyer,"""{"orderId":$order,"rating":5,"comment":"resolved31"}""")).asLong()
        error(request("POST","/reviews",f.buyer,"""{"orderId":$order,"rating":5}"""),400,"REVIEW_ALREADY_EXIST")
        error(request("POST","/reviews",stranger,"""{"orderId":$order,"rating":5}"""),404,"ORDER_NOT_FOUND")
        val reviews=ok(request("GET","/reviews/seller/${f.seller}",f.buyer))
        assertEquals(review,reviews.single()["id"].asLong()); assertEquals(5,reviews.single()["rating"].asInt())
        assertEquals(setOf("id","rating","comment","reviewerName","imageId","createdAt"),reviews.single().fieldNames().asSequence().toSet())
        assertEquals(1,number("SELECT total_reviews FROM seller_rating WHERE seller_id=${f.seller}"))
        ok(request("DELETE","/reviews?reviewId=$review",stranger)); assertEquals(1,number("SELECT count(*) FROM review WHERE id=$review"))
        ok(request("DELETE","/reviews?reviewId=$review",f.buyer)); ok(request("DELETE","/reviews?reviewId=$review",f.buyer))
        assertEquals(0,number("SELECT count(*) FROM seller_rating WHERE seller_id=${f.seller}")); assertEquals(before+1,history(order))
    }

    @Test fun `HTTP cancellation late resolution failure rolls back then payment appeal is idempotent`() {
        val f=offer(); val order=book(f); val admin=person("ADMIN")
        ok(request("POST","/order/$order/AWAITING_PREPAYMENT",f.seller))
        val case=ok(request("POST","/order/$order/cancellation-request?comment=cancel",f.seller)).asLong()
        val before=history(order)
        jdbc.execute("""CREATE FUNCTION fail_resolution31() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'} BEGIN
            IF NEW.id=$case AND NEW.state='RESOLVED' THEN RAISE EXCEPTION 'fixture late resolution failure' USING ERRCODE='22003'; END IF;
            RETURN NEW; END ${'$'}${'$'}""")
        jdbc.execute("CREATE TRIGGER fail_resolution31 BEFORE UPDATE ON order_case FOR EACH ROW EXECUTE FUNCTION fail_resolution31()")
        try {
            error(request("POST","/admin/actions/order-cases/$case/resolve?outcome=CANCELLED&comment=cancel",admin,role="ADMIN"),500,"INTERNAL_ERROR")
            assertEquals("AWAITING_PAYMENT",status(order)); assertEquals(3,stock(f.product)); assertEquals(before,history(order))
            assertEquals("OPEN",jdbc.queryForObject("SELECT state FROM order_case WHERE id=?",String::class.java,case))
        } finally { jdbc.execute("DROP TRIGGER fail_resolution31 ON order_case"); jdbc.execute("DROP FUNCTION fail_resolution31()") }
        ok(request("POST","/admin/actions/order-cases/$case/resolve?outcome=CANCELLED&comment=cancel",admin,role="ADMIN"))
        assertEquals("CANCELLED",status(order)); assertEquals(4,stock(f.product)); assertEquals(before+1,history(order))
        val evidence=upload(f.buyer)
        val appeal=ok(request("POST","/order/$order/payment-appeal?comment=paid&imageIds=$evidence",f.buyer)).asLong()
        assertEquals("DISPUTED",status(order))
        ok(request("POST","/admin/actions/order-cases/$appeal/resolve?outcome=BUYER&comment=refund",admin,role="ADMIN"))
        assertEquals("CANCELLED",status(order)); assertEquals(4,stock(f.product)); assertEquals(before+3,history(order))
        error(request("POST","/order/$order/payment-appeal?comment=again",f.buyer),409,"PAYMENT_APPEAL_ALREADY_EXISTS")
        assertEquals(2,number("SELECT count(*) FROM order_case WHERE order_id=$order"))
    }

    @Test fun `historical agent order uses actual admin actions audit and customer payment and review`() {
        val (f,order)=importedAgentOrder(); val admin=person("ADMIN"); val other=person("ADMIN")
        val action="/admin/actions/agents/${f.seller}/orders/$order"
        assertEquals(403,request("GET","/admin/actions/agent-orders",f.buyer).statusCode())
        error(request("POST","$action/CONFIRM",admin,role="ADMIN",type="agent_access"),403,"ACCESS_DENIED")
        ok(request("POST","$action/CONFIRM",admin,role="ADMIN"))
        val proof=upload(f.buyer)
        ok(request("POST","/order/$order/ASSEMBLING?imageId=$proof",f.buyer))
        ok(request("POST","$action/SHIP?deliveryUrl=https%3A%2F%2Fexample.test%2Fagent",other,role="ADMIN"))
        ok(request("POST","/order/$order/COMPLETED",f.buyer))
        ok(request("POST","/reviews",f.buyer,"""{"orderId":$order,"rating":4}"""))
        val queue=ok(request("GET","/admin/actions/agents/${f.seller}/orders?status=COMPLETED&page=0&size=1",admin,role="ADMIN"))
        assertEquals(order,queue.single()["orderId"].asLong()); assertEquals("COMPLETED",queue.single()["actualStatus"].asText())
        assertEquals(2,audits(order)); assertEquals(5,history(order))
        val audit=jdbc.queryForList("SELECT admin_id,agent_id,action FROM admin_agent_order_action WHERE order_id=? ORDER BY id",order)
        assertEquals(listOf(admin,other),audit.map {(it["admin_id"] as Number).toLong()})
        assertTrue(audit.all {(it["agent_id"] as Number).toLong()==f.seller}); assertEquals(listOf("CONFIRM","SHIP"),audit.map {it["action"]})
        jdbc.update("UPDATE participant SET status='BLOCKED' WHERE id=?",admin)
        error(request("GET","/admin/actions/agent-orders",admin,role="ADMIN"),403,"ACCESS_DENIED")
        assertEquals(2,audits(order)); assertEquals(5,history(order))
    }
    companion object {
        private val pg=EmbeddedPostgres.start()
        @JvmStatic @DynamicPropertySource fun database(registry: DynamicPropertyRegistry) = properties(registry,pg)
    }
}
