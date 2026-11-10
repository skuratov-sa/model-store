package com.model_store.modern.ordering.`case`.admin

import com.model_store.modern.ordering.`case`.admin.application.*
import com.model_store.modern.ordering.`case`.admin.domain.*
import com.model_store.modern.ordering.`case`.user.UserCaseHttpContract
import com.model_store.modern.shared.domain.Actor
import com.model_store.modern.shared.domain.TokenType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.net.http.HttpResponse
import java.util.concurrent.*

/** Reuses accepted26's isolated DB, RSA, mocks and fixture helpers. Its six HTTP
 * regression tests are inherited deliberately and counted separately in the report. */
abstract class AdminCaseHttpContract : UserCaseHttpContract() {
    fun adminRequest(method: String, path: String, admin: Long, type: String = "access") =
        request(method, "/admin/actions/order-cases$path", admin, role = "ADMIN", type = type)
    fun admin() = participant("ADMIN")
    fun openCase(kind: String = "DISPUTE", previous: String = "ON_THE_WAY"): Pair<Fixture, Long> {
        val f = fixture()
        jdbc.update("UPDATE \"order\" SET status=CAST(? AS order_status) WHERE id=?",
            if (kind == "CANCELLATION_REQUEST") previous else "DISPUTED", f.order)
        return f to case(f, kind, previous)
    }
    fun resolve(id: Long, admin: Long, outcome: String, comment: String = " reason ") =
        adminRequest("POST", "/$id/resolve?outcome=$outcome&comment=${encode(comment)}", admin)

    @Test
    fun `admin queue pagination details raw Telegram URL and full response shape`() {
        val a = admin()
        val (f, id) = openCase()
        val proof = image(f.buyer, state = "ACTIVE", entity = f.order)
        val foreign = image(participant(), state = "ACTIVE", entity = fixture().order)
        val evidence = image(f.seller, state = "ACTIVE", entity = f.order)
        val deleted = image(f.seller, state = "DELETE", entity = f.order)
        image(f.seller, state = "TEMPORARY", entity = f.order)
        image(f.seller, tag = "PRODUCT", state = "ACTIVE", entity = f.order)
        jdbc.update("UPDATE \"order\" SET image_payment_proof_id=? WHERE id=?", proof, f.order)
        jdbc.update("INSERT INTO order_case_image(case_id,image_id,uploaded_by) VALUES (?,?,?)", id, evidence, f.seller)
        jdbc.update("INSERT INTO order_case_image(case_id,image_id,uploaded_by) VALUES (?,?,?)", id, deleted, f.seller)
        // A later case with exactly the same timestamp proves the ID tie-break.
        val (_, next) = openCase("CANCELLATION_REQUEST", "AWAITING_PAYMENT")
        jdbc.update("UPDATE order_case SET created_at='2090-01-01' WHERE id IN (?,?)", id, next)
        val page = adminRequest("GET", "?limit=1&offset=1", a); ok(page)
        val summary = json.readTree(page.body()).single()
        assertEquals(id, summary["caseId"].asLong())
        assertEquals(setOf("caseId","orderId","kind","state","openedBy","openingComment","createdAt","orderStatus"), summary.fieldNames().asSequence().toSet())
        val url = "HTTPS://t.me:443/discussion?x=y#anchor"
        val updated = adminRequest("PUT", "/$id/telegram-url?url=${encode(url)}", a); ok(updated)
        assertEquals(id, updated.body().toLong())
        val response = adminRequest("GET", "/$id", a); ok(response)
        val detail = json.readTree(response.body())
        assertEquals(setOf("orderCase","orderStatus","buyerId","sellerId","productId","count","totalPrice","prepaymentAmount","currency","paymentProofImageId","orderImages","caseImages"), detail.fieldNames().asSequence().toSet())
        assertEquals(url, detail["orderCase"]["telegramUrl"].asText())
        assertEquals(1, detail["orderCase"]["version"].asInt())
        assertTrue(detail["orderCase"]["resolvedBy"].isNull)
        assertEquals(listOf(proof, evidence), detail["orderImages"].map { it.asLong() })
        assertEquals(listOf(evidence,deleted), detail["caseImages"].map { it.asLong() })
        assertFalse(detail["orderImages"].any { it.asLong() == foreign })
        parity("admin-detail", detail)
        val resolved = resolve(id, a, "SELLER"); ok(resolved)
        val closed = adminRequest("GET", "?state=RESOLVED&limit=100", a); ok(closed)
        assertTrue(json.readTree(closed.body()).any { it["caseId"].asLong() == id })
        ok(adminRequest("GET", "?offset=9223372036854775807", a))
    }

    @Test
    fun `all case kinds and outcomes preserve status history stock raw decision and replay refusal`() {
        val a = admin()
        for (kind in listOf("DISPUTE", "PAYMENT_APPEAL", "CANCELLATION_REQUEST"))
            for (previous in listOf("ON_THE_WAY", "AWAITING_PAYMENT"))
                for (outcome in CaseOutcome.entries) {
                    val (f, id) = openCase(kind, previous)
                    val before = histories(f.order); val quantity = stock(f.product)
                    val allowed = when (kind) {
                        "DISPUTE" -> outcome == CaseOutcome.BUYER || outcome == CaseOutcome.SELLER && previous == "ON_THE_WAY"
                        "PAYMENT_APPEAL" -> outcome in setOf(CaseOutcome.BUYER, CaseOutcome.SELLER)
                        else -> outcome in setOf(CaseOutcome.CANCELLED, CaseOutcome.REJECTED)
                    }
                    val response = resolve(id, a, outcome.name)
                    if (!allowed) {
                        error(response,400,"ORDER_CASE_INVALID_OUTCOME","Недопустимый исход обращения")
                        assertEquals(before,histories(f.order)); assertEquals(quantity,stock(f.product))
                        assertEquals("OPEN",jdbc.queryForObject("SELECT state FROM order_case WHERE id=?",String::class.java,id))
                        continue
                    }
                    ok(response); assertEquals(id,response.body().toLong())
                    val expected = when {
                        outcome == CaseOutcome.REJECTED -> previous
                        kind != "DISPUTE" -> "CANCELLED"
                        outcome == CaseOutcome.BUYER -> "FAILED"
                        else -> "COMPLETED"
                    }
                    assertEquals(expected,status(f.order))
                    assertEquals(before + if(outcome == CaseOutcome.REJECTED) 0 else 1,histories(f.order))
                    assertEquals(quantity!! + if(kind == "CANCELLATION_REQUEST" && outcome == CaseOutcome.CANCELLED) 1 else 0,stock(f.product))
                    val detail=json.readTree(adminRequest("GET","/$id",a).body())
                    val c=detail["orderCase"]
                    assertEquals("RESOLVED",c["state"].asText()); assertEquals(a,c["resolvedBy"].asLong())
                    assertEquals(outcome.name,c["outcome"].asText()); assertEquals(" reason ",c["resolutionComment"].asText())
                    assertEquals(1,c["version"].asInt()); assertFalse(c["resolvedAt"].isNull)
                    parity("admin-$kind-$previous-$outcome",detail)
                    error(resolve(id,a,outcome.name),409,"ORDER_CASE_ALREADY_RESOLVED","Обращение уже завершено")
                    error(adminRequest("PUT","/$id/telegram-url?url=https://t.me/x",a),409,"ORDER_CASE_ALREADY_RESOLVED","Обращение уже завершено")
                    assertEquals(before + if(outcome == CaseOutcome.REJECTED) 0 else 1,histories(f.order))
                }
    }

    @Test
    fun `invalid states IDs URLs comments pagination and JWT roles leave cases unchanged`() {
        val a = admin(); val (f,id)=openCase()
        for(url in listOf("http://t.me/x","https://T.ME/x","https://t.me/","https://u@t.me/x","https://evil.test/x","https://t.me/"+"x".repeat(2048)))
            error(adminRequest("PUT","/$id/telegram-url?url=${encode(url)}",a),400,"BAD_REQUEST","Укажите ссылку https://t.me/...")
        for(comment in listOf(""," ","\u2003"))
            error(resolve(id,a,"BUYER",comment),400,"BAD_REQUEST","Укажите причину обращения")
        for(query in listOf("limit=0","limit=101","offset=-1"))
            error(adminRequest("GET","?$query",a),400,"BAD_REQUEST","Некорректные параметры списка обращений")
        for(path in listOf("/-123","/-123/resolve?outcome=BUYER&comment=x","/-123/telegram-url?url=https://t.me/x"))
            error(adminRequest(if("resolve" in path) "POST" else if("url" in path) "PUT" else "GET",path,a),400,"BAD_REQUEST",invalid)
        val refresh=adminRequest("POST","/$id/resolve",a,"refresh")
        assertEquals(401,refresh.statusCode(),refresh.body())
        assertEquals(403,request("GET","/admin/actions/order-cases/$id",f.buyer).statusCode())
        jdbc.update("UPDATE \"order\" SET status='COMPLETED' WHERE id=?",f.order)
        error(resolve(id,a,"BUYER"),400,"ORDER_CASE_INVALID_TRANSITION",invalid)
        assertEquals("OPEN",jdbc.queryForObject("SELECT state FROM order_case WHERE id=?",String::class.java,id))
    }

    @Test
    fun `cancellation stock follows catalog limited availability and rejection ignores changed order status`() {
        val a=admin()
        for(mode in listOf("PREORDER","UNLIMITED","MISSING","BLOCKED","DELETED")) {
            val (f,id)=openCase("CANCELLATION_REQUEST","AWAITING_PAYMENT")
            when(mode) {
                "PREORDER" -> jdbc.update("UPDATE product SET availability='PREORDER' WHERE id=?",f.product)
                "UNLIMITED" -> jdbc.update("UPDATE product SET count=NULL WHERE id=?",f.product)
                "MISSING" -> jdbc.update("DELETE FROM product WHERE id=?",f.product)
                else -> jdbc.update("UPDATE product SET status=CAST(? AS product_status) WHERE id=?",mode,f.product)
            }
            val before=histories(f.order)
            ok(resolve(id,a,"CANCELLED"))
            assertEquals(before+1,histories(f.order))
            when(mode) {
                "MISSING" -> assertEquals(0,count("SELECT count(*) FROM product WHERE id=${f.product}"))
                "UNLIMITED" -> assertNull(stock(f.product))
                "PREORDER" -> assertEquals(3,stock(f.product))
                else -> assertEquals(4,stock(f.product))
            }
        }
        val (f,id)=openCase("CANCELLATION_REQUEST","AWAITING_PAYMENT")
        jdbc.update("UPDATE \"order\" SET status='ASSEMBLING' WHERE id=?",f.order)
        val before=histories(f.order)
        error(resolve(id,a,"CANCELLED"),400,"ORDER_CASE_INVALID_TRANSITION",invalid)
        ok(resolve(id,a,"REJECTED"))
        assertEquals("ASSEMBLING",status(f.order)); assertEquals(before,histories(f.order)); assertEquals(3,stock(f.product))
    }

    @Test
    fun `stock integer overflow reports legacy internal error and rolls back entire cancellation`() {
        val a=admin(); val (f,id)=openCase("CANCELLATION_REQUEST","AWAITING_PAYMENT")
        jdbc.update("UPDATE product SET count=2147483647 WHERE id=?",f.product)
        val before=histories(f.order)
        error(resolve(id,a,"CANCELLED"),500,"INTERNAL_ERROR","Внутренняя ошибка")
        assertEquals("AWAITING_PAYMENT",status(f.order)); assertEquals(before,histories(f.order)); assertEquals(Int.MAX_VALUE,stock(f.product))
        assertEquals("OPEN",jdbc.queryForObject("SELECT state FROM order_case WHERE id=?",String::class.java,id))
        assertEquals(0,jdbc.queryForObject("SELECT version FROM order_case WHERE id=?",Int::class.java,id))
    }

    @Test
    fun `overlong transition comment keeps legacy internal error while rejected case accepts TEXT comment`() {
        val a=admin(); val longComment="x".repeat(501)
        for(kind in listOf("DISPUTE","PAYMENT_APPEAL","CANCELLATION_REQUEST")) {
            val (f,id)=openCase(kind,"AWAITING_PAYMENT")
            val before=histories(f.order); val quantity=stock(f.product); val previous=status(f.order)
            error(resolve(id,a,if(kind=="CANCELLATION_REQUEST") "CANCELLED" else "BUYER",longComment),500,"INTERNAL_ERROR","Внутренняя ошибка")
            assertEquals(previous,status(f.order)); assertEquals(before,histories(f.order)); assertEquals(quantity,stock(f.product))
            assertEquals("OPEN",jdbc.queryForObject("SELECT state FROM order_case WHERE id=?",String::class.java,id))
            assertEquals(0,jdbc.queryForObject("SELECT version FROM order_case WHERE id=?",Int::class.java,id))
        }
        val (f,id)=openCase("CANCELLATION_REQUEST","AWAITING_PAYMENT"); val before=histories(f.order)
        ok(resolve(id,a,"REJECTED",longComment))
        assertEquals(longComment,jdbc.queryForObject("SELECT resolution_comment FROM order_case WHERE id=?",String::class.java,id))
        assertEquals(before,histories(f.order)); assertEquals(3,stock(f.product))
    }

    @Test
    fun `persisted nonpositive order quantity cancels without stock restoration as legacy`() {
        val a=admin()
        for(quantity in listOf(0,-1)) {
            val (f,id)=openCase("CANCELLATION_REQUEST","AWAITING_PAYMENT")
            jdbc.update("UPDATE \"order\" SET count=? WHERE id=?",quantity,f.order)
            val before=histories(f.order)
            ok(resolve(id,a,"CANCELLED"))
            assertEquals("CANCELLED",status(f.order)); assertEquals(3,stock(f.product)); assertEquals(before+1,histories(f.order))
            assertEquals("RESOLVED",jdbc.queryForObject("SELECT state FROM order_case WHERE id=?",String::class.java,id))
        }
    }

    @Test
    fun `late PostgreSQL exception preserves legacy internal envelope and rolls back all writes`() {
        val a=admin(); val (f,id)=openCase("CANCELLATION_REQUEST","AWAITING_PAYMENT"); val before=histories(f.order)
        jdbc.execute("""CREATE FUNCTION admin27_audit_late_sql_error() RETURNS trigger LANGUAGE plpgsql AS $$
            BEGIN RAISE EXCEPTION 'admin27 injected late error'; END; $$""")
        try {
            jdbc.execute("""CREATE TRIGGER admin27_audit_late_error BEFORE UPDATE ON order_case FOR EACH ROW
                WHEN (NEW.id=$id AND NEW.state='RESOLVED') EXECUTE FUNCTION admin27_audit_late_sql_error()""")
            try {
                error(resolve(id,a,"CANCELLED"),500,"INTERNAL_ERROR","Внутренняя ошибка")
                assertEquals("AWAITING_PAYMENT",status(f.order)); assertEquals(before,histories(f.order)); assertEquals(3,stock(f.product))
                assertEquals("OPEN",jdbc.queryForObject("SELECT state FROM order_case WHERE id=?",String::class.java,id))
                assertEquals(0,jdbc.queryForObject("SELECT version FROM order_case WHERE id=?",Int::class.java,id))
            } finally { jdbc.execute("DROP TRIGGER admin27_audit_late_error ON order_case") }
        } finally { jdbc.execute("DROP FUNCTION admin27_audit_late_sql_error()") }
    }

    @Test
    fun `human admin binding errors keep legacy required query and enum envelopes`() {
        val a=admin(); val (_,id)=openCase()
        for(path in listOf("/bad-id","?limit=bad","?state=BAD"))
            error(adminRequest("GET",path,a),400,"BAD_REQUEST","Type mismatch.")
        error(adminRequest("POST","/$id/resolve?outcome=BAD&comment=x",a),400,"BAD_REQUEST","Type mismatch.")
        for((method,path,name) in listOf(Triple("POST","/$id/resolve?comment=x","outcome"),
            Triple("POST","/$id/resolve?outcome=BUYER","comment"),Triple("PUT","/$id/telegram-url","url")))
            error(adminRequest(method,path,a),400,"BAD_REQUEST","Required query parameter '$name' is not present.")
    }
}

@ActiveProfiles("legacy")
class AdminOrderCaseLegacyHttpTest : AdminCaseHttpContract() {
    @Test
    fun `legacy accepts signed agent type with ADMIN claim until binding unlike modern`() {
        val a=admin(); val (_,id)=openCase()
        val response=adminRequest("POST","/$id/resolve",a,"agent_access")
        assertEquals(400,response.statusCode(),response.body())
        ok(adminRequest("GET","/$id",a,"agent_access"))
    }
}

@ActiveProfiles("modern")
class AdminOrderCaseModernHttpTest : AdminCaseHttpContract() {
    @Autowired lateinit var cases: AdminOrderCases
    @Autowired lateinit var transactions: PlatformTransactionManager
    @Autowired lateinit var lifecycle: com.model_store.modern.ordering.lifecycle.application.OrderLifecycle
    @Autowired lateinit var store: AdminOrderCaseStore
    @Autowired lateinit var entityManagerFactory: jakarta.persistence.EntityManagerFactory
    fun actor(id: Long) = Actor(id,"admin","ADMIN",TokenType.ACCESS)

    data class BlockedBackend(val pid: Int, val sql: String, val directBlockers: String)
    private fun normalizeSql(sql: String) = sql.replace(Regex("\\$[0-9]+"),"?").replace(Regex("\\s+")," ").trim()
    fun waitForBlock(holder: Int, expectedSql: List<String> = listOf(PARTICIPANT_SQL), exactPids: Set<Int>? = null): Set<Int> {
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(6)
        var found=emptyList<BlockedBackend>()
        do {
            jdbc.execute("SELECT pg_stat_clear_snapshot()")
            found=jdbc.query("""WITH RECURSIVE blocked(pid) AS (
                SELECT pid FROM pg_stat_activity WHERE ?=ANY(pg_blocking_pids(pid))
                UNION SELECT a.pid FROM pg_stat_activity a JOIN blocked b ON b.pid=ANY(pg_blocking_pids(a.pid)))
                SELECT a.pid,a.query,pg_blocking_pids(a.pid)::text AS blockers FROM pg_stat_activity a JOIN blocked b ON b.pid=a.pid
                WHERE a.wait_event_type='Lock'""",{ r,_ -> BlockedBackend(r.getInt(1),normalizeSql(r.getString(2)),r.getString(3)) },holder)
            if(found.size>=expectedSql.size) break
            Thread.sleep(15)
        } while(System.nanoTime()<deadline)
        assertEquals(expectedSql.map(::normalizeSql).sorted(),found.map { it.sql }.sorted(),"Wrong SQL/backend blocked by holder=$holder: $found")
        val pids=found.map { it.pid }.toSet()
        assertEquals(expectedSql.size,pids.size)
        assertFalse(holder in pids)
        if(exactPids!=null) assertEquals(exactPids,pids,"Wrong backend PID blocked by holder=$holder")
        println("admin27 lock proof: rootHolder=$holder waiters=$found")
        return pids
    }

    companion object {
        const val PARTICIPANT_SQL="SELECT id FROM participant WHERE id=? FOR UPDATE"
        const val ADMIN_SQL="SELECT role::text, status::text, is_agent FROM participant WHERE id=? FOR UPDATE"
    }

    @Test
    fun `current active nonagent ADMIN required for all four routes and bot denied before binding`() {
        val (f,id)=openCase()
        for(change in listOf("role='USER'","status='BLOCKED'","status='DELETED'","is_agent=true")) {
            val a=admin(); jdbc.update("UPDATE participant SET $change WHERE id=?",a)
            for((method,path) in listOf("GET" to "", "GET" to "/$id", "PUT" to "/$id/telegram-url?url=https://t.me/x",
                "POST" to "/$id/resolve?outcome=BUYER&comment=x")) {
                val r=adminRequest(method,path,a)
                assertEquals(403,r.statusCode(),r.body())
                if(change=="is_agent=true") assertEquals("",r.body())
                else error(r,403,"ACCESS_DENIED","Доступ запрещён")
            }
            if(change=="is_agent=true") for((method,path) in listOf("GET" to "?limit=oops","GET" to "/oops",
                "POST" to "/oops/resolve", "PUT" to "/oops/telegram-url")) {
                val r=adminRequest(method,path,a); assertEquals(403,r.statusCode(),r.body()); assertEquals("",r.body())
            }
        }
        error(adminRequest("GET","/$id",Long.MAX_VALUE),403,"ACCESS_DENIED","Доступ запрещён")
        for((method,path) in listOf("GET" to "?limit=oops", "GET" to "/oops", "POST" to "/oops/resolve", "PUT" to "/oops/telegram-url"))
            error(adminRequest(method,path,admin(),"agent_access"),403,"ACCESS_DENIED","Доступ запрещён")
        assertEquals("DISPUTED",status(f.order)); assertEquals("OPEN",jdbc.queryForObject("SELECT state FROM order_case WHERE id=?",String::class.java,id))
    }

    @Test
    fun `simultaneous admin resolutions with reversed participant IDs return stock and history exactly once`() {
        // Different admins are each the opposite order party: taking admin first would deadlock.
        for(kind in listOf("DISPUTE","PAYMENT_APPEAL","CANCELLATION_REQUEST")) {
            val (f,id)=openCase(kind,if(kind=="CANCELLATION_REQUEST") "AWAITING_PAYMENT" else "ON_THE_WAY")
            jdbc.update("UPDATE participant SET role='ADMIN' WHERE id IN (?,?)",f.buyer,f.seller)
            val before=histories(f.order); val count=stock(f.product)!!
            val pool=Executors.newFixedThreadPool(2); val gate=CyclicBarrier(2)
            try {
                lateinit var first: Future<HttpResponse<String>>; lateinit var second: Future<HttpResponse<String>>
                TransactionTemplate(transactions).execute {
                    val holder=jdbc.queryForObject("SELECT pg_backend_pid()",Int::class.java)!!
                    jdbc.queryForObject("SELECT id FROM participant WHERE id=? FOR UPDATE",Long::class.java,f.buyer)
                    val outcome=if(kind=="CANCELLATION_REQUEST") "CANCELLED" else "BUYER"
                    first=pool.submit<HttpResponse<String>> { gate.await(5,TimeUnit.SECONDS); resolve(id,f.buyer,outcome) }
                    second=pool.submit<HttpResponse<String>> { gate.await(5,TimeUnit.SECONDS); resolve(id,f.seller,outcome) }
                    waitForBlock(holder,listOf(ADMIN_SQL,PARTICIPANT_SQL))
                }
                val results=listOf(first.get(12,TimeUnit.SECONDS),second.get(12,TimeUnit.SECONDS))
                assertEquals(listOf(200,409),results.map { it.statusCode() }.sorted(),results.map { it.body() }.toString())
                error(results.single { it.statusCode()==409 },409,"ORDER_CASE_ALREADY_RESOLVED","Обращение уже завершено")
                assertEquals(before+1,histories(f.order)); assertEquals(count+if(kind=="CANCELLATION_REQUEST") 1 else 0,stock(f.product))
                assertEquals(1,jdbc.queryForObject("SELECT version FROM order_case WHERE id=?",Int::class.java,id))
            } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS)) }
        }
    }

    @Test
    fun `role revocation while waiting and changed parties refuse without extending lock frontier`() {
        for(changedParties in listOf(false,true)) {
            val replacement=participant(); val (f,id)=openCase(); val a=admin()
            val before=histories(f.order); val pool=Executors.newSingleThreadExecutor()
            try {
                lateinit var pending: Future<HttpResponse<String>>
                TransactionTemplate(transactions).execute {
                    val holder=jdbc.queryForObject("SELECT pg_backend_pid()",Int::class.java)!!
                    jdbc.queryForObject("SELECT id FROM participant WHERE id=? FOR UPDATE",Long::class.java,f.buyer)
                    pending=pool.submit<HttpResponse<String>> { resolve(id,a,"BUYER") }
                    waitForBlock(holder)
                    if(changedParties) jdbc.update("UPDATE \"order\" SET customer_id=? WHERE id=?",replacement,f.order)
                    else jdbc.update("UPDATE participant SET role='USER' WHERE id=?",a)
                }
                val response=pending.get(12,TimeUnit.SECONDS)
                if(changedParties) error(response,400,"BAD_REQUEST",invalid)
                else error(response,403,"ACCESS_DENIED","Доступ запрещён")
                assertEquals(before,histories(f.order)); assertEquals("DISPUTED",status(f.order))
                assertEquals("OPEN",jdbc.queryForObject("SELECT state FROM order_case WHERE id=?",String::class.java,id))
            } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS)) }
        }
    }

    @Test
    fun `outer failure and late case SQL failure roll back order stock trigger history and decision`() {
        for(lateFailure in listOf(false,true)) {
            val (f,id)=openCase("CANCELLATION_REQUEST","AWAITING_PAYMENT"); val a=admin()
            val before=histories(f.order); val count=stock(f.product)
            if(lateFailure) jdbc.execute("""ALTER TABLE order_case ADD CONSTRAINT admin27_test_failure CHECK (id<>$id OR state='OPEN')""")
            try {
                fun attempt() {
                    TransactionTemplate(transactions).execute {
                        cases.telegram(actor(a),id,"https://t.me/rollback")
                        cases.resolve(actor(a),id,CaseOutcome.CANCELLED,"decision")
                        assertEquals("CANCELLED",status(f.order)); assertEquals(count!!+1,stock(f.product))
                        assertEquals(before+1,histories(f.order))
                        assertEquals("RESOLVED",jdbc.queryForObject("SELECT state FROM order_case WHERE id=?",String::class.java,id))
                        throw IllegalStateException("outer failure")
                    }
                }
                if(lateFailure) {
                    val failure=assertThrows(org.springframework.dao.DataIntegrityViolationException::class.java) { attempt() }
                    val root=generateSequence<Throwable>(failure) { it.cause }.last()
                    assertEquals("23514",(root as java.sql.SQLException).sqlState)
                    assertTrue(root.message.orEmpty().contains("admin27_test_failure"),root.message)
                } else {
                    assertEquals("outer failure",assertThrows(IllegalStateException::class.java) { attempt() }.message)
                }
                assertEquals("AWAITING_PAYMENT",status(f.order)); assertEquals(before,histories(f.order)); assertEquals(count,stock(f.product))
                assertEquals("OPEN",jdbc.queryForObject("SELECT state FROM order_case WHERE id=?",String::class.java,id))
                assertEquals(0,jdbc.queryForObject("SELECT version FROM order_case WHERE id=?",Int::class.java,id))
                assertNull(jdbc.queryForObject("SELECT telegram_url FROM order_case WHERE id=?",String::class.java,id))
            } finally { if(lateFailure) jdbc.execute("ALTER TABLE order_case DROP CONSTRAINT admin27_test_failure") }
        }
    }

    @Test
    fun `real payment and case opening serialize with resolution using accepted owner operations`() {
        for(payment in listOf(false,true)) {
            val (f,id)=openCase(if(payment) "CANCELLATION_REQUEST" else "DISPUTE","AWAITING_PAYMENT")
            val a=admin(); val evidence=image(f.buyer); val before=histories(f.order); val count=stock(f.product)!!
            val pool=Executors.newFixedThreadPool(2); val gate=CyclicBarrier(2)
            try {
                lateinit var decision: Future<HttpResponse<String>>; lateinit var other: Future<HttpResponse<String>>
                TransactionTemplate(transactions).execute {
                    val holder=jdbc.queryForObject("SELECT pg_backend_pid()",Int::class.java)!!
                    jdbc.queryForObject("SELECT id FROM participant WHERE id=? FOR UPDATE",Long::class.java,f.buyer)
                    decision=pool.submit<HttpResponse<String>> { gate.await(5,TimeUnit.SECONDS); resolve(id,a,if(payment) "CANCELLED" else "BUYER") }
                    other=pool.submit<HttpResponse<String>> {
                        gate.await(5,TimeUnit.SECONDS)
                        if(payment) request("POST","/order/${f.order}/ASSEMBLING?imageId=$evidence",f.buyer)
                        else request("POST","/order/orders/${f.order}/DISPUTED?comment=new&imageIds=$evidence",f.buyer)
                    }
                    waitForBlock(holder,listOf(PARTICIPANT_SQL,PARTICIPANT_SQL))
                }
                val r=decision.get(12,TimeUnit.SECONDS); val o=other.get(12,TimeUnit.SECONDS)
                if(!payment) {
                    ok(r)
                    if(o.statusCode()==409) error(o,409,"ORDER_CASE_ALREADY_OPEN","По заказу уже открыто обращение")
                    else error(o,400,"ORDER_CASE_INVALID_TRANSITION",invalid)
                    assertEquals("FAILED",status(f.order))
                }
                else if(r.statusCode()==200) { error(o,400,"BAD_REQUEST","Статус заказа изменился. Повторите операцию"); assertEquals("CANCELLED",status(f.order)) }
                else { error(r,409,"ORDER_CASE_ALREADY_RESOLVED","Обращение уже завершено"); ok(o); assertEquals("ASSEMBLING",status(f.order)) }
                assertEquals(before+1,histories(f.order))
                assertEquals(count+if(payment && r.statusCode()==200) 1 else 0,stock(f.product))
                assertEquals(1,count("SELECT count(*) FROM order_case WHERE order_id=${f.order}"))
                assertEquals(1,jdbc.queryForObject("SELECT version FROM order_case WHERE id=?",Int::class.java,id))
                assertEquals(if(payment && o.statusCode()==200) "ACTIVE" else "TEMPORARY",
                    jdbc.queryForObject("SELECT status::text FROM image WHERE id=?",String::class.java,evidence))
            } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS)) }
        }
    }

    @Test
    fun `payment wins before admin decision in cancellation and dispute and opposite order rolls proof back`() {
        for(dispute in listOf(false,true)) for(paymentFirst in listOf(false,true)) {
            val (f,id)=openCase(if(dispute) "DISPUTE" else "CANCELLATION_REQUEST","AWAITING_PAYMENT")
            if(dispute) {
                val original=image(f.buyer,state="ACTIVE",entity=f.order)
                jdbc.update("UPDATE \"order\" SET image_payment_proof_id=? WHERE id=?",original,f.order)
            }
            val a=admin(); val proof=image(f.buyer); val before=histories(f.order); val quantity=stock(f.product)!!
            val pool=Executors.newSingleThreadExecutor()
            try {
                lateinit var pending: Future<HttpResponse<String>>
                TransactionTemplate(transactions).execute {
                    val holder=jdbc.queryForObject("SELECT pg_backend_pid()",Int::class.java)!!
                    if(paymentFirst) {
                        lifecycle.execute(Actor(f.buyer,"buyer","USER",TokenType.ACCESS), f.order,
                            com.model_store.modern.ordering.lifecycle.domain.OrderAction.PAY,imageId=proof)
                        pending=pool.submit<HttpResponse<String>> { resolve(id,a,if(dispute) "BUYER" else "CANCELLED") }
                    } else {
                        cases.resolve(actor(a),id,if(dispute) CaseOutcome.BUYER else CaseOutcome.CANCELLED,"decision")
                        pending=pool.submit<HttpResponse<String>> { request("POST","/order/${f.order}/ASSEMBLING?imageId=$proof",f.buyer) }
                    }
                    waitForBlock(holder)
                }
                val response=pending.get(12,TimeUnit.SECONDS)
                if(paymentFirst && dispute) ok(response)
                else if(paymentFirst) error(response,409,"ORDER_CASE_ALREADY_RESOLVED","Обращение уже завершено")
                else error(response,400,"BAD_REQUEST",if(dispute) "Операция недоступна для текущего состояния заказа" else "Статус заказа изменился. Повторите операцию")
                assertEquals(if(dispute) "FAILED" else if(paymentFirst) "ASSEMBLING" else "CANCELLED",status(f.order))
                assertEquals(before+1,histories(f.order)); assertEquals(quantity+if(!dispute && !paymentFirst) 1 else 0,stock(f.product))
                assertEquals(if(paymentFirst) "ACTIVE" else "TEMPORARY",jdbc.queryForObject("SELECT status::text FROM image WHERE id=?",String::class.java,proof))
                assertEquals(if(paymentFirst && dispute) 1 else 0,count("SELECT count(*) FROM order_case_image WHERE case_id=$id AND image_id=$proof"))
                assertEquals("RESOLVED",jdbc.queryForObject("SELECT state FROM order_case WHERE id=?",String::class.java,id))
            } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS)) }
        }
    }

    @Test
    fun `typed mutation store rejects missing JDBC-only and readonly application transactions`() {
        val (f,id)=openCase(); val a=admin()
        val c=AdminCase(id,f.order,0,"DISPUTE","OPEN","ON_THE_WAY")
        fun writes() = listOf<() -> Any?>( { store.lock(id) }, { store.telegram(c,"https://t.me/x") },
            { store.transition(f.order,"DISPUTED",CaseResolutionTarget.FAILED,"x") }, { store.resolve(c,a,CaseOutcome.BUYER,"x") })
        writes().forEach { assertThrows(com.model_store.modern.ordering.lifecycle.application.LifecycleTransactionRequired::class.java) { it() } }
        TransactionTemplate(transactions).apply { isReadOnly=true }.execute {
            writes().forEach { assertThrows(com.model_store.modern.ordering.lifecycle.application.LifecycleTransactionRequired::class.java) { it() } }
        }
        val ds=jdbc.dataSource!!
        TransactionTemplate(org.springframework.jdbc.datasource.DataSourceTransactionManager(ds)).execute {
            writes().forEach { assertThrows(com.model_store.modern.ordering.lifecycle.application.LifecycleTransactionRequired::class.java) { it() } }
        }
        val foreign=org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean().apply {
            setDataSource(ds); setPackagesToScan("com.model_store.modern.ordering.lifecycle")
            persistenceUnitName="admin27-audit-foreign"
            jpaVendorAdapter=org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter()
            setJpaPropertyMap(mapOf("hibernate.hbm2ddl.auto" to "none")); afterPropertiesSet()
        }
        try {
            TransactionTemplate(org.springframework.orm.jpa.JpaTransactionManager(foreign.`object`!!)).execute {
                writes().forEach { assertThrows(com.model_store.modern.ordering.lifecycle.application.LifecycleTransactionRequired::class.java) { it() } }
            }
        } finally { foreign.destroy() }
        val otherDs=org.springframework.jdbc.datasource.DriverManagerDataSource(
            "jdbc:postgresql://localhost:${UserCaseHttpContract.postgres.port}/postgres","postgres","")
        val wrongDsManager=org.springframework.orm.jpa.JpaTransactionManager(entityManagerFactory).apply { dataSource=otherDs }
        TransactionTemplate(wrongDsManager).execute {
            writes().forEach { assertThrows(com.model_store.modern.ordering.lifecycle.application.LifecycleTransactionRequired::class.java) { it() } }
        }
        assertEquals("DISPUTED",status(f.order)); assertEquals(0,jdbc.queryForObject("SELECT version FROM order_case WHERE id=?",Int::class.java,id))
    }

    @Test
    fun `each read and write holds current ADMIN until commit and rechecks each revocation after exact backend wait`() {
        for(operation in listOf("queue","detail","telegram","resolve"))
            for(change in listOf("role='USER'","status='BLOCKED'","is_agent=true"))
                for(operationFirst in listOf(false,true)) {
                    // Caller is the lowest participant ID, so a mutation waits at its owner ADMIN lock too.
                    val a=admin(); val (f,id)=openCase(); val before=histories(f.order)
                    val pool=Executors.newSingleThreadExecutor(); val revokePid=ArrayBlockingQueue<Int>(1)
                    try {
                        lateinit var pending: Future<*>
                        TransactionTemplate(transactions).execute {
                            val holder=jdbc.queryForObject("SELECT pg_backend_pid()",Int::class.java)!!
                            if(operationFirst) {
                                when(operation) {
                                    "queue" -> cases.queue(actor(a),CaseState.OPEN,50,0)
                                    "detail" -> cases.detail(actor(a),id)
                                    "telegram" -> cases.telegram(actor(a),id,"https://t.me/retained")
                                    else -> cases.resolve(actor(a),id,CaseOutcome.BUYER,"decision")
                                }
                                pending=pool.submit<Int> {
                                    jdbc.dataSource!!.connection.use { connection ->
                                        connection.createStatement().use { statement ->
                                            statement.execute("SET statement_timeout='10s'")
                                            statement.executeQuery("SELECT pg_backend_pid()").use { r -> r.next(); revokePid.put(r.getInt(1)) }
                                        }
                                        connection.prepareStatement("UPDATE participant SET $change WHERE id=?").use {
                                            it.setLong(1,a); it.executeUpdate()
                                        }
                                    }
                                }
                                val expectedPid=requireNotNull(revokePid.poll(5,TimeUnit.SECONDS))
                                waitForBlock(holder,listOf("UPDATE participant SET $change WHERE id=?"),setOf(expectedPid))
                            } else {
                                jdbc.update("UPDATE participant SET $change WHERE id=?",a)
                                pending=pool.submit<HttpResponse<String>> {
                                    when(operation) {
                                        "queue" -> adminRequest("GET","",a)
                                        "detail" -> adminRequest("GET","/$id",a)
                                        "telegram" -> adminRequest("PUT","/$id/telegram-url?url=https://t.me/retained",a)
                                        else -> resolve(id,a,"BUYER")
                                    }
                                }
                                waitForBlock(holder,listOf(ADMIN_SQL))
                            }
                            assertFalse(pending.isDone,"$operation $change operationFirst=$operationFirst must wait for transaction end")
                        }
                        if(operationFirst) {
                            assertEquals(1,pending.get(12,TimeUnit.SECONDS))
                            val response=adminRequest("GET","/$id",a)
                            if(change=="is_agent=true") { assertEquals(403,response.statusCode()); assertEquals("",response.body()) }
                            else error(response,403,"ACCESS_DENIED","Доступ запрещён")
                        } else {
                            @Suppress("UNCHECKED_CAST")
                            error((pending as Future<HttpResponse<String>>).get(12,TimeUnit.SECONDS),403,"ACCESS_DENIED","Доступ запрещён")
                        }
                        assertEquals(if(operationFirst && operation=="resolve") "FAILED" else "DISPUTED",status(f.order))
                        assertEquals(before+if(operationFirst && operation=="resolve") 1 else 0,histories(f.order))
                        assertEquals(3,stock(f.product))
                        assertEquals(if(operationFirst && operation in setOf("telegram","resolve")) 1 else 0,
                            jdbc.queryForObject("SELECT version FROM order_case WHERE id=?",Int::class.java,id))
                    } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS)) }
                }
    }

    @Test
    fun `case moved to another order during participant wait refuses without following its new parties`() {
        val other=fixture(); val (f,id)=openCase(); val a=admin(); val before=histories(f.order)
        val pool=Executors.newSingleThreadExecutor()
        try {
            lateinit var pending: Future<HttpResponse<String>>
            TransactionTemplate(transactions).execute {
                val holder=jdbc.queryForObject("SELECT pg_backend_pid()",Int::class.java)!!
                jdbc.queryForObject(PARTICIPANT_SQL,Long::class.java,f.buyer)
                pending=pool.submit<HttpResponse<String>> { resolve(id,a,"BUYER") }
                waitForBlock(holder)
                jdbc.update("UPDATE order_case SET order_id=? WHERE id=?",other.order,id)
            }
            error(pending.get(12,TimeUnit.SECONDS),400,"BAD_REQUEST",invalid)
            assertEquals("DISPUTED",status(f.order)); assertEquals(before,histories(f.order))
            assertEquals("BOOKED",status(other.order)); assertEquals(1,histories(other.order))
            assertEquals("OPEN",jdbc.queryForObject("SELECT state FROM order_case WHERE id=?",String::class.java,id))
            assertEquals(0,jdbc.queryForObject("SELECT version FROM order_case WHERE id=?",Int::class.java,id))
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS)) }
    }

    @Test
    fun `opening first and resolution first each prove exact blocked SQL and legacy business refusal`() {
        val caseSql="SELECT id FROM order_case WHERE order_id=? AND state='OPEN' ORDER BY id FOR UPDATE"
        for(resolveFirst in listOf(false,true)) {
            val (f,id)=openCase(); val a=admin(); val evidence=image(f.buyer); val before=histories(f.order)
            val pool=Executors.newFixedThreadPool(2)
            try {
                lateinit var opening: Future<HttpResponse<String>>
                var decision: Future<HttpResponse<String>>?=null
                TransactionTemplate(transactions).execute {
                    val holder=jdbc.queryForObject("SELECT pg_backend_pid()",Int::class.java)!!
                    if(resolveFirst) cases.resolve(actor(a),id,CaseOutcome.BUYER,"decision")
                    else jdbc.queryForObject("SELECT id FROM order_case WHERE id=? FOR UPDATE",Long::class.java,id)
                    opening=pool.submit<HttpResponse<String>> { request("POST","/order/orders/${f.order}/DISPUTED?comment=new&imageIds=$evidence",f.buyer) }
                    if(resolveFirst) waitForBlock(holder)
                    else {
                        val openerPid=waitForBlock(holder,listOf(caseSql)).single()
                        decision=pool.submit<HttpResponse<String>> { resolve(id,a,"BUYER") }
                        val all=waitForBlock(holder,listOf(caseSql,PARTICIPANT_SQL))
                        assertTrue(openerPid in all)
                    }
                }
                val result=opening.get(12,TimeUnit.SECONDS)
                if(resolveFirst) error(result,400,"ORDER_CASE_INVALID_TRANSITION",invalid)
                else { error(result,409,"ORDER_CASE_ALREADY_OPEN","По заказу уже открыто обращение"); ok(decision!!.get(12,TimeUnit.SECONDS)) }
                assertEquals("FAILED",status(f.order)); assertEquals(before+1,histories(f.order)); assertEquals(3,stock(f.product))
                assertEquals(1,count("SELECT count(*) FROM order_case WHERE order_id=${f.order}"))
                assertEquals(1,jdbc.queryForObject("SELECT version FROM order_case WHERE id=?",Int::class.java,id))
                assertEquals("TEMPORARY",jdbc.queryForObject("SELECT status::text FROM image WHERE id=?",String::class.java,evidence))
                assertEquals(0,count("SELECT count(*) FROM order_case_image WHERE image_id=$evidence"))
            } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS)) }
        }
    }

    @Test
    fun `Telegram retains legacy observed version CAS against another URL or resolution while waiting`() {
        for(resolved in listOf(false,true)) {
            val (f,id)=openCase(); val a=admin(); val before=histories(f.order)
            val pool=Executors.newSingleThreadExecutor()
            try {
                lateinit var pending: Future<HttpResponse<String>>
                TransactionTemplate(transactions).execute {
                    val holder=jdbc.queryForObject("SELECT pg_backend_pid()",Int::class.java)!!
                    jdbc.queryForObject(PARTICIPANT_SQL,Long::class.java,f.buyer)
                    pending=pool.submit<HttpResponse<String>> { adminRequest("PUT","/$id/telegram-url?url=https://t.me/loser",a) }
                    waitForBlock(holder)
                    if(resolved) cases.resolve(actor(a),id,CaseOutcome.BUYER,"decision")
                    else cases.telegram(actor(a),id,"https://t.me/winner")
                }
                error(pending.get(12,TimeUnit.SECONDS),400,"ORDER_CASE_INVALID_TRANSITION",invalid)
                assertEquals(1,jdbc.queryForObject("SELECT version FROM order_case WHERE id=?",Int::class.java,id))
                assertEquals(if(resolved) null else "https://t.me/winner",jdbc.queryForObject("SELECT telegram_url FROM order_case WHERE id=?",String::class.java,id))
                assertEquals(if(resolved) "FAILED" else "DISPUTED",status(f.order))
                assertEquals(before+if(resolved) 1 else 0,histories(f.order)); assertEquals(3,stock(f.product))
            } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS)) }
        }
    }
}
