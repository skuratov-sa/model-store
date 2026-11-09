package com.model_store.modern.ordering.lifecycle

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
abstract class LifecycleHttpContract {
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
    fun `ordinary preorder and external full HTTP flow histories roles and repeat errors`() {
        for (availability in listOf("PURCHASABLE", "PREORDER", "EXTERNAL_PRODUCT")) {
            val f = fixture(availability, prepayment = if (availability == "PURCHASABLE") 0f else 5f)
            val outsider = participant()
            assertEquals("[]", request("GET", "/order/seller", outsider).body())
            assertEquals("[]", request("GET", "/order/customer", outsider).body())
            val planned = mutableListOf("BOOKED")
            fun step(route: String, who: Long, expected: String, proof: Long? = null, extra: String = "") {
                val before = count("SELECT count(*) FROM order_status_history WHERE order_id=${f.order}")
                error(request("POST", "/order/${f.order}/$route$extra", outsider), 400, "BAD_REQUEST", "Нельзя выполнить операцию с данными условиями")
                val result = request("POST", "/order/${f.order}/$route?comment=comment-$route${proof?.let { "&imageId=$it" } ?: ""}${if (extra.isNotEmpty()) "&deliveryUrl=" + encode("https://delivery.test/a?x=1&y=2") else ""}", who)
                assertEquals(200, result.statusCode(), result.body())
                assertEquals("${f.order}", result.body())
                assertEquals(expected, status(f.order))
                assertEquals(before + 1, count("SELECT count(*) FROM order_status_history WHERE order_id=${f.order}"))
                planned += expected
                error(request("POST", "/order/${f.order}/$route$extra", who), 400, "BAD_REQUEST", "Нельзя выполнить операцию с данными условиями")
                val view = list(f.buyer, false).single { it["orderId"].asLong() == f.order }
                assertEquals(planned, view["histories"].map { it["status"].asText() })
                assertEquals("comment-$route", view["histories"].last()["comment"].asText())
                val time = jdbc.queryForObject("SELECT max(changed_at) FROM order_status_history WHERE order_id=?", java.sql.Timestamp::class.java, f.order)!!.toInstant()
                assertEquals(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss").withZone(ZoneId.of("Europe/Moscow")).format(time), view["histories"].last()["changedAt"].asText())
            }
            step("AWAITING_PREPAYMENT", f.seller, if (availability == "PURCHASABLE") "AWAITING_PAYMENT" else "AWAITING_PREPAYMENT")
            if (availability != "PURCHASABLE") {
                val cancellation=case(f,"CANCELLATION_REQUEST","AWAITING_PREPAYMENT")
                step("AWAITING_PREPAYMENT_APPROVAL", f.buyer, "AWAITING_PREPAYMENT_APPROVAL", image(f.buyer))
                assertEquals("RESOLVED",jdbc.queryForObject("SELECT state FROM order_case WHERE id=?",String::class.java,cancellation))
                step("AWAITING_PAYMENT", f.seller, "AWAITING_PAYMENT")
            }
            val proof = image(f.buyer)
            step("ASSEMBLING", f.buyer, "ASSEMBLING", proof)
            step("ON_THE_WAY", f.seller, "ON_THE_WAY", extra = "?deliveryUrl=track")
            step("COMPLETED", f.buyer, "COMPLETED")
            val view = list(f.seller, true).single { it["orderId"].asLong() == f.order }
            assertEquals(proof, view["paymentProofImageId"].asLong())
            assertEquals("https://delivery.test/a?x=1&y=2", view["deliveryUrl"].asText())
            assertEquals(f.buyer, view["userInfo"]["id"].asLong())
            assertTrue(view["sellerLogin"].isNull)
            parity("completed-$availability",view)
        }
    }

    @Test
    fun `every ordinary route rejects wrong status and wrong party without side effects`() {
        val valid=mapOf("AWAITING_PREPAYMENT" to "BOOKED", "AWAITING_PREPAYMENT_APPROVAL" to "AWAITING_PREPAYMENT",
            "AWAITING_PAYMENT" to "AWAITING_PREPAYMENT_APPROVAL", "ASSEMBLING" to "AWAITING_PAYMENT",
            "ON_THE_WAY" to "ASSEMBLING", "COMPLETED" to "ON_THE_WAY", "CANCELLED" to "BOOKED")
        val sellerRoutes=setOf("AWAITING_PREPAYMENT","AWAITING_PAYMENT","ON_THE_WAY")
        val states=listOf("BOOKED","AWAITING_PREPAYMENT","AWAITING_PREPAYMENT_APPROVAL","AWAITING_PAYMENT","ASSEMBLING","ON_THE_WAY","COMPLETED","CANCELLED","FAILED")
        for((route,allowed) in valid) {
            val f=fixture("PREORDER",5f)
            val who=if(route in sellerRoutes) f.seller else f.buyer
            for(state in states.filter { it!=allowed }) {
                jdbc.update("UPDATE \"order\" SET status=CAST(? AS order_status) WHERE id=?",state,f.order)
                val histories=count("SELECT count(*) FROM order_status_history WHERE order_id=${f.order}")
                error(request("POST","/order/${f.order}/$route${if(route=="ON_THE_WAY") "?deliveryUrl=x" else ""}",who,
                    if(route=="CANCELLED") "{}" else null),400,"BAD_REQUEST","Нельзя выполнить операцию с данными условиями")
                assertEquals(state,status(f.order)); assertEquals(3,stock(f.product))
                assertEquals(histories,count("SELECT count(*) FROM order_status_history WHERE order_id=${f.order}"))
            }
            if(route!="CANCELLED") {
                jdbc.update("UPDATE \"order\" SET status=CAST(? AS order_status) WHERE id=?",allowed,f.order)
                error(request("POST","/order/${f.order}/$route${if(route=="ON_THE_WAY") "?deliveryUrl=x" else ""}",
                    if(who==f.buyer) f.seller else f.buyer),400,"BAD_REQUEST","Нельзя выполнить операцию с данными условиями")
            }
        }
    }

    @Test
    fun `proof missing foreign wrong tag already active and repeat failures rollback history`() {
        val f = fixture()
        ok(request("POST", "/order/${f.order}/AWAITING_PREPAYMENT", f.seller))
        val foreign = participant()
        val other = fixture()
        val invalid = listOf<Long?>(null, -1, Long.MAX_VALUE, image(foreign), image(f.buyer, tag = "PRODUCT"),
            image(f.buyer, state = "ACTIVE"), image(f.buyer, state = "DELETE"), image(f.buyer, entity = other.order))
        val before = count("SELECT count(*) FROM order_status_history WHERE order_id=${f.order}")
        invalid.forEach { proof ->
            error(request("POST", "/order/${f.order}/ASSEMBLING${proof?.let { "?imageId=$it" } ?: ""}", f.buyer),
                404, "IMAGE_NOT_FOUND", "Подтверждение оплаты не найдено")
            assertEquals("AWAITING_PAYMENT", status(f.order))
            assertEquals(before, count("SELECT count(*) FROM order_status_history WHERE order_id=${f.order}"))
        }
        val proof = image(f.buyer, entity = f.order)
        ok(request("POST", "/order/${f.order}/ASSEMBLING?imageId=$proof", f.buyer))
        error(request("POST", "/order/${f.order}/ASSEMBLING?imageId=$proof", f.buyer), 400, "BAD_REQUEST", "Нельзя выполнить операцию с данными условиями")
    }

    @Test
    fun `participant avatar timestamp ties retain actual legacy choice while product ties use ID`() {
        val f=fixture()
        fun reverseIdImages(tag: String, entity: Long): Pair<Long,Long> {
            val low=jdbc.queryForObject("SELECT nextval('image_id_seq')",Long::class.java)!!
            val high=jdbc.queryForObject("SELECT nextval('image_id_seq')",Long::class.java)!!
            for(id in listOf(high,low)) jdbc.update("""INSERT INTO image(id,filename,tag,status,entity_id,uploaded_by,created_at)
                VALUES (?,?,CAST(? AS image_tag),'ACTIVE',?,?,'2010-01-01T00:00:00Z')""",
                id,"avatar-tie-$id.jpg",tag,entity,f.seller)
            return low to high
        }
        val (lowAvatar,highAvatar)=reverseIdImages("PARTICIPANT",f.seller)
        val (lowProduct,_)=reverseIdImages("PRODUCT",f.product)
        // Execute the actual repository annotation SQL, rather than duplicate a desired sort as oracle.
        val legacySql=com.model_store.repository.ImageRepository::class.java.getMethod("findActualIdsByEntity",
            java.lang.Long::class.java,com.model_store.model.constant.ImageTag::class.java)
            .getAnnotation(org.springframework.data.r2dbc.repository.Query::class.java).value
        val actualLegacy=org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc).query(legacySql,
            mapOf("entityId" to f.seller,"tag" to "PARTICIPANT")) { rs,_ -> rs.getLong(1) }.first()
        assertEquals(highAvatar,actualLegacy,"fixture must distinguish legacy timestamp ordering from an added ID tie-break")
        assertNotEquals(lowAvatar,actualLegacy)
        val view=list(f.buyer,false).single { it["orderId"].asLong()==f.order }
        assertEquals(actualLegacy,view["userInfo"]["imageId"].asLong())
        assertEquals(lowProduct,view["product"]["imageId"].asLong())
    }

    @Test
    fun `lists snapshots enrichment nulls media deleted product and inactive counterparty`() {
        val f = fixture()
        val productImage = image(f.seller, "PRODUCT", "ACTIVE", f.product)
        val avatar = image(f.seller, "PARTICIPANT", "ACTIVE", f.seller)
        val orderImage = image(f.buyer, state = "ACTIVE", entity = f.order)
        val category = jdbc.queryForObject("INSERT INTO category(name) VALUES (?) RETURNING id", Long::class.java, "LC category ${sequence.incrementAndGet()}")!!
        jdbc.update("INSERT INTO product_category(product_id,category_id) VALUES (?,?)", f.product, category)
        jdbc.update("UPDATE product SET name='changed',price=99,external_url='https://ignored.test',used=true WHERE id=?", f.product)
        val view = list(f.buyer, false).single { it["orderId"].asLong() == f.order }
        assertEquals(setOf("orderId","actualStatus","totalPrice","prepaymentAmount","sellerId","sellerLogin","createdAt","userInfo","product","transfer","images","paymentProofImageId","deliveryUrl","histories"), view.fieldNames().asSequence().toSet())
        assertEquals("snapshot", view["product"]["name"].asText())
        assertEquals(10f, view["product"]["price"].floatValue())
        assertEquals(1, view["product"]["count"].asInt())
        assertEquals(productImage, view["product"]["imageId"].asLong())
        assertEquals(category, view["product"]["categories"][0]["id"].asLong())
        assertTrue(view["product"]["externalUrl"].isNull)
        assertEquals(0f, view["product"]["sellerRating"].floatValue())
        assertEquals(avatar, view["userInfo"]["imageId"].asLong())
        assertEquals(listOf(orderImage), view["images"].map { it.asLong() })
        assertEquals("RU г. Moscow ул. null null кв. null, индекс: null", view["transfer"]["address"].asText())
        assertEquals(2L, view["transfer"]["imageId"].asLong())
        assertEquals(jdbc.queryForObject("SELECT created_at FROM \"order\" WHERE id=?", java.sql.Timestamp::class.java, f.order)!!.toInstant().toString(), view["createdAt"].asText())
        parity("enriched",view)
        jdbc.update("UPDATE participant SET status='BLOCKED' WHERE id=?", f.seller)
        val blocked = list(f.buyer, false).single { it["orderId"].asLong() == f.order }
        assertEquals(f.seller, blocked["userInfo"]["id"].asLong())
        assertTrue(blocked["userInfo"]["login"].isNull)
        parity("inactive-counterparty",blocked)
        jdbc.update("DELETE FROM product_category WHERE product_id=?", f.product)
        jdbc.update("DELETE FROM product WHERE id=?", f.product)
        val deleted = list(f.buyer, false).single { it["orderId"].asLong() == f.order }
        assertEquals("DELETED", deleted["product"]["status"].asText())
        assertEquals("snapshot", deleted["product"]["name"].asText())
        assertTrue(deleted["product"]["categories"].isNull)
        parity("deleted-product",deleted)
    }

    @Test
    fun `cancellation stock current availability unlimited and bot policies`() {
        for (availability in listOf("PURCHASABLE", "PREORDER", "EXTERNAL_PRODUCT")) {
            val f = fixture(availability)
            ok(request("POST", "/order/${f.order}/CANCELLED", f.buyer, "{\"closureReason\":\"ignored\",\"comment\":\"cancel\"}"))
            assertEquals("CANCELLED", status(f.order))
            assertEquals(if (availability == "PURCHASABLE") 4 else 3, stock(f.product))
            error(request("POST", "/order/${f.order}/CANCELLED", f.seller, "{}"), 400, "BAD_REQUEST", "Нельзя выполнить операцию с данными условиями")
            assertEquals(if (availability == "PURCHASABLE") 4 else 3, stock(f.product))
        }
        val f = fixture()
        jdbc.update("UPDATE product SET availability='PREORDER' WHERE id=?", f.product)
        ok(request("POST", "/order/${f.order}/CANCELLED", f.seller, "{}"))
        assertEquals(3, stock(f.product))
        val becameLimited=fixture("PREORDER")
        jdbc.update("UPDATE product SET availability='PURCHASABLE',status='DELETED' WHERE id=?",becameLimited.product)
        ok(request("POST", "/order/${becameLimited.order}/CANCELLED", becameLimited.buyer, "{}"))
        assertEquals(4,stock(becameLimited.product))
        val unlimited = fixture()
        jdbc.update("UPDATE product SET count=NULL WHERE id=?", unlimited.product)
        ok(request("POST", "/order/${unlimited.order}/CANCELLED", unlimited.buyer, "{}"))
        assertNull(stock(unlimited.product))
        val agent = fixture()
        jdbc.update("UPDATE participant SET is_agent=true WHERE id=?", agent.seller)
        val botSeller = request("POST", "/order/${agent.order}/AWAITING_PREPAYMENT", agent.seller)
        if (this is OrderLifecycleLegacyHttpTest) { assertEquals(403,botSeller.statusCode()); assertEquals("",botSeller.body()) }
        else error(botSeller, 403, "ACCESS_DENIED", "Заказами бота управляет администратор")
        ok(request("POST", "/order/${agent.order}/CANCELLED", agent.buyer, "{}"))
        if (this is OrderLifecycleLegacyHttpTest) assertEquals(403,request("GET","/order/seller",agent.seller).statusCode())
        else assertEquals(1, list(agent.seller, true).size)
        val botBuyer = fixture()
        jdbc.update("UPDATE participant SET is_agent=true WHERE id=?", botBuyer.buyer)
        val botCancel=request("POST", "/order/${botBuyer.order}/CANCELLED", botBuyer.buyer, "{}")
        if (this is OrderLifecycleLegacyHttpTest) { assertEquals(403,botCancel.statusCode()); assertEquals("",botCancel.body()) }
        else error(botCancel, 403, "ACCESS_DENIED", "Заказами бота управляет администратор")
        ok(request("POST", "/order/${botBuyer.order}/AWAITING_PREPAYMENT", botBuyer.seller))
        val botPay=request("POST", "/order/${botBuyer.order}/ASSEMBLING?imageId=${image(botBuyer.buyer)}", botBuyer.buyer)
        if (this is OrderLifecycleLegacyHttpTest) { assertEquals(403,botPay.statusCode()); assertEquals("",botPay.body()) }
        else ok(botPay)
    }

    @Test
    fun `delivery URL and comment preserve empty arbitrary strings and report database length errors`() {
        val f=fixture()
        jdbc.update("UPDATE \"order\" SET status='ASSEMBLING' WHERE id=?",f.order)
        ok(request("POST","/order/${f.order}/ON_THE_WAY?deliveryUrl=&comment=",f.seller))
        assertEquals("",jdbc.queryForObject("SELECT delivery_url FROM \"order\" WHERE id=?",String::class.java,f.order))
        assertEquals("",jdbc.queryForObject("SELECT comment FROM \"order\" WHERE id=?",String::class.java,f.order))
        val g=fixture()
        jdbc.update("UPDATE \"order\" SET status='ASSEMBLING' WHERE id=?",g.order)
        ok(request("POST","/order/${g.order}/ON_THE_WAY?deliveryUrl=${encode("custom:track value")}",g.seller))
        assertEquals("custom:track value",jdbc.queryForObject("SELECT delivery_url FROM \"order\" WHERE id=?",String::class.java,g.order))
        val longComment=fixture()
        val commentResult=request("POST","/order/${longComment.order}/AWAITING_PREPAYMENT?comment=${"x".repeat(501)}",longComment.seller)
        error(commentResult,500,"INTERNAL_ERROR","Внутренняя ошибка")
        assertEquals("BOOKED",status(longComment.order)); assertEquals(1,count("SELECT count(*) FROM order_status_history WHERE order_id=${longComment.order}"))
        val longUrl=fixture()
        jdbc.update("UPDATE \"order\" SET status='ASSEMBLING' WHERE id=?",longUrl.order)
        val urlResult=request("POST","/order/${longUrl.order}/ON_THE_WAY?deliveryUrl=${"x".repeat(1001)}",longUrl.seller)
        error(urlResult,500,"INTERNAL_ERROR","Внутренняя ошибка"); assertEquals("ASSEMBLING",status(longUrl.order))
    }

    @Test
    fun `overflow cancellation HTTP baseline preserves order and stock`() {
        val f=fixture()
        jdbc.update("UPDATE product SET count=? WHERE id=?",Int.MAX_VALUE,f.product)
        val response=request("POST","/order/${f.order}/CANCELLED",f.buyer,"{}")
        error(response,500,"INTERNAL_ERROR","Внутренняя ошибка")
        assertEquals("BOOKED",status(f.order)); assertEquals(Int.MAX_VALUE,stock(f.product))
        assertEquals(1,count("SELECT count(*) FROM order_status_history WHERE order_id=${f.order}"))
    }

    @Test
    fun `payment dismisses only open cancellation and records evidence in eligible existing dispute`() {
        val f = fixture()
        ok(request("POST", "/order/${f.order}/AWAITING_PREPAYMENT", f.seller))
        val cancellation = case(f, "CANCELLATION_REQUEST", "AWAITING_PAYMENT")
        ok(request("POST", "/order/${f.order}/ASSEMBLING?imageId=${image(f.buyer)}", f.buyer))
        assertEquals("REJECTED", jdbc.queryForObject("SELECT outcome FROM order_case WHERE id=?", String::class.java, cancellation))
        assertEquals(1L, jdbc.queryForObject("SELECT version FROM order_case WHERE id=?", Long::class.java, cancellation))
        assertEquals("Получено подтверждение оплаты", jdbc.queryForObject("SELECT resolution_comment FROM order_case WHERE id=?", String::class.java, cancellation))
        val d = fixture("PREORDER", 5f)
        val previous = image(d.buyer, state = "ACTIVE", entity = d.order)
        jdbc.update("UPDATE \"order\" SET status='DISPUTED',image_payment_proof_id=? WHERE id=?", previous, d.order)
        val dispute = case(d, "DISPUTE", "AWAITING_PAYMENT")
        val proof = image(d.buyer)
        val before = count("SELECT count(*) FROM order_status_history WHERE order_id=${d.order}")
        ok(request("POST", "/order/${d.order}/ASSEMBLING?imageId=$proof&comment=ignored", d.buyer))
        assertEquals("DISPUTED", status(d.order))
        assertEquals(before, count("SELECT count(*) FROM order_status_history WHERE order_id=${d.order}"))
        assertEquals(proof, jdbc.queryForObject("SELECT image_id FROM order_case_image WHERE case_id=?", Long::class.java, dispute))
        error(request("POST", "/order/${d.order}/ASSEMBLING?imageId=$proof", d.buyer), 404, "IMAGE_NOT_FOUND", "Подтверждение оплаты не найдено")
        val another=image(d.buyer)
        ok(request("POST","/order/${d.order}/ASSEMBLING?imageId=$another",d.buyer))
        assertEquals(2,count("SELECT count(*) FROM order_case_image WHERE case_id=$dispute"))
        assertEquals(before,count("SELECT count(*) FROM order_status_history WHERE order_id=${d.order}"))
        jdbc.update("UPDATE order_case SET state='RESOLVED',outcome='REJECTED',resolved_at=now() WHERE id=?", dispute)
        val next = image(d.buyer)
        error(request("POST", "/order/${d.order}/ASSEMBLING?imageId=$next", d.buyer), 400, "BAD_REQUEST", "Операция недоступна для текущего состояния заказа")
        assertEquals("TEMPORARY", jdbc.queryForObject("SELECT status::text FROM image WHERE id=?", String::class.java, next))
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

    private fun parity(scenario: String, original: JsonNode) {
        fun canonical(node: JsonNode, field: String = ""): JsonNode {
            if(node.isNull) return node
            if(field in setOf("id","orderId","sellerId","transferId","addressId","imageId","paymentProofImageId"))
                return json.nodeFactory.textNode("<id>")
            if(field in setOf("createdAt","expirationDate","changedAt")) return json.nodeFactory.textNode("<time>")
            if(field in setOf("login","sellerLogin","mail")) return json.nodeFactory.textNode("<identity>")
            if(field=="name" && node.asText().startsWith("LC category")) return json.nodeFactory.textNode("<category>")
            if(node.isObject) return json.createObjectNode().also { result ->
                node.fields().forEachRemaining { (key,value) -> result.set<JsonNode>(key,canonical(value,key)) }
            }
            if(node.isArray) return json.createArrayNode().also { result -> node.forEach { result.add(canonical(it,if(field=="images") "id" else "")) } }
            return node
        }
        val normalized=canonical(original)
        val other=paritySnapshots.putIfAbsent(scenario,normalized)
        if(other!=null) assertEquals(other,normalized,"Full legacy/modern JSON parity: $scenario")
    }

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
class OrderLifecycleLegacyHttpTest : LifecycleHttpContract()

@ActiveProfiles("modern")
class OrderLifecycleModernHttpTest : LifecycleHttpContract() {
    @Autowired lateinit var lifecycle: com.model_store.modern.ordering.lifecycle.application.OrderLifecycle
    @Autowired lateinit var creation: com.model_store.modern.ordering.create.application.CreateOrder
    @Autowired lateinit var rows: com.model_store.modern.media.image.command.application.ImageCommandRows
    @Autowired lateinit var transactions: org.springframework.transaction.PlatformTransactionManager
    @Autowired lateinit var transactionGuard: com.model_store.modern.ordering.lifecycle.application.LifecycleTransaction
    @Autowired lateinit var caseWrites: com.model_store.modern.ordering.lifecycle.application.OrderPaymentCases

    @Test
    fun `commands require writable actual application JPA before any order side effect`() {
        val f=fixture()
        val action=com.model_store.modern.ordering.lifecycle.domain.OrderAction.CANCEL
        val target=org.springframework.test.util.AopTestUtils.getTargetObject<com.model_store.modern.ordering.lifecycle.application.OrderLifecycle>(lifecycle)
        fun refused(block: () -> Unit) {
            assertThrows(com.model_store.modern.ordering.lifecycle.application.LifecycleTransactionRequired::class.java,block)
            assertEquals("BOOKED",status(f.order)); assertEquals(3,stock(f.product))
            assertEquals(1,count("SELECT count(*) FROM order_status_history WHERE order_id=${f.order}"))
        }
        refused { target.execute(actor(f.buyer),f.order,action) }
        refused { caseWrites.dismissCancellation(f.order) }
        refused {
            org.springframework.transaction.support.TransactionTemplate(org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.dataSource!!))
                .executeWithoutResult { target.execute(actor(f.buyer),f.order,action) }
        }
        refused {
            org.springframework.transaction.support.TransactionTemplate(transactions).apply { isReadOnly=true }
                .executeWithoutResult { lifecycle.execute(actor(f.buyer),f.order,action) }
        }
        val foreign=org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean().apply {
            setDataSource(jdbc.dataSource!!)
            setPackagesToScan("com.model_store.modern.ordering.lifecycle")
            persistenceUnitName="lifecycle-audit-foreign"
            jpaVendorAdapter=org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter()
            setJpaPropertyMap(mapOf("hibernate.hbm2ddl.auto" to "none"))
            afterPropertiesSet()
        }
        try {
            refused { org.springframework.transaction.support.TransactionTemplate(org.springframework.orm.jpa.JpaTransactionManager(foreign.`object`!!))
                .executeWithoutResult { target.execute(actor(f.buyer),f.order,action) } }
        } finally { foreign.destroy() }
        transaction {
            val txid=jdbc.queryForObject("SELECT txid_current()",Long::class.java)
            transactionGuard.requireWritableJpa()
            lifecycle.execute(actor(f.seller),f.order,com.model_store.modern.ordering.lifecycle.domain.OrderAction.AGREE)
            lifecycle.execute(actor(f.buyer),f.order,com.model_store.modern.ordering.lifecycle.domain.OrderAction.PAY,imageId=image(f.buyer))
            assertEquals(txid,jdbc.queryForObject("SELECT txid_current()",Long::class.java))
        }
        assertEquals("ASSEMBLING",status(f.order))
        assertEquals(3,count("SELECT count(*) FROM order_status_history WHERE order_id=${f.order}"))
    }

    private fun awaitParticipantWait(pid: Int) {
        val deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3)
        while(System.nanoTime()<deadline) {
            // pg_stat_activity is cached per transaction; refresh the diagnostic snapshot while
            // retaining the business row lock that makes this concurrency scenario deterministic.
            jdbc.execute("SELECT pg_stat_clear_snapshot()")
            if(jdbc.queryForObject("""SELECT EXISTS(SELECT 1 FROM pg_stat_activity
                WHERE query LIKE 'SELECT id FROM participant WHERE id=%FOR UPDATE'
                AND ?=ANY(pg_blocking_pids(pid)))""",Boolean::class.java,pid)==true) return
            Thread.sleep(20)
        }
        fail<Unit>("command did not reach participant lock after its scalar observation")
    }

    @Test
    fun `disputed payment races preserve legacy case first error precedence and proof CAS`() {
        for(close in listOf(false,true)) {
            val f=fixture()
            val initial=image(f.buyer,state="ACTIVE",entity=f.order)
            jdbc.update("UPDATE \"order\" SET status='DISPUTED',image_payment_proof_id=? WHERE id=?",initial,f.order)
            val caseId=case(f,"DISPUTE","AWAITING_PAYMENT")
            val winner=image(f.buyer); val loser=image(f.buyer)
            val pool=java.util.concurrent.Executors.newSingleThreadExecutor()
            lateinit var competing: java.util.concurrent.Future<Result<Long>>
            try {
                transaction {
                    val pid=jdbc.queryForObject("SELECT pg_backend_pid()",Int::class.java)!!
                    jdbc.queryForObject("SELECT id FROM participant WHERE id=? FOR UPDATE",Long::class.java,minOf(f.buyer,f.seller))
                    competing=pool.submit<Result<Long>> { runCatching {
                        command(f,com.model_store.modern.ordering.lifecycle.domain.OrderAction.PAY,f.buyer,loser)
                    } }
                    awaitParticipantWait(pid)
                    lifecycle.execute(actor(f.buyer),f.order,com.model_store.modern.ordering.lifecycle.domain.OrderAction.PAY,imageId=winner)
                    if(close) {
                        jdbc.update("UPDATE order_case SET state='RESOLVED',outcome='REJECTED',resolved_at=now(),version=version+1 WHERE id=?",caseId)
                        jdbc.update("UPDATE \"order\" SET status='COMPLETED' WHERE id=?",f.order)
                    }
                }
                val failure=competing.get(8,java.util.concurrent.TimeUnit.SECONDS).exceptionOrNull()
                assertTrue(failure is com.model_store.modern.ordering.lifecycle.domain.LifecycleFailure,"$failure")
                assertEquals(if(close) com.model_store.modern.ordering.lifecycle.domain.LifecycleProblem.DISPUTE_PAYMENT_UNAVAILABLE
                    else com.model_store.modern.ordering.lifecycle.domain.LifecycleProblem.DISPUTE_PAYMENT_CHANGED,
                    (failure as com.model_store.modern.ordering.lifecycle.domain.LifecycleFailure).problem)
                assertEquals(winner,jdbc.queryForObject("SELECT image_payment_proof_id FROM \"order\" WHERE id=?",Long::class.java,f.order))
                assertEquals("TEMPORARY",jdbc.queryForObject("SELECT status::text FROM image WHERE id=?",String::class.java,loser))
                assertEquals(1,count("SELECT count(*) FROM order_case_image WHERE case_id=$caseId"))
                assertEquals(if(close) 3 else 2,count("SELECT count(*) FROM order_status_history WHERE order_id=${f.order}"))
            } finally { pool.shutdownNow() }
        }
    }

    @Test
    fun `normal payment that races existing dispute attaches to case without another status history`() {
        val f=fixture("PREORDER",5f)
        val prepayment=image(f.buyer,state="ACTIVE",entity=f.order)
        jdbc.update("UPDATE \"order\" SET status='AWAITING_PAYMENT',image_payment_proof_id=? WHERE id=?",prepayment,f.order)
        val proof=image(f.buyer)
        val pool=java.util.concurrent.Executors.newSingleThreadExecutor()
        lateinit var paying: java.util.concurrent.Future<Result<Long>>
        var caseId=0L
        try {
            transaction {
                val pid=jdbc.queryForObject("SELECT pg_backend_pid()",Int::class.java)!!
                jdbc.queryForObject("SELECT id FROM participant WHERE id=? FOR UPDATE",Long::class.java,minOf(f.buyer,f.seller))
                paying=pool.submit<Result<Long>> { runCatching {
                    command(f,com.model_store.modern.ordering.lifecycle.domain.OrderAction.PAY,f.buyer,proof)
                } }
                awaitParticipantWait(pid)
                caseId=case(f,"DISPUTE","AWAITING_PAYMENT")
                jdbc.update("UPDATE \"order\" SET status='DISPUTED',comment='dispute' WHERE id=?",f.order)
            }
            assertTrue(paying.get(8,java.util.concurrent.TimeUnit.SECONDS).isSuccess)
            assertEquals("DISPUTED",status(f.order))
            assertEquals("dispute",jdbc.queryForObject("SELECT comment FROM \"order\" WHERE id=?",String::class.java,f.order))
            assertEquals(proof,jdbc.queryForObject("SELECT image_id FROM order_case_image WHERE case_id=?",Long::class.java,caseId))
            assertEquals(3,count("SELECT count(*) FROM order_status_history WHERE order_id=${f.order}"))
        } finally { pool.shutdownNow() }
    }

    private fun actor(id: Long) = com.model_store.modern.shared.domain.Actor(id, "lc", "USER")
    private fun command(f: Fixture, action: com.model_store.modern.ordering.lifecycle.domain.OrderAction, who: Long,
                        proof: Long? = null) = transaction {
        lifecycle.execute(actor(who), f.order, action, "concurrent", proof)
    }
    private fun <T> transaction(block: () -> T): T = org.springframework.transaction.support.TransactionTemplate(transactions).execute {
        jdbc.execute("SET LOCAL lock_timeout='4s'")
        jdbc.execute("SET LOCAL statement_timeout='8s'")
        block()
    }!!
    private fun race(first: () -> Any?, second: () -> Any?, expectedFailures: Set<com.model_store.modern.ordering.lifecycle.domain.LifecycleProblem> = setOf(
        com.model_store.modern.ordering.lifecycle.domain.LifecycleProblem.INVALID_TRANSITION,
        com.model_store.modern.ordering.lifecycle.domain.LifecycleProblem.STATUS_CHANGED,
    )): List<Result<Any?>> {
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        val ready = java.util.concurrent.CountDownLatch(2)
        val go = java.util.concurrent.CountDownLatch(1)
        try {
            val futures = listOf(first, second).map { action -> pool.submit<Result<Any?>> {
                ready.countDown(); assertTrue(go.await(5, java.util.concurrent.TimeUnit.SECONDS))
                runCatching { action() }
            } }
            assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS)); go.countDown()
            return futures.map { it.get(12, java.util.concurrent.TimeUnit.SECONDS) }.also { outcomes ->
                // An expected losing transition must be a business refusal. A deadlock, timeout,
                // rollback infrastructure error or assertion is never an acceptable loser.
                outcomes.filter { it.isFailure }.forEach { outcome ->
                    val failure=outcome.exceptionOrNull()
                    assertTrue(failure is com.model_store.modern.ordering.lifecycle.domain.LifecycleFailure,
                        "Unexpected concurrent failure: $failure")
                    assertTrue((failure as com.model_store.modern.ordering.lifecycle.domain.LifecycleFailure).problem in expectedFailures,
                        "Unexpected concurrent business refusal: ${failure.problem}")
                }
            }
        } finally { go.countDown(); pool.shutdownNow() }
    }

    @Test
    fun `request that observed valid state before participant wait receives legacy status changed error`() {
        val f=fixture()
        val pool=java.util.concurrent.Executors.newSingleThreadExecutor()
        lateinit var losing: java.util.concurrent.Future<Result<Long>>
        try {
            transaction {
                val pid=jdbc.queryForObject("SELECT pg_backend_pid()",Int::class.java)!!
                jdbc.queryForObject("SELECT id FROM participant WHERE id=? FOR UPDATE",Long::class.java,minOf(f.buyer,f.seller))
                losing=pool.submit<Result<Long>> { runCatching {
                    command(f,com.model_store.modern.ordering.lifecycle.domain.OrderAction.AGREE,f.seller)
                } }
                awaitParticipantWait(pid)
                lifecycle.execute(actor(f.seller),f.order,com.model_store.modern.ordering.lifecycle.domain.OrderAction.AGREE)
            }
            val failure=losing.get(8,java.util.concurrent.TimeUnit.SECONDS).exceptionOrNull()
            assertTrue(failure is com.model_store.modern.ordering.lifecycle.domain.LifecycleFailure,"$failure")
            assertEquals(com.model_store.modern.ordering.lifecycle.domain.LifecycleProblem.STATUS_CHANGED,
                (failure as com.model_store.modern.ordering.lifecycle.domain.LifecycleFailure).problem)
            assertEquals(2,count("SELECT count(*) FROM order_status_history WHERE order_id=${f.order}"))
        } finally { pool.shutdownNow() }
    }

    @Test
    fun `concurrent transitions cancellation payment expiry and creation return stock once`() {
        val agree = com.model_store.modern.ordering.lifecycle.domain.OrderAction.AGREE
        val cancel = com.model_store.modern.ordering.lifecycle.domain.OrderAction.CANCEL
        val pay = com.model_store.modern.ordering.lifecycle.domain.OrderAction.PAY
        val f=fixture()
        val double = race({ command(f,cancel,f.buyer) }, { command(f,cancel,f.seller) })
        assertEquals(1,double.count { it.isSuccess }); assertEquals(4,stock(f.product))
        assertEquals(2,count("SELECT count(*) FROM order_status_history WHERE order_id=${f.order}"))
        val g=fixture()
        val mixed=race({ command(g,cancel,g.buyer) }, { command(g,agree,g.seller) })
        assertEquals(1,mixed.count { it.isSuccess })
        assertEquals(if(status(g.order)=="CANCELLED") 4 else 3,stock(g.product))
        val p=fixture(); command(p,agree,p.seller); val proof=image(p.buyer)
        val paid=race({ command(p,pay,p.buyer,proof) }, { command(p,pay,p.buyer,proof) })
        assertEquals(1,paid.count { it.isSuccess }); assertEquals("ASSEMBLING",status(p.order))
        val p2=fixture(); command(p2,agree,p2.seller)
        val paidCancel=race({ command(p2,pay,p2.buyer,image(p2.buyer)) }, { command(p2,cancel,p2.seller) })
        assertTrue(paidCancel.first().isSuccess); assertTrue(paidCancel.last().isFailure); assertEquals(3,stock(p2.product))
        repeat(4) {
            val old=fixture()
            jdbc.update("UPDATE \"order\" SET created_at=now()-interval '2 days' WHERE id=?",old.order)
            val expiry=race({ command(old,cancel,old.buyer) }, { transaction { jdbc.execute("SELECT process_expired_booked_orders()") } })
            assertTrue(expiry.last().isSuccess,expiry.toString()); assertEquals("CANCELLED",status(old.order)); assertEquals(4,stock(old.product))
            assertEquals(2,count("SELECT count(*) FROM order_status_history WHERE order_id=${old.order}"))
        }
        val c=fixture()
        jdbc.update("INSERT INTO participant_address(participant_id,address_id) VALUES (?,?)",c.buyer,c.address)
        val created=race({ command(c,cancel,c.buyer) }, { transaction {
            creation.book(actor(c.buyer),listOf(com.model_store.modern.ordering.create.domain.OrderLine(c.product,1,c.address,c.transfer,null)))
        } })
        assertTrue(created.all { it.isSuccess },created.toString()); assertEquals(3,stock(c.product))
    }

    @Test
    fun `proof competing orders NOWAIT and late rollback leave no partial writes`() {
        val agree = com.model_store.modern.ordering.lifecycle.domain.OrderAction.AGREE
        val pay = com.model_store.modern.ordering.lifecycle.domain.OrderAction.PAY
        val cancel = com.model_store.modern.ordering.lifecycle.domain.OrderAction.CANCEL
        val a=fixture(); val b=fixture()
        // Same buyer makes both claims valid authorizations, only one can activate the TEMPORARY image.
        jdbc.update("UPDATE \"order\" SET customer_id=? WHERE id=?",a.buyer,b.order)
        command(a,agree,a.seller); command(b,agree,b.seller)
        val proof=image(a.buyer)
        val results=race({ command(a,pay,a.buyer,proof) }, { command(b,pay,a.buyer,proof) },
            setOf(com.model_store.modern.ordering.lifecycle.domain.LifecycleProblem.PROOF_NOT_FOUND))
        assertEquals(1,results.count { it.isSuccess })
        assertEquals(1,count("SELECT count(*) FROM \"order\" WHERE id IN (${a.order},${b.order}) AND status='ASSEMBLING'"))
        val late=fixture(); command(late,agree,late.seller); val lateProof=image(late.buyer)
        val lateCancellation=case(late,"CANCELLATION_REQUEST","AWAITING_PAYMENT")
        assertThrows(IllegalStateException::class.java) { transaction {
            lifecycle.execute(actor(late.buyer),late.order,pay,"late",lateProof)
            throw IllegalStateException("late failure")
        } }
        assertEquals("AWAITING_PAYMENT",status(late.order))
        assertEquals("TEMPORARY",jdbc.queryForObject("SELECT status::text FROM image WHERE id=?",String::class.java,lateProof))
        assertEquals(2,count("SELECT count(*) FROM order_status_history WHERE order_id=${late.order}"))
        assertEquals("OPEN",jdbc.queryForObject("SELECT state FROM order_case WHERE id=?",String::class.java,lateCancellation))
        assertEquals(0L,jdbc.queryForObject("SELECT version FROM order_case WHERE id=?",Long::class.java,lateCancellation))
        val stockRollback=fixture()
        assertThrows(IllegalStateException::class.java) { transaction {
            lifecycle.execute(actor(stockRollback.buyer),stockRollback.order,cancel,"late")
            throw IllegalStateException("late failure")
        } }
        assertEquals("BOOKED",status(stockRollback.order)); assertEquals(3,stock(stockRollback.product))
        assertEquals(1,count("SELECT count(*) FROM order_status_history WHERE order_id=${stockRollback.order}"))

        val pool=java.util.concurrent.Executors.newSingleThreadExecutor()
        val locked=java.util.concurrent.CountDownLatch(1); val release=java.util.concurrent.CountDownLatch(1)
        try {
            val locker=pool.submit { transaction {
                jdbc.queryForObject("SELECT id FROM image WHERE id=? FOR UPDATE",Long::class.java,lateProof)
                locked.countDown(); assertTrue(release.await(5,java.util.concurrent.TimeUnit.SECONDS))
            } }
            assertTrue(locked.await(5,java.util.concurrent.TimeUnit.SECONDS))
            error(request("POST","/order/${late.order}/ASSEMBLING?imageId=$lateProof",late.buyer),409,"IMAGE_BUSY","Изображение занято. Повторите операцию")
            assertEquals("AWAITING_PAYMENT",status(late.order))
            assertEquals(2,count("SELECT count(*) FROM order_status_history WHERE order_id=${late.order}"))
            release.countDown(); locker.get(8,java.util.concurrent.TimeUnit.SECONDS)
        } finally { release.countDown(); pool.shutdownNow() }
        ok(request("POST","/order/${late.order}/ASSEMBLING?imageId=$lateProof",late.buyer))
    }

    @Test
    fun `invalid and resolved disputes reject payment and case attachment late failure rolls back proof`() {
        val pay=com.model_store.modern.ordering.lifecycle.domain.OrderAction.PAY
        for ((kind,previous,hasProof) in listOf(Triple("DISPUTE","ON_THE_WAY",true),Triple("PAYMENT_APPEAL","AWAITING_PAYMENT",true),Triple("DISPUTE","AWAITING_PAYMENT",false))) {
            val f=fixture(); val initial=if(hasProof) image(f.buyer,state="ACTIVE",entity=f.order) else null
            jdbc.update("UPDATE \"order\" SET status='DISPUTED',image_payment_proof_id=? WHERE id=?",initial,f.order)
            case(f,kind,previous)
            val proof=image(f.buyer)
            error(request("POST","/order/${f.order}/ASSEMBLING?imageId=$proof",f.buyer),400,"BAD_REQUEST","Операция недоступна для текущего состояния заказа")
            assertEquals("TEMPORARY",jdbc.queryForObject("SELECT status::text FROM image WHERE id=?",String::class.java,proof))
        }
        val f=fixture(); val initial=image(f.buyer,state="ACTIVE",entity=f.order)
        jdbc.update("UPDATE \"order\" SET status='DISPUTED',image_payment_proof_id=? WHERE id=?",initial,f.order)
        val case=case(f,"DISPUTE","AWAITING_PAYMENT"); val proof=image(f.buyer)
        // Unique image relation fails after activation, reproducing a late case write error.
        jdbc.update("INSERT INTO order_case_image(case_id,image_id,uploaded_by) VALUES (?,?,?)",case,proof,f.buyer)
        assertThrows(org.springframework.dao.DataIntegrityViolationException::class.java) { command(f,pay,f.buyer,proof) }
        assertEquals(initial,jdbc.queryForObject("SELECT image_payment_proof_id FROM \"order\" WHERE id=?",Long::class.java,f.order))
        assertEquals("TEMPORARY",jdbc.queryForObject("SELECT status::text FROM image WHERE id=?",String::class.java,proof))
        jdbc.update("DELETE FROM order_case_image WHERE case_id=?",case)
        val race=race({ command(f,pay,f.buyer,proof) }, { transaction {
            jdbc.queryForObject("SELECT id FROM \"order\" WHERE id=? FOR UPDATE",Long::class.java,f.order)
            jdbc.update("UPDATE order_case SET state='RESOLVED',outcome='REJECTED',resolved_at=now(),version=version+1 WHERE id=?",case)
        } }, setOf(com.model_store.modern.ordering.lifecycle.domain.LifecycleProblem.DISPUTE_PAYMENT_UNAVAILABLE))
        assertTrue(race.last().isSuccess,race.toString())
        assertEquals(if(race.first().isSuccess) "ACTIVE" else "TEMPORARY",jdbc.queryForObject("SELECT status::text FROM image WHERE id=?",String::class.java,proof))
    }

    @Test
    fun `ORDER upload delete interoperate while lifecycle holds participant order locks`() {
        val f=fixture(); val pool=java.util.concurrent.Executors.newSingleThreadExecutor()
        val started=java.util.concurrent.CountDownLatch(1)
        lateinit var uploading: java.util.concurrent.Future<List<Long>>
        try {
            transaction {
                jdbc.queryForObject("SELECT id FROM participant WHERE id=? FOR UPDATE",Long::class.java,minOf(f.buyer,f.seller))
                jdbc.queryForObject("SELECT id FROM participant WHERE id=? FOR UPDATE",Long::class.java,maxOf(f.buyer,f.seller))
                jdbc.queryForObject("SELECT id FROM \"order\" WHERE id=? FOR UPDATE",Long::class.java,f.order)
                lifecycle.execute(actor(f.seller),f.order,com.model_store.modern.ordering.lifecycle.domain.OrderAction.AGREE)
                uploading=pool.submit<List<Long>> {
                    assertThrows(com.model_store.modern.media.image.command.domain.ImageCommandDenied::class.java) {
                        rows.markDeleted(listOf(1),com.model_store.modern.media.storage.domain.StorageLocation.ORDER,f.buyer)
                    }
                    started.countDown()
                    rows.insertBatch(com.model_store.modern.media.image.command.domain.UploadTarget(com.model_store.modern.media.storage.domain.StorageLocation.ORDER,f.order,f.buyer),
                        listOf(com.model_store.modern.media.image.command.domain.ImageToInsert("lifecycle-upload.jpg",10,10)))
                }
                assertTrue(started.await(5,java.util.concurrent.TimeUnit.SECONDS))
                assertThrows(java.util.concurrent.TimeoutException::class.java) { uploading.get(150,java.util.concurrent.TimeUnit.MILLISECONDS) }
            }
            val proof=uploading.get(8,java.util.concurrent.TimeUnit.SECONDS).single()
            ok(request("POST","/order/${f.order}/ASSEMBLING?imageId=$proof",f.buyer))
        } finally { pool.shutdownNow() }
    }

    @Test
    fun `security rejects agent tokens and permits ADMIN with unchanged party checks`() {
        val f=fixture()
        assertEquals(403,request("GET","/order/customer",f.buyer,type="agent_access").statusCode())
        assertEquals(403,request("POST","/order/${f.order}/AWAITING_PREPAYMENT",f.seller,type="agent_access").statusCode())
        ok(request("POST","/order/${f.order}/AWAITING_PREPAYMENT",f.seller,role="ADMIN"))
        error(request("POST","/order/${f.order}/ASSEMBLING",f.seller,role="ADMIN"),400,"BAD_REQUEST","Нельзя выполнить операцию с данными условиями")
        val missing=request("POST","/order/${f.order}/ON_THE_WAY",f.seller)
        error(missing,400,"BAD_REQUEST","Required query parameter 'deliveryUrl' is not present.")
    }

    @Test
    fun `stock overflow rolls back cancellation and history and missing product is a no op`() {
        val f=fixture()
        jdbc.update("UPDATE product SET count=? WHERE id=?",Int.MAX_VALUE,f.product)
        val failure=assertThrows(com.model_store.modern.ordering.lifecycle.domain.LifecycleFailure::class.java) {
            command(f,com.model_store.modern.ordering.lifecycle.domain.OrderAction.CANCEL,f.buyer)
        }
        assertEquals(com.model_store.modern.ordering.lifecycle.domain.LifecycleProblem.STOCK_CONFLICT,failure.problem)
        assertEquals("BOOKED",status(f.order)); assertEquals(Int.MAX_VALUE,stock(f.product))
        assertEquals(1,count("SELECT count(*) FROM order_status_history WHERE order_id=${f.order}"))
        jdbc.update("DELETE FROM product WHERE id=?",f.product)
        command(f,com.model_store.modern.ordering.lifecycle.domain.OrderAction.CANCEL,f.buyer)
        assertEquals("CANCELLED",status(f.order))
    }

    @Test
    fun `media upload delete serialize below ordering participant order product locks in both actor ID directions`() {
        for(adminFirst in listOf(false,true)) for(delete in listOf(false,true)) {
            val earlierAdmin=if(adminFirst) participant("ADMIN") else null
            val f=fixture()
            val admin=earlierAdmin ?: participant("ADMIN")
            jdbc.update("UPDATE participant SET is_agent=true WHERE id=?",f.seller)
            jdbc.update("UPDATE \"order\" SET customer_id=? WHERE id=?",admin,f.order)
            val existing=if(delete) image(f.seller,"PRODUCT","ACTIVE",f.product) else null
            val pool=java.util.concurrent.Executors.newSingleThreadExecutor()
            val workerPid=java.util.concurrent.ArrayBlockingQueue<Int>(1)
            lateinit var worker: java.util.concurrent.Future<List<Long>>
            try {
                transaction {
                    val holder=jdbc.queryForObject("SELECT pg_backend_pid()",Int::class.java)!!
                    val low=minOf(admin,f.seller); val high=maxOf(admin,f.seller)
                    jdbc.queryForObject("SELECT id FROM participant WHERE id=? FOR UPDATE",Long::class.java,low)
                    worker=pool.submit<List<Long>> { transaction {
                        workerPid.put(jdbc.queryForObject("SELECT pg_backend_pid()",Int::class.java)!!)
                        if(delete) {
                            rows.markDeleted(listOf(requireNotNull(existing)),com.model_store.modern.media.storage.domain.StorageLocation.PRODUCT,admin)
                            emptyList()
                        } else rows.insertBatch(com.model_store.modern.media.image.command.domain.UploadTarget(
                            com.model_store.modern.media.storage.domain.StorageLocation.PRODUCT,f.product,admin),
                            listOf(com.model_store.modern.media.image.command.domain.ImageToInsert("media-serialized-${sequence.incrementAndGet()}.jpg",10,10)))
                    } }
                    val waiting=workerPid.poll(5,java.util.concurrent.TimeUnit.SECONDS)
                    assertNotNull(waiting,"media worker must have its own JPA connection")
                    assertNotEquals(holder,waiting,"lock probes must use a different backend from media")
                    awaitSpecificMediaWait(requireNotNull(waiting),holder,worker)
                    // This must succeed while the worker is blocked on the lower participant.
                    // Old actor-first media takes high admin first and fails this probe in the reverse direction.
                    assertEquals(high,jdbc.queryForObject("SELECT id FROM participant WHERE id=? FOR UPDATE NOWAIT",Long::class.java,high))
                    // Exercise the actual ordering path through participants -> order -> catalog product.
                    lifecycle.execute(com.model_store.modern.shared.domain.Actor(admin,"admin","ADMIN"),f.order,
                        com.model_store.modern.ordering.lifecycle.domain.OrderAction.CANCEL,"serialized")
                    assertFalse(worker.isDone,"media must still wait until ordering commits")
                }
                val uploaded=worker.get(12,java.util.concurrent.TimeUnit.SECONDS)
                assertEquals("CANCELLED",status(f.order)); assertEquals(4,stock(f.product))
                assertEquals(2,count("SELECT count(*) FROM order_status_history WHERE order_id=${f.order}"))
                if(delete) {
                    assertTrue(uploaded.isEmpty())
                    assertEquals("DELETE",jdbc.queryForObject("SELECT status::text FROM image WHERE id=?",String::class.java,existing))
                } else {
                    val id=uploaded.single()
                    assertEquals("TEMPORARY",jdbc.queryForObject("SELECT status::text FROM image WHERE id=?",String::class.java,id))
                    assertEquals(admin,jdbc.queryForObject("SELECT uploaded_by FROM image WHERE id=?",Long::class.java,id))
                    assertEquals(f.product,jdbc.queryForObject("SELECT entity_id FROM image WHERE id=?",Long::class.java,id))
                }
            } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(12,java.util.concurrent.TimeUnit.SECONDS)) }
        }
    }

    @Test
    fun `media waiting for product does not own images and completes deletion after product unlock`() {
        val f=fixture()
        val admin=participant("ADMIN")
        jdbc.update("UPDATE participant SET is_agent=true WHERE id=?",f.seller)
        val first=image(f.seller,"PRODUCT","ACTIVE",f.product)
        val second=image(f.seller,"PRODUCT","ACTIVE",f.product)
        val pool=java.util.concurrent.Executors.newSingleThreadExecutor()
        val workerPid=java.util.concurrent.ArrayBlockingQueue<Int>(1)
        lateinit var worker: java.util.concurrent.Future<Unit>
        try {
            transaction {
                val holder=jdbc.queryForObject("SELECT pg_backend_pid()",Int::class.java)!!
                jdbc.queryForObject("SELECT id FROM product WHERE id=? FOR UPDATE",Long::class.java,f.product)
                worker=pool.submit<Unit> { transaction {
                    workerPid.put(jdbc.queryForObject("SELECT pg_backend_pid()",Int::class.java)!!)
                    rows.markDeleted(listOf(second,first),com.model_store.modern.media.storage.domain.StorageLocation.PRODUCT,admin)
                } }
                val waiting=workerPid.poll(5,java.util.concurrent.TimeUnit.SECONDS)
                assertNotNull(waiting)
                assertNotEquals(holder,waiting,"image probe must not be reentrant on media's connection")
                awaitSpecificMediaWait(requireNotNull(waiting),holder,worker)
                // The only business row held by this connection before waiting was product, so the
                // observed worker -> holder dependency proves it reached that product lock.
                for(id in listOf(first,second)) assertEquals(id,jdbc.queryForObject(
                    "SELECT id FROM image WHERE id=? FOR UPDATE NOWAIT",Long::class.java,id))
                assertFalse(worker.isDone)
            }
            worker.get(12,java.util.concurrent.TimeUnit.SECONDS)
            assertEquals(2,count("SELECT count(*) FROM image WHERE id IN ($first,$second) AND status='DELETE'"))
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(12,java.util.concurrent.TimeUnit.SECONDS)) }
    }

    private fun awaitSpecificMediaWait(workerPid: Int, holderPid: Int, worker: java.util.concurrent.Future<*>) {
        val deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3)
        while(System.nanoTime()<deadline) {
            assertFalse(worker.isDone,"media must reach the held row rather than finish/fail before the probe")
            jdbc.execute("SELECT pg_stat_clear_snapshot()")
            if(jdbc.queryForObject("SELECT ?=ANY(pg_blocking_pids(?))",Boolean::class.java,holderPid,workerPid)==true) return
            Thread.sleep(20)
        }
        fail<Unit>("specific media backend did not block on the holder")
    }
}
