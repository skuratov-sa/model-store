package com.model_store.modern.seller.agent.product

import com.amazonaws.services.s3.AmazonS3
import com.fasterxml.jackson.databind.ObjectMapper
import io.jsonwebtoken.Jwts
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.net.URI
import java.net.http.*
import java.security.KeyFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Duration
import java.time.Instant
import java.util.*

/** The same requests run against full real WebFlux/R2DBC and MVC/JPA applications. */
abstract class AgentProductHttpContract {
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var json: ObjectMapper
    @MockitoBean lateinit var s3: AmazonS3
    @MockitoBean lateinit var mail: JavaMailSender
    @Value("\${local.server.port}") private var port: Int = 0
    protected abstract val modern: Boolean
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()

    @BeforeEach fun clearWindows() { jdbc.update("UPDATE product SET giveaway_enabled=false") }

    protected fun person(role: String = "USER", status: String = "ACTIVE", agent: Boolean = false): Long {
        val login = "agent29-${UUID.randomUUID()}"
        return jdbc.queryForObject("""INSERT INTO participant(login,mail,password,role,status,is_agent)
            VALUES (?,?,'test-hash',?::participant_role,?::participant_status,?) RETURNING id""",
            Long::class.java, login, "$login@example.test", role, status, agent)!!
    }

    protected fun product(owner: Long, status: String = "ACTIVE", availability: String = "EXTERNAL_PRODUCT"): Long =
        jdbc.queryForObject("""INSERT INTO product(name,price,currency,participant_id,status,expiration_date,
            availability,external_url) VALUES (?,10,'RUB',?,?::product_status,now()+interval '1 day',
            ?::product_availability,'https://example.test/product') RETURNING id""", Long::class.java,
            "item-${UUID.randomUUID()}", owner, status, availability)!!

    protected fun image(owner: Long, tag: String = "PRODUCT", status: String = "TEMPORARY", entity: Long? = null): Long =
        jdbc.queryForObject("""INSERT INTO image(filename,tag,status,uploaded_by,entity_id)
            VALUES (?,?::image_tag,?::image_status,?,?) RETURNING id""", Long::class.java,
            "${UUID.randomUUID()}.jpg", tag, status, owner, entity)!!

    protected fun call(method: String, path: String, token: String?, body: String? = null): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).timeout(Duration.ofSeconds(15))
        if (token != null) request.header("Authorization", "Bearer $token")
        if (body != null) request.header("Content-Type", "application/json")
        return client.send(request.method(method, body?.let(HttpRequest.BodyPublishers::ofString)
            ?: HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString())
    }

    protected fun error(response: HttpResponse<String>, status: Int, code: String? = null) {
        assertEquals(status, response.statusCode(), response.body())
        if (code != null) assertEquals(code, json.readTree(response.body())["code"].asText(), response.body())
    }

    protected fun root(agent: Long, id: Long) = "/admin/actions/agents/$agent/products/$id"
    protected fun createBody(name: String = "item-${UUID.randomUUID()}") =
        """{"name":"$name","price":10,"currency":"RUB","externalUrl":"https://example.test/item"}"""
    protected fun create(agent: Long): Long {
        val response = call("POST", "/agent/products", token(agent, agent = true), createBody())
        assertEquals(200, response.statusCode(), response.body())
        return response.body().toLong()
    }

    @Test fun `agent creates external active product without seller prerequisites`() {
        val agent = person(agent = true)
        val img = image(agent)
        val response = call("POST", "/agent/products", token(agent, agent = true),
            createBody().dropLast(1) + """, "imageIds":[$img], "availability":"GIVEAWAY","count":-2,"participantId":1,"status":"BLOCKED"}""")
        assertEquals(200, response.statusCode(), response.body())
        val id = response.body().toLong()
        val p = jdbc.queryForMap("SELECT * FROM product WHERE id=?", id)
        assertEquals(agent, (p["participant_id"] as Number).toLong())
        assertEquals("EXTERNAL_PRODUCT", p["availability"].toString())
        assertEquals("ACTIVE", p["status"].toString())
        assertEquals(false, p["used"])
        assertNull(p["count"]); assertNull(p["prepayment_amount"])
        assertEquals(id, jdbc.queryForObject("SELECT entity_id FROM image WHERE id=?", Long::class.java, img))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM transfer WHERE participant_id=?", Int::class.java, agent))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM social_network WHERE participant_id=?", Int::class.java, agent))
        // Legacy accepts any nonblank external URL, including a non-HTTP value and a negative price.
        val loose = call("POST", "/agent/products", token(agent, agent = true),
            """{"externalUrl":"opaque:external","price":-1,"currency":"RUB"}""")
        assertEquals(200, loose.statusCode(), loose.body())
    }

    @Test fun `creation preserves missing required column duplicate and URL errors`() {
        val agent = person(agent = true); val access = token(agent, agent = true)
        listOf("{}", """{"externalUrl":" "}""").forEach {
            val response=call("POST", "/agent/products", access, it)
            error(response, 400, "INVALID_REQUEST")
            assertEquals("Укажите ссылку на внешний товар",json.readTree(response.body())["message"].asText())
        }
        listOf("""{"externalUrl":"x","currency":"RUB"}""", """{"externalUrl":"x","price":1}""").forEach {
            error(call("POST", "/agent/products", access, it), 409, "DUPLICATE_KEY")
        }
        val body = createBody()
        assertEquals(200, call("POST", "/agent/products", access, body).statusCode())
        error(call("POST", "/agent/products", access, body), 409, "PRODUCT_ALREADY_EXISTS")
        error(call("POST", "/agent/products", access, "null"), 400, "BAD_REQUEST")
        error(call("POST", "/agent/products", access, """{"currency":"BAD"}"""), 400, "BAD_REQUEST")
        error(call("POST", "/agent/products", access), 400, "BAD_REQUEST")
        // CREATE is in Java's enum but not PostgreSQL's currency type. Modern safely translates it.
        error(call("POST", "/agent/products", access, """{"externalUrl":"x","price":1,"currency":"CREATE"}"""),
            if(modern) 409 else 500, if(modern) "DUPLICATE_KEY" else "INTERNAL_ERROR")
    }

    @Test fun `external URL follows Java legacy blank semantics for nonbreaking spaces`() {
        val owner=person(agent=true);val access=token(owner,agent=true);val admin=token(person("ADMIN"),"ADMIN")
        listOf("\u00a0","\u2007","\u202f").forEach { url ->
            val body=json.writeValueAsString(mapOf("externalUrl" to url,"price" to 1,"currency" to "RUB"))
            val created=call("POST","/agent/products",access,body)
            assertEquals(200,created.statusCode(),created.body())
            val id=created.body().toLong()
            assertEquals(200,call("PUT",root(owner,id),admin,body).statusCode())
            assertEquals(url,jdbc.queryForObject("SELECT external_url FROM product WHERE id=?",String::class.java,id))
        }
    }

    @Test fun `administrative reads expose raw product JSON all statuses and exact plural route`() {
        val admin = token(person("ADMIN"), "ADMIN"); val agent = person(agent = true)
        val ids = listOf("ACTIVE", "TIME_EXPIRED", "BLOCKED", "DELETED", "AWAITING_GIVEAWAY").map { product(agent, it) }
        val list = call("GET", "/admin/actions/agents/$agent/products", admin)
        assertEquals(200, list.statusCode(), list.body())
        assertEquals(ids.toSet(), json.readTree(list.body()).map { it["id"].asLong() }.toSet())
        val response = call("GET", "/admin/actions/products/${ids[3]}", admin)
        assertEquals(200, response.statusCode(), response.body())
        val raw = json.readTree(response.body())
        assertEquals(productFields, raw.fieldNames().asSequence().toSet())
        assertEquals("DELETED", raw["status"].asText()); assertTrue(raw["count"].isNull)
        val userProduct = product(person())
        assertEquals(200, call("GET", "/admin/actions/products/$userProduct", admin).statusCode())
        error(call("GET", "/admin/actions/products/9223372036854775807", admin), 404, "PRODUCT_NOT_FOUND")
        val empty = person(agent = true)
        assertEquals("[]", call("GET", "/admin/actions/agents/$empty/products", admin).body())
        error(call("GET", "/admin/actions/agents/${person()}/products", admin), 404, "PARTICIPANT_NOT_FOUND")
        error(call("GET", "/admin/actions/products/no-id", admin), 400, "BAD_REQUEST")
        // Singular status command is the existing task 16 route, not an alias for plural GET.
        assertEquals(200, call("PUT", "/admin/actions/product/${ids[0]}?productStatus=BLOCKED", admin).statusCode())
        assertEquals("BLOCKED", jdbc.queryForObject("SELECT status::text FROM product WHERE id=?", String::class.java, ids[0]))
    }

    @Test fun `admin edits and extends only the target agents eligible products`() {
        val admin = token(person("ADMIN"), "ADMIN"); val agent = person(agent = true); val other = person(agent = true)
        val id = product(agent); val path = root(agent, id)
        val updated = call("PUT", path, admin,
            """{"name":"changed","description":"text","price":12,"currency":"USD","used":true,
                "originality":"custom","externalUrl":"opaque:new","count":-1,"prepaymentAmount":2,
                "participantId":$other,"id":9,"status":"DELETED","categoryIds":[]}""")
        assertEquals(200, updated.statusCode(), updated.body()); assertEquals("", updated.body())
        val raw = json.readTree(call("GET", "/admin/actions/products/$id", admin).body())
        assertEquals("changed", raw["name"].asText()); assertEquals("USD", raw["currency"].asText())
        assertEquals(agent, raw["participantId"].asLong()); assertEquals("ACTIVE", raw["status"].asText())
        assertTrue(raw["count"].isNull); assertEquals(2f, raw["prepaymentAmount"].floatValue())
        listOf("PURCHASABLE", "PREORDER").forEach {
            error(call("PUT", path, admin, """{"availability":"$it"}"""), 400, "INVALID_REQUEST")
        }
        error(call("PUT", path, admin, """{"externalUrl":" "}"""), 400, "INVALID_REQUEST")
        error(call("PUT", path, admin, """{"prepaymentAmount":13}"""), 400, "INVALID_REQUEST")
        error(call("PUT", root(other,id), admin, "{}"), 404, "PRODUCT_NOT_FOUND")
        error(call("POST", root(other,id)+"/extend", admin), 404, "PRODUCT_NOT_FOUND")
        listOf("ACTIVE", "TIME_EXPIRED").forEach { status ->
            jdbc.update("UPDATE product SET status=?::product_status, expiration_date=now()-interval '1 day' WHERE id=?", status,id)
            val extended = call("POST", "$path/extend", admin)
            assertEquals(200, extended.statusCode(), extended.body()); assertEquals("", extended.body())
            assertEquals("ACTIVE", jdbc.queryForObject("SELECT status::text FROM product WHERE id=?", String::class.java, id))
            assertTrue(jdbc.queryForObject("SELECT expiration_date > now() FROM product WHERE id=?", Boolean::class.java,id)!!)
        }
        listOf("BLOCKED", "AWAITING_GIVEAWAY", "DELETED").forEach { status ->
            jdbc.update("UPDATE product SET status=?::product_status WHERE id=?",status,id)
            error(call("POST", "$path/extend", admin),404,"PRODUCT_NOT_FOUND")
        }
        error(call("PUT", path,admin,"{}"),404,"PRODUCT_NOT_FOUND")
    }

    @Test fun `blocked and deleted target agents keep administrative editing access`() {
        val admin = token(person("ADMIN"), "ADMIN")
        listOf("BLOCKED", "DELETED").forEach { status ->
            val agent = person(status = status, agent = true); val id = product(agent)
            assertEquals(200, call("GET", "/admin/actions/agents/$agent/products", admin).statusCode())
            assertEquals(200, call("PUT", root(agent,id), admin, """{"name":"allowed-$status"}""").statusCode())
            assertEquals(200, call("POST", root(agent,id)+"/extend",admin).statusCode())
        }
    }

    @Test fun `historical agent products retain legacy stock and partial patch rules`() {
        val admin=token(person("ADMIN"),"ADMIN");val owner=person(agent=true)
        val purchasable=product(owner,availability="PURCHASABLE")
        val result=call("PUT",root(owner,purchasable),admin,"""{"count":-3,"prepaymentAmount":99}""")
        assertEquals(200,result.statusCode(),result.body())
        assertEquals(-3,jdbc.queryForObject("SELECT count FROM product WHERE id=?",Int::class.java,purchasable))
        assertNull(jdbc.queryForObject("SELECT prepayment_amount FROM product WHERE id=?",Float::class.java,purchasable))
        val preorder=product(owner,availability="PREORDER")
        assertEquals(200,call("PUT",root(owner,preorder),admin,"""{"count":5,"prepaymentAmount":0}""").statusCode())
        assertNull(jdbc.queryForObject("SELECT count FROM product WHERE id=?",Int::class.java,preorder))
    }

    @Test fun `creation storage limits and missing category references preserve safe errors`() {
        val agent=person(agent=true);val access=token(agent,agent=true)
        val body=createBody()
        val longName=json.writeValueAsString(mapOf("name" to "n".repeat(201),"price" to 1,"currency" to "RUB","externalUrl" to "x"))
        val tooLong=call("POST","/agent/products",access,longName)
        error(tooLong,if(modern)409 else 500,if(modern)"DUPLICATE_KEY" else "INTERNAL_ERROR")
        error(call("POST","/agent/products",access,body.dropLast(1)+""", "categoryIds":[9223372036854775807]}"""),400,"INVALID_REFERENCE")
        error(call("POST","/agent/products",access,"""{"externalUrl":"x","price":1,"currency":null}"""),409,"DUPLICATE_KEY")
    }

    protected fun schedule(start: String = "2090-01-01T00:00:00+03:00", end: String = "2090-01-02T00:00:00+03:00",
        enabled: Boolean = true) = """{"availability":"GIVEAWAY","giveaway":{"enabled":$enabled,
        "telegramUrl":" https://t.me/agent ","startAt":"$start","endAt":"$end","winnersCount":2,
        "rules":"Rules","homeText":"Home"}}"""

    @Test fun `giveaway schedule transitions overlap adjacency timezone and external return`() {
        val admin = token(person("ADMIN"),"ADMIN"); val agent = person(agent = true)
        val first=product(agent); val second=product(agent)
        error(call("PUT",root(agent,first),admin,"""{"availability":"GIVEAWAY"}"""),400,"INVALID_REQUEST")
        val enter=call("PUT",root(agent,first),admin,schedule())
        assertEquals(200,enter.statusCode(),enter.body())
        val stored=json.readTree(call("GET","/admin/actions/products/$first",admin).body())
        assertEquals("AWAITING_GIVEAWAY",stored["status"].asText())
        assertEquals(Instant.parse("2089-12-31T21:00:00Z"),Instant.parse(stored["giveawayStartAt"].asText()))
        assertEquals("https://t.me/agent",stored["giveawayTelegramUrl"].asText())
        error(call("POST",root(agent,first)+"/extend",admin),404,"PRODUCT_NOT_FOUND")
        error(call("PUT",root(agent,second),admin,schedule()),400,"GIVEAWAY_WINDOW_OVERLAP")
        val adjacent=call("PUT",root(agent,second),admin,schedule("2090-01-02T00:00:00+03:00","2090-01-03T00:00:00+03:00"))
        assertEquals(200,adjacent.statusCode(),adjacent.body())
        assertEquals(200,call("PUT",root(agent,first),admin,"""{"giveaway":{"enabled":false}}""").statusCode())
        assertEquals("TIME_EXPIRED",jdbc.queryForObject("SELECT status::text FROM product WHERE id=?",String::class.java,first))
        // Legacy ignores giveaway settings on the transition back to an ordinary subtype.
        val leave=call("PUT",root(agent,first),admin,"""{"availability":"EXTERNAL_PRODUCT","price":20,"giveaway":{"enabled":true}}""")
        assertEquals(200,leave.statusCode(),leave.body())
        assertFalse(jdbc.queryForObject("SELECT giveaway_enabled FROM product WHERE id=?",Boolean::class.java,first)!!)
        assertEquals("ACTIVE",jdbc.queryForObject("SELECT status::text FROM product WHERE id=?",String::class.java,first))
    }

    @Test fun `giveaway rejects invalid rules dates links blocked activation and ordinary settings`() {
        val admin=token(person("ADMIN"),"ADMIN");val agent=person(agent=true);val id=product(agent)
        val valid=schedule()
        listOf(valid.replace("https://t.me/agent","http://t.me/agent"),valid.replace("https://t.me/agent","https://user@t.me/agent"),
            valid.replace("\"winnersCount\":2","\"winnersCount\":0"),valid.replace("\"rules\":\"Rules\"","\"rules\":\" \""),
            schedule("2090-01-02T00:00:00Z","2090-01-01T00:00:00Z"),
            schedule("2000-01-01T00:00:00Z","2000-01-02T00:00:00Z")).forEach {
            error(call("PUT",root(agent,id),admin,it),400,"INVALID_REQUEST")
        }
        error(call("PUT",root(agent,id),admin,"""{"giveaway":{"enabled":false}}"""),400,"INVALID_REQUEST")
        jdbc.update("UPDATE product SET status='BLOCKED' WHERE id=?",id)
        error(call("PUT",root(agent,id),admin,valid),400,"INVALID_REQUEST")
        val disabled=call("PUT",root(agent,id),admin,schedule(enabled=false))
        assertEquals(200,disabled.statusCode(),disabled.body())
        assertEquals("BLOCKED",jdbc.queryForObject("SELECT status::text FROM product WHERE id=?",String::class.java,id))
    }

    @Test fun `unfinished order prevents agent product conversion without changing its fields`() {
        val admin=token(person("ADMIN"),"ADMIN");val owner=person(agent=true);val buyer=person();val id=product(owner)
        val address=jdbc.queryForObject("INSERT INTO address(city) VALUES ('Test') RETURNING id",Long::class.java)!!
        val transfer=jdbc.queryForObject("""INSERT INTO transfer(sending,price,currency,participant_id)
            VALUES ('PRODUCT_PICKUP',0,'RUB',?) RETURNING id""",Long::class.java,owner)!!
        jdbc.update("""INSERT INTO "order"(seller_id,customer_id,count,status,product_id,address_id,transfer_id,
            product_name,product_unit_price,product_currency,product_availability)
            VALUES (?,?,1,'BOOKED',?,?,?,'Ordered29',10,'RUB','EXTERNAL_PRODUCT')""",owner,buyer,id,address,transfer)
        val response=call("PUT",root(owner,id),admin,schedule())
        error(response,400,"INVALID_REQUEST")
        assertEquals("Товар с незавершёнными заказами нельзя перевести в розыгрыш",json.readTree(response.body())["message"].asText())
        assertEquals("EXTERNAL_PRODUCT",jdbc.queryForObject("SELECT availability::text FROM product WHERE id=?",String::class.java,id))
    }

    @Test fun `accepted media port closes legacy foreign uploader and partial batch claims`() {
        val owner=person(agent=true);val foreign=person(agent=true);val access=token(owner,agent=true)
        val otherImage=image(foreign)
        val forbidden=call("POST","/agent/products",access,createBody().dropLast(1)+""", "imageIds":[$otherImage]}""")
        error(forbidden,if(modern)403 else 200,if(modern)"ACCESS_DENIED" else null)
        val mine=image(owner)
        val partial=call("POST","/agent/products",access,createBody().dropLast(1)+""", "imageIds":[$mine,9223372036854775807]}""")
        error(partial,if(modern)404 else 200,if(modern)"IMAGE_NOT_FOUND" else null)
        if(modern) {
            assertEquals("TEMPORARY",jdbc.queryForObject("SELECT status::text FROM image WHERE id=?",String::class.java,mine))
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM product WHERE participant_id=?",Int::class.java,owner))
        }
    }

    @Test fun `directory includes exactly scalar agent summaries in ascending order including inactive`() {
        // This DB belongs solely to this contract class.
        jdbc.update("UPDATE participant SET is_agent=false")
        val admin=token(person("ADMIN"),"ADMIN")
        val empty=call("GET","/admin/actions/agents",admin)
        assertEquals(200,empty.statusCode(),empty.body());assertEquals("[]",empty.body())
        val agents=listOf("ACTIVE","BLOCKED","DELETED").map { person(status=it,agent=true) }
        person()
        val response=call("GET","/admin/actions/agents",admin)
        assertEquals(200,response.statusCode(),response.body())
        val rows=json.readTree(response.body())
        assertEquals(agents,rows.map { it["id"].asLong() })
        rows.forEach { assertEquals(setOf("id","login","status"),it.fieldNames().asSequence().toSet()) }
        assertEquals(listOf("ACTIVE","BLOCKED","DELETED"),rows.map { it["status"].asText() })
    }

    @Test fun `agent creation verifies JWT type issuer signature and expiry before writing`() {
        val owner=person(agent=true)
        listOf("refresh","verify","agent_refresh").forEach { type ->
            error(call("POST","/agent/products",token(owner,agent=true,type=type),createBody()),
                if(modern || type=="refresh")401 else 403)
        }
        listOf(null,"user").forEach { issuer ->
            error(call("POST","/agent/products",token(owner,agent=true,issuer=issuer),createBody()),if(modern)401 else 403)
        }
        error(call("POST","/agent/products",token(owner,agent=true,seconds=-120),createBody()),401)
        val parts=token(owner,agent=true).split('.')
        val forged=parts.take(2).joinToString(".")+"."+(if(parts[2][0]=='A')"B" else "A")+parts[2].substring(1)
        error(call("POST","/agent/products",forged,createBody()),401)
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM product WHERE participant_id=?",Int::class.java,owner))
    }

    @Test fun `accepted giveaway policy Unicode normalization differences stay explicit for migration32`() {
        val owner=person(agent=true);val admin=token(person("ADMIN"),"ADMIN")
        val wrapped=product(owner)
        val wrappedUrl=schedule().replace("https://t.me/agent","\u00a0https://t.me/agent\u00a0")
        error(call("PUT",root(owner,wrapped),admin,wrappedUrl),if(modern)200 else 400,
            if(modern)null else "INVALID_REQUEST")
        val blankRules=product(owner)
        val rules=schedule("2092-01-01T00:00:00Z","2092-01-02T00:00:00Z")
            .replace("\"rules\":\"Rules\"","\"rules\":\"\u00a0\"")
        error(call("PUT",root(owner,blankRules),admin,rules),if(modern)400 else 200,
            if(modern)"INVALID_REQUEST" else null)
    }

    @Test fun `all six routes enforce normal admin versus agent access and token integrity`() {
        val admin=person("ADMIN");val user=person();val agent=person(agent=true);val id=product(agent)
        val routes=listOf(Triple("GET","/admin/actions/agents",null),
            Triple("GET","/admin/actions/agents/$agent/products",null),Triple("GET","/admin/actions/products/$id",null),
            Triple("PUT",root(agent,id),"{}"),Triple("POST",root(agent,id)+"/extend",null))
        routes.forEach { (method,path,body) ->
            error(call(method,path,null,body),401)
            error(call(method,path,token(user),body),403)
            error(call(method,path,token(agent,agent=true),body),403)
            listOf("refresh","verify","agent_refresh").forEach { type ->
                error(call(method,path,token(admin,"ADMIN",type=type),body),if(!modern && type!="refresh") 200 else 401)
            }
            error(call(method,path,token(admin,"ADMIN",seconds=-120),body),401)
            val good=token(admin,"ADMIN");val parts=good.split('.')
            val bad=parts.take(2).joinToString(".")+"."+(if(parts[2][0]=='A') "B" else "A")+parts[2].substring(1)
            error(call(method,path,bad,body),401)
            error(call(method,path,token(agent,"ADMIN",agent=true),body),403)
        }
        listOf(token(user),token(admin,"ADMIN"),token(agent)).forEach { error(call("POST","/agent/products",it,createBody()),403) }
        error(call("POST","/agent/products",null,createBody()),401)
        listOf("BLOCKED","DELETED").forEach { status ->
            val inactive=person(status=status,agent=true)
            error(call("POST","/agent/products",token(inactive,agent=true),createBody()),403)
        }
        // A correctly signed claim with no current is_agent flag cannot create an agent-owned product.
        error(call("POST","/agent/products",token(user,agent=true),createBody()),403)
        if (modern) {
            routes.forEach { (method,path,body) -> error(call(method,path,token(user,"ADMIN"),body),403,"ACCESS_DENIED") }
            jdbc.update("UPDATE participant SET role='USER' WHERE id=?",admin)
            error(call("PUT",root(agent,id),token(admin,"ADMIN"),"{}"),403,"ACCESS_DENIED")
            jdbc.update("UPDATE participant SET role='ADMIN' WHERE id=?",admin)
            listOf("status='BLOCKED'","status='DELETED'","status='ACTIVE',is_agent=true").forEach { change ->
                jdbc.update("UPDATE participant SET $change WHERE id=?",admin)
                routes.forEach { (method,path,body) ->error(call(method,path,token(admin,"ADMIN"),body),403,"ACCESS_DENIED") }
            }
        }
    }

    companion object {
        val productFields=setOf("id","name","description","price","prepaymentAmount","count","currency","originality",
            "participantId","status","expirationDate","availability","used","externalUrl","giveawayEnabled",
            "giveawayTelegramUrl","giveawayStartAt","giveawayEndAt","giveawayWinnersCount","giveawayRules","giveawayHomeText","createdAt")
        private val privateKey by lazy {
            val pem=ClassPathResource("keys/test_private_key.pem").inputStream.bufferedReader().use { it.readText() }
                .replace("-----BEGIN PRIVATE KEY-----","").replace("-----END PRIVATE KEY-----","").replace(Regex("\\s+"),"")
            KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)))
        }
        fun token(id:Long,role:String="USER",agent:Boolean=false,type:String=if(agent) "agent_access" else "access",seconds:Long=3600,
            issuer:String?="admin"):String =
            Jwts.builder().subject("agent29").claim("login","agent29").claim("id",id).claim("role",role).claim("type",type)
                .apply { if(agent && issuer!=null) claim("issuedBy",issuer) }.expiration(Date.from(Instant.now().plusSeconds(seconds))).signWith(privateKey).compact()
        fun database(registry:DynamicPropertyRegistry,pg:EmbeddedPostgres) {
            registry.add("spring.datasource.url") { "jdbc:postgresql://localhost:${pg.port}/postgres" }
            registry.add("spring.datasource.username") { "postgres" };registry.add("spring.datasource.password") { "" }
            registry.add("spring.flyway.url") { "jdbc:postgresql://localhost:${pg.port}/postgres" }
            registry.add("spring.flyway.user") { "postgres" };registry.add("spring.flyway.password") { "" }
            registry.add("spring.r2dbc.url") { "r2dbc:postgresql://localhost:${pg.port}/postgres" }
            registry.add("spring.r2dbc.username") { "postgres" };registry.add("spring.r2dbc.password") { "" }
        }
    }
}
