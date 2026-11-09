package com.model_store.modern.reputation

import com.amazonaws.services.s3.AmazonS3
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
import java.net.http.*
import java.security.KeyFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Duration
import java.time.Instant
import java.util.*
import java.util.concurrent.atomic.AtomicLong

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["app.public-key-path=keys/test_public_key.pem", "app.private-key-path=keys/test_private_key.pem"])
abstract class ReviewFixture {
    @Value("\${local.server.port}") var port: Int = 0
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var json: ObjectMapper
    @MockitoBean lateinit var s3: AmazonS3
    @MockitoBean lateinit var mail: JavaMailSender
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
    @AfterEach fun closeClient() { client.shutdownNow(); assertTrue(client.awaitTermination(Duration.ofSeconds(3))) }

    data class Fixture(val buyer: Long, val seller: Long, val product: Long, val order: Long)
    fun participant(role: String = "USER"): Long {
        val name = "review${sequence.incrementAndGet()}"
        return jdbc.queryForObject("""INSERT INTO participant(login,mail,password,full_name,status,role)
            VALUES (?,?,'hash','Review Author','ACTIVE',CAST(? AS participant_role)) RETURNING id""",
            Long::class.java,name,"$name@example.test",role)!!
    }
    fun fixture(seller: Long = participant(), buyer: Long = participant(), state: String = "COMPLETED"): Fixture {
        val address=jdbc.queryForObject("INSERT INTO address(country,city) VALUES ('RU','Moscow') RETURNING id",Long::class.java)!!
        val transfer=jdbc.queryForObject("INSERT INTO transfer(sending,price,currency,participant_id) VALUES ('PRODUCT_PICKUP',0,'RUB',?) RETURNING id",Long::class.java,seller)!!
        val product = jdbc.queryForObject("""INSERT INTO product(name,price,currency,participant_id,status,availability,count,used)
            VALUES (?,10,'RUB',?,'ACTIVE','PURCHASABLE',3,false) RETURNING id""", Long::class.java,"Reviewed ${sequence.incrementAndGet()}",seller)!!
        val order = jdbc.queryForObject("""INSERT INTO "order"(seller_id,customer_id,count,status,product_id,address_id,transfer_id,total_price,
            product_name,product_unit_price,product_currency,product_availability)
            VALUES (?,?,1,CAST(? AS order_status),?,?,?,10,'snapshot',10,'RUB','PURCHASABLE') RETURNING id""",
            Long::class.java,seller,buyer,state,product,address,transfer)!!
        return Fixture(buyer,seller,product,order)
    }
    fun request(method: String, path: String, actor: Long? = null, body: String? = null, role: String = "USER", type: String = "access"): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).timeout(Duration.ofSeconds(20))
        if(actor != null) builder.header("Authorization","Bearer ${token(actor,role,type)}")
        if(body != null) builder.header("Content-Type","application/json")
        return client.send(builder.method(method,body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString())
    }
    fun create(f: Fixture, rating: Int = 4, comment: String? = null) = request("POST","/reviews",f.buyer,
        json.writeValueAsString(mapOf("orderId" to f.order,"rating" to rating,"comment" to comment)))
    fun ok(response: HttpResponse<String>) { assertEquals(200,response.statusCode(),response.body()) }
    fun error(response: HttpResponse<String>, status: Int, code: String, message: String) {
        assertEquals(status,response.statusCode(),response.body())
        val body=json.readTree(response.body())
        assertEquals(setOf("code","message","status","timestamp","details"),body.fieldNames().asSequence().toSet())
        assertEquals(code,body["code"].asText());assertEquals(message,body["message"].asText());assertEquals(status,body["status"].asInt())
        assertTrue(body["details"].isNull); assertDoesNotThrow { java.time.OffsetDateTime.parse(body["timestamp"].asText()) }
    }
    fun count(sql: String) = jdbc.queryForObject(sql,Int::class.java)!!
    fun assertRating(seller: Long, average: String?, total: Int) {
        val rows = jdbc.queryForList("SELECT average_rating,total_reviews FROM seller_rating WHERE seller_id=?",seller)
        if(average == null) assertTrue(rows.isEmpty()) else {
            assertEquals(java.math.BigDecimal(average),rows.single()["average_rating"])
            assertEquals(total,rows.single()["total_reviews"])
        }
    }
    companion object {
        val postgres = EmbeddedPostgres.start()
        val sequence = AtomicLong()
        private val key by lazy {
            val pem = ClassPathResource("keys/test_private_key.pem").inputStream.bufferedReader().use { it.readText() }
                .replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "").replace(Regex("\\s+"), "")
            KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)))
        }
        fun token(id: Long,role: String = "USER",type: String = "access") = Jwts.builder().claim("id",id).claim("login","review")
            .claim("role",role).claim("type",type).apply { if(type=="agent_access") claim("issuedBy","admin") }
            .expiration(Date.from(Instant.now().plusSeconds(3600))).signWith(key).compact()
        @JvmStatic @DynamicPropertySource fun database(registry: DynamicPropertyRegistry) {
            val url="jdbc:postgresql://localhost:${postgres.port}/postgres"
            registry.add("spring.datasource.url") { url };registry.add("spring.datasource.username") { "postgres" };registry.add("spring.datasource.password") { "" }
            registry.add("spring.flyway.url") { url };registry.add("spring.flyway.user") { "postgres" };registry.add("spring.flyway.password") { "" }
            registry.add("spring.r2dbc.url") { "r2dbc:postgresql://localhost:${postgres.port}/postgres" };registry.add("spring.r2dbc.username") { "postgres" };registry.add("spring.r2dbc.password") { "" }
        }
    }
}

abstract class ReviewHttpContract : ReviewFixture() {
    @Test fun `create list rounding own delete and empty rating follow actual legacy`() {
        val seller=participant()
        val fixtures=(1..3).map { fixture(seller) }
        val ids=fixtures.mapIndexed { i,f -> val result=create(f,if(i==0) 1 else 5,if(i==0) "" else null);ok(result);result.body().toLong() }
        assertRating(seller,"3.67",3)
        // No paging in this legacy route: extra query parameters do not truncate the list.
        val response=request("GET","/reviews/seller/$seller?size=1&page=99",fixtures[0].buyer);ok(response)
        val reviews=json.readTree(response.body())
        assertEquals(3,reviews.size());assertEquals(ids.reversed(),reviews.map { it["id"].asLong() })
        reviews.forEach { row ->
            assertEquals(setOf("id","rating","comment","reviewerName","imageId","createdAt"),row.fieldNames().asSequence().toSet())
            assertEquals("Review Author",row["reviewerName"].asText());assertTrue(row["imageId"].isNull)
            assertDoesNotThrow { Instant.parse(row["createdAt"].asText()) }
        }
        assertEquals("",reviews.last()["comment"].asText());assertTrue(reviews.first()["comment"].isNull)
        ids.forEachIndexed { i,id -> ok(request("DELETE","/reviews?reviewId=$id",fixtures[i].buyer)) }
        assertRating(seller,null,0)
        val empty=request("GET","/reviews/seller/$seller",fixtures[0].buyer);ok(empty);assertEquals("[]",empty.body())
        ok(request("DELETE","/reviews?reviewId=${ids[0]}",fixtures[0].buyer))
    }

    @Test fun `eligibility duplicate precedence and foreign delete preserve errors`() {
        val f=fixture();val admin=participant("ADMIN")
        val body="""{"orderId":${f.order},"rating":5}"""
        error(request("POST","/reviews",admin,body,"ADMIN"),404,"ORDER_NOT_FOUND","Нет данных о заказе товара")
        error(request("POST","/reviews",f.buyer,"""{"orderId":-1,"rating":5}"""),404,"ORDER_NOT_FOUND","Нет данных о заказе товара")
        for(state in listOf("BOOKED","AWAITING_PREPAYMENT","AWAITING_PREPAYMENT_APPROVAL","AWAITING_PAYMENT","ASSEMBLING","ON_THE_WAY","DISPUTED","CANCELLED")) {
            jdbc.update("UPDATE \"order\" SET status=CAST(? AS order_status) WHERE id=?",state,f.order)
            error(create(f),400,"ORDER_NOT_TERMINATED","Нельзя оставить отзыв пока заказ еще активен")
        }
        jdbc.update("UPDATE \"order\" SET status='FAILED' WHERE id=?",f.order)
        val created=create(f);ok(created);val id=created.body().toLong()
        ok(request("DELETE","/reviews?reviewId=$id",admin,role="ADMIN"));assertRating(f.seller,"4.00",1)
        jdbc.update("UPDATE \"order\" SET status='BOOKED' WHERE id=?",f.order)
        error(create(f),400,"REVIEW_ALREADY_EXIST","Вы уже оставили отзыв для этого заказа")
        ok(request("DELETE","/reviews?reviewId=$id",f.buyer));assertRating(f.seller,null,0)
    }

    @Test fun `validation nulls length and oldest active avatar`() {
        val f=fixture()
        for(body in listOf("{}","""{"orderId":null,"rating":4}""","""{"orderId":${f.order},"rating":null}""","""{"orderId":${f.order},"rating":0}""","""{"orderId":${f.order},"rating":6}""")) {
            val result=request("POST","/reviews",f.buyer,body);assertEquals(400,result.statusCode(),result.body())
            val error=json.readTree(result.body());assertEquals("VALIDATION_ERROR",error["code"].asText());assertEquals("Ошибка валидации",error["message"].asText())
            assertTrue(error["details"]["errors"].size()>0)
        }
        error(create(f,comment="x".repeat(501)),500,"INTERNAL_ERROR","Внутренняя ошибка")
        assertRating(f.seller,null,0)
        val ids=listOf("ACTIVE","DELETE","ACTIVE").mapIndexed { i,state -> jdbc.queryForObject("""INSERT INTO image(filename,tag,status,entity_id,created_at)
            VALUES ('review.jpg','PARTICIPANT',CAST(? AS image_status),?,now()+ (? * interval '1 minute')) RETURNING id""",Long::class.java,state,f.buyer,i)!! }
        ok(create(f,comment="x".repeat(500)))
        val list=request("GET","/reviews/seller/${f.seller}",f.buyer);ok(list)
        assertEquals(ids.first(),json.readTree(list.body()).single()["imageId"].asLong())
        jdbc.update("UPDATE participant SET full_name=NULL WHERE id=?",f.buyer)
        val unnamed=request("GET","/reviews/seller/${f.seller}",f.buyer)
        ok(unnamed);assertEquals("[]",unnamed.body())
    }

    @Test fun `guest refresh role admin and detached product behavior`() {
        val f=fixture(buyer=participant("ADMIN"))
        assertEquals(401,request("GET","/reviews/seller/${f.seller}").statusCode())
        assertEquals(401,request("POST","/reviews",f.buyer,"{}","ADMIN","refresh").statusCode())
        jdbc.update("DELETE FROM product WHERE id=?",f.product)
        val result=request("POST","/reviews",f.buyer,"""{"orderId":${f.order},"rating":5}""","ADMIN");ok(result)
        assertRating(f.seller,"5.00",1)
        ok(request("DELETE","/reviews?reviewId=${result.body()}",f.buyer,role="ADMIN"))
    }

    @Test fun `actual legacy agent filter and arbitrary signed role preserve route access`() {
        val f=fixture()
        ok(request("GET","/reviews/seller/${f.seller}",f.buyer,role="OTHER"))
        ok(request("GET","/reviews/seller/${f.seller}",f.buyer,type="agent_access"))
        jdbc.update("UPDATE participant SET status='BLOCKED' WHERE id=?",f.buyer)
        ok(create(f)) // No new ACTIVE policy for an ordinary previously issued token.
        jdbc.update("UPDATE participant SET is_agent=true WHERE id=?",f.buyer)
        for((method,path,body) in listOf(Triple("GET","/reviews/seller/${f.seller}",null),Triple("POST","/reviews","{}"),Triple("DELETE","/reviews?reviewId=-1",null))) {
            val result=request(method,path,f.buyer,body)
            assertEquals(403,result.statusCode(),result.body());assertEquals("",result.body())
        }
    }

    @Test fun `nullable stored rating reads as zero while aggregate counts the row`() {
        val f=fixture();val result=create(f);ok(result)
        jdbc.update("UPDATE review SET rating=NULL WHERE id=?",result.body().toLong())
        assertRating(f.seller,"0.00",1)
        val list=request("GET","/reviews/seller/${f.seller}",f.buyer);ok(list)
        assertEquals(0,json.readTree(list.body()).single()["rating"].asInt())
        ok(request("DELETE","/reviews?reviewId=${result.body()}",f.buyer));assertRating(f.seller,null,0)
    }
}

@ActiveProfiles("legacy")
class ReviewLegacyHttpTest : ReviewHttpContract()
@ActiveProfiles("modern")
class ReviewModernHttpTest : ReviewHttpContract()
