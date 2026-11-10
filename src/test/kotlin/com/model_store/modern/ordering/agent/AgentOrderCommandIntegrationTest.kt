package com.model_store.modern.ordering.agent

import com.model_store.modern.seller.agent.order.AgentOrderFixture
import com.model_store.modern.ordering.agent.application.*
import com.model_store.modern.ordering.agent.infrastructure.JdbcAgentOrderAudit
import com.model_store.modern.ordering.lifecycle.application.*
import com.model_store.modern.ordering.lifecycle.infrastructure.JpaLifecycleTransaction
import com.model_store.modern.identity.participant.application.AgentProfileFailure
import jakarta.persistence.EntityManagerFactory
import com.model_store.modern.ordering.`case`.user.application.UserOrderCases
import com.model_store.modern.ordering.`case`.user.domain.CaseKind
import com.model_store.modern.ordering.`case`.admin.application.AdminOrderCases
import com.model_store.modern.ordering.`case`.admin.domain.*
import com.model_store.modern.ordering.lifecycle.domain.*
import com.model_store.modern.shared.domain.Actor
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.util.concurrent.*

@ActiveProfiles("modern")
class AgentOrderCommandIntegrationTest : AgentOrderFixture() {
    @Autowired lateinit var commands: AgentOrderCommands
    @Autowired lateinit var transactions: PlatformTransactionManager
    @Autowired lateinit var emf: EntityManagerFactory
    @Autowired lateinit var audit: AgentOrderAudit
    @Autowired lateinit var lifecycle: OrderLifecycle
    @Autowired lateinit var userCases: UserOrderCases
    @Autowired lateinit var adminCases: AdminOrderCases
    @Autowired lateinit var images: com.model_store.modern.media.image.command.application.ImageCommandRows
    @Autowired lateinit var store: LifecycleStore
    @Autowired lateinit var access: com.model_store.modern.identity.participant.application.AgentAccessLock
    @Autowired lateinit var stockOwner: com.model_store.modern.catalog.product.reservation.application.ProductReservation

    @Test
    fun `unproxied command and direct audit validate actual caller manager and resources`() {
        val a=admin(); val f=agentFixture(); val data=jdbc.dataSource!!
        val plain=AgentOrderCommands(store,JpaLifecycleTransaction(emf,data),access,stockOwner,audit)
        fun call()=plain.execute(a,f.seller,f.order,"CANCEL",null,null)
        assertThrows(LifecycleTransactionRequired::class.java) { call() }
        assertThrows(LifecycleTransactionRequired::class.java) {
            TransactionTemplate(org.springframework.jdbc.datasource.DataSourceTransactionManager(data)).execute { call() }
        }
        val url=data.connection.use { it.metaData.url }
        val otherData=org.springframework.jdbc.datasource.DriverManagerDataSource(url,"postgres","")
        val wrongManager=JpaTransactionManager(emf).apply { dataSource=otherData }
        assertThrows(LifecycleTransactionRequired::class.java) { TransactionTemplate(wrongManager).execute { call() } }
        val noOp=object : LifecycleTransaction { override fun requireWritableJpa() {} }
        val direct=JdbcAgentOrderAudit(NamedParameterJdbcTemplate(jdbc),noOp,emf,data)
        assertThrows(LifecycleTransactionRequired::class.java) { direct.record(a,f.seller,f.order,"CANCEL") }
        assertEquals("BOOKED",status(f.order)); assertEquals(0,audits(f.order)); assertEquals(3,stock(f.product))
    }

    @Test
    fun auditJdbcIdentityIsRequiredBeforeInsert() {
        val a=admin(); val f=agentFixture()
        val url=jdbc.dataSource!!.connection.use { it.metaData.url }
        val otherData=org.springframework.jdbc.datasource.DriverManagerDataSource(url,"postgres","")
        val actualGuard=JpaLifecycleTransaction(emf,jdbc.dataSource!!)
        val adapter=JdbcAgentOrderAudit(NamedParameterJdbcTemplate(otherData),actualGuard,emf,jdbc.dataSource!!)
        try {
            assertThrows(LifecycleTransactionRequired::class.java) {
                TransactionTemplate(transactions).execute { tx ->
                    tx.setRollbackOnly()
                    adapter.record(a,f.seller,f.order,"CANCEL")
                }
            }
            assertEquals(0,audits(f.order))
        } finally {
            println("isolated audit rows after caller rollback: ${audits(f.order)}")
            jdbc.update("DELETE FROM admin_agent_order_action WHERE order_id=?",f.order)
        }
    }

    private fun execute(a:Long,f:Fixture,action:String="CANCEL")=commands.execute(a,f.seller,f.order,action,"decision",null)
    private fun unchanged(f:Fixture,before:Int) {
        assertEquals("BOOKED",status(f.order)); assertEquals(3,stock(f.product))
        assertEquals(before,histories(f.order)); assertEquals(0,audits(f.order))
    }

    @Test
    fun `delegated matrix rejects every wrong persisted status without effects`() {
        val a=admin()
        for((action,allowed) in mapOf("CONFIRM" to "BOOKED","CONFIRM_PREPAYMENT" to "AWAITING_PREPAYMENT_APPROVAL",
            "SHIP" to "ASSEMBLING","CANCEL" to "BOOKED")) {
            val f=agentFixture()
            for(state in OrderStatus.entries.map { it.name }.filter { it!=allowed }) {
                jdbc.update("UPDATE \"order\" SET status=CAST(? AS order_status) WHERE id=?",state,f.order)
                val before=histories(f.order)
                val failure=assertThrows(LifecycleFailure::class.java) { execute(a,f,action) }
                assertEquals(LifecycleProblem.INVALID_TRANSITION,failure.problem)
                assertEquals(state,status(f.order)); assertEquals(before,histories(f.order))
                assertEquals(0,audits(f.order)); assertEquals(3,stock(f.product))
            }
        }
    }

    @Test
    fun `case opening and ship serialize in both orders without copying case or proof work`() {
        for(shipFirst in listOf(false,true)) {
            val a=admin(); val f=agentFixture(); val proof=image(f.buyer,state="ACTIVE",entity=f.order)
            jdbc.update("UPDATE \"order\" SET status='ASSEMBLING',image_payment_proof_id=? WHERE id=?",proof,f.order)
            val before=histories(f.order); val pool=Executors.newSingleThreadExecutor()
            try {
                lateinit var pending:Future<Long>
                TransactionTemplate(transactions).execute {
                    val holder=jdbc.queryForObject("SELECT pg_backend_pid()",Int::class.java)!!
                    if(shipFirst) {
                        execute(a,f,"SHIP")
                        pending=submitWork(pool) { userCases.open(Actor(f.buyer,"buyer","USER"),f.order,CaseKind.DISPUTE,"dispute") }
                    } else {
                        jdbc.queryForList("SELECT id FROM participant WHERE id=? FOR UPDATE",minOf(a,f.buyer,f.seller))
                        pending=submitWork(pool) { execute(a,f,"SHIP") }
                        waitBlocked(holder,participantSql,pending)
                        userCases.open(Actor(f.buyer,"buyer","USER"),f.order,CaseKind.DISPUTE,"dispute")
                    }
                    waitBlocked(holder,participantSql,pending)
                }
                if(shipFirst) assertTrue(pending.get(12,TimeUnit.SECONDS)>0)
                else {
                    val failure=assertThrows(ExecutionException::class.java) { pending.get(12,TimeUnit.SECONDS) }
                    assertEquals(LifecycleProblem.STATUS_CHANGED,(failure.cause as LifecycleFailure).problem)
                }
                assertEquals("DISPUTED",status(f.order)); assertEquals(3,stock(f.product))
                assertEquals(if(shipFirst) 1 else 0,audits(f.order))
                assertEquals(before+if(shipFirst) 2 else 1,histories(f.order))
                assertEquals(if(shipFirst) "ON_THE_WAY" else "ASSEMBLING",jdbc.queryForObject(
                    "SELECT previous_order_status::text FROM order_case WHERE order_id=?",String::class.java,f.order))
            } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS)) }
        }
    }

    @Test
    fun `persisted cancellation resolution and agent cancel return stock once in either order`() {
        for(agentFirst in listOf(false,true)) {
            val a=admin(); val resolver=admin(); val f=agentFixture()
            // SQL accepts a legacy BOOKED cancellation request; owner27 explicitly supports its previous status.
            val caseId=case(f,"CANCELLATION_REQUEST","BOOKED"); val before=histories(f.order)
            val pool=Executors.newSingleThreadExecutor()
            try {
                lateinit var pending:Future<Long>
                TransactionTemplate(transactions).execute {
                    val holder=jdbc.queryForObject("SELECT pg_backend_pid()",Int::class.java)!!
                    if(agentFirst) {
                        execute(a,f)
                        pending=submitWork(pool) { adminCases.resolve(Actor(resolver,"admin","ADMIN"),caseId,CaseOutcome.CANCELLED,"decision") }
                    } else {
                        jdbc.queryForList("SELECT id FROM participant WHERE id=? FOR UPDATE",minOf(a,resolver,f.buyer,f.seller))
                        pending=submitWork(pool) { execute(a,f) }
                        waitBlocked(holder,participantSql,pending)
                        adminCases.resolve(Actor(resolver,"admin","ADMIN"),caseId,CaseOutcome.CANCELLED,"decision")
                    }
                    waitBlocked(holder,participantSql,pending)
                }
                val failure=assertThrows(ExecutionException::class.java) { pending.get(12,TimeUnit.SECONDS) }
                if(agentFirst) assertEquals(AdminCaseProblem.INVALID_TRANSITION,(failure.cause as AdminCaseFailure).problem)
                else assertEquals(LifecycleProblem.STATUS_CHANGED,(failure.cause as LifecycleFailure).problem)
                assertEquals("CANCELLED",status(f.order)); assertEquals(4,stock(f.product)); assertEquals(before+1,histories(f.order))
                assertEquals(if(agentFirst) 1 else 0,audits(f.order))
            } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS)) }
        }
    }

    @Test
    fun `real payment owner wins before incompatible delegated confirm without extra audit or history`() {
        val a=admin(); val f=agentFixture(); val proof=image(f.buyer)
        jdbc.update("UPDATE \"order\" SET status='AWAITING_PAYMENT' WHERE id=?",f.order)
        val before=histories(f.order); val pool=Executors.newSingleThreadExecutor()
        try {
            lateinit var pending:Future<Long>
            TransactionTemplate(transactions).execute {
                val holder=jdbc.queryForObject("SELECT pg_backend_pid()",Int::class.java)!!
                jdbc.queryForList("SELECT id FROM participant WHERE id=? FOR UPDATE",minOf(a,f.buyer,f.seller))
                pending=submitWork(pool) { execute(a,f,"CONFIRM") }
                waitBlocked(holder,participantSql,pending)
                lifecycle.execute(Actor(f.buyer,"buyer","USER"),f.order,OrderAction.PAY,"paid",proof)
            }
            val failure=assertThrows(ExecutionException::class.java) { pending.get(12,TimeUnit.SECONDS) }
            assertEquals(LifecycleProblem.INVALID_TRANSITION,(failure.cause as LifecycleFailure).problem)
            assertEquals("ASSEMBLING",status(f.order)); assertEquals(0,audits(f.order)); assertEquals(before+1,histories(f.order))
            assertEquals(proof,jdbc.queryForObject("SELECT image_payment_proof_id FROM \"order\" WHERE id=?",Long::class.java,f.order))
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS)) }
    }

    @Test
    fun `late audit failure and outer rollback undo transition stock trigger history and audit`() {
        val a=admin(); val f=agentFixture(); val before=histories(f.order)
        val constraint="ao_fail_${f.order}"
        jdbc.execute("ALTER TABLE admin_agent_order_action ADD CONSTRAINT $constraint CHECK(order_id<>${f.order})")
        try {
            val failure=assertThrows(org.springframework.dao.DataIntegrityViolationException::class.java) { execute(a,f) }
            val root=generateSequence<Throwable>(failure) { it.cause }.last() as java.sql.SQLException
            assertEquals("23514",root.sqlState); assertTrue(root.message.orEmpty().contains(constraint))
            unchanged(f,before)
        } finally { jdbc.execute("ALTER TABLE admin_agent_order_action DROP CONSTRAINT $constraint") }
        val failure=assertThrows(IllegalStateException::class.java) {
            TransactionTemplate(transactions).execute {
                execute(a,f)
                assertEquals("CANCELLED",status(f.order)); assertEquals(4,stock(f.product))
                assertEquals(1,audits(f.order)); assertEquals(before+1,histories(f.order))
                throw IllegalStateException("outer rollback")
            }
        }
        assertEquals("outer rollback",failure.message); unchanged(f,before)
    }

    @Test
    fun `direct audit guards reject no transaction readonly JDBC only foreign EMF and wrong datasource`() {
        val a=admin(); val f=agentFixture(); val before=histories(f.order)
        assertThrows(LifecycleTransactionRequired::class.java) { audit.record(a,f.seller,f.order,"CANCEL") }
        assertThrows(LifecycleTransactionRequired::class.java) {
            TransactionTemplate(transactions).apply { isReadOnly=true }.execute { audit.record(a,f.seller,f.order,"CANCEL") }
        }
        assertThrows(LifecycleTransactionRequired::class.java) {
            TransactionTemplate(transactions).apply { isReadOnly=true }.execute { execute(a,f) }
        }
        val data=jdbc.dataSource!!
        assertThrows(LifecycleTransactionRequired::class.java) {
            TransactionTemplate(org.springframework.jdbc.datasource.DataSourceTransactionManager(data)).execute {
                audit.record(a,f.seller,f.order,"CANCEL")
            }
        }
        val foreign=org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean().apply {
            dataSource=data; setPackagesToScan("com.model_store.modern.identity.participant.infrastructure")
            jpaVendorAdapter=org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter()
            setJpaPropertyMap(mapOf("hibernate.hbm2ddl.auto" to "none")); afterPropertiesSet()
        }
        try {
            assertThrows(LifecycleTransactionRequired::class.java) {
                TransactionTemplate(JpaTransactionManager(foreign.`object`!!)).execute { audit.record(a,f.seller,f.order,"CANCEL") }
            }
            TransactionTemplate(transactions).execute {
                val wrong=org.springframework.jdbc.datasource.DriverManagerDataSource()
                val adapter=JdbcAgentOrderAudit(NamedParameterJdbcTemplate(jdbc),JpaLifecycleTransaction(emf,wrong),emf,data)
                assertThrows(LifecycleTransactionRequired::class.java) { adapter.record(a,f.seller,f.order,"CANCEL") }
            }
        } finally { foreign.destroy() }
        unchanged(f,before)
    }

    private val workerPids=java.util.concurrent.ConcurrentHashMap<Future<*>,Int>()
    private fun <T:Any> submitWork(pool:ExecutorService, block:()->T):Future<T> {
        val pids=ArrayBlockingQueue<Int>(1)
        val future=pool.submit<T> {
            TransactionTemplate(transactions).execute {
                jdbc.execute("SET LOCAL statement_timeout='15s'")
                jdbc.execute("SET LOCAL lock_timeout='12s'")
                pids.put(jdbc.queryForObject("SELECT pg_backend_pid()",Int::class.java)!!)
                block()
            }!!
        }
        workerPids[future]=requireNotNull(pids.poll(8,TimeUnit.SECONDS)) { "worker failed to expose backend PID" }
        return future
    }
    private fun normalized(sql:String)=sql.replace(Regex("\\$[0-9]+"),"?").replace(Regex("\\s+")," ").trim()
    private val participantSql="SELECT id FROM participant WHERE id=? FOR UPDATE"
    private val mediaParticipantSql="SELECT role::text AS role, status::text AS status, is_agent FROM participant WHERE id = ? FOR UPDATE"

    // Pin BOTH worker backend and root holder and compare complete normalized SQL.
    private fun waitBlocked(holder:Int,sql:String,pending:Future<*>) {
        val expectedPid=workerPids.getValue(pending)
        val deadline=System.nanoTime()+Duration.ofSeconds(8).toNanos()
        while(System.nanoTime()<deadline) {
            jdbc.execute("SELECT pg_stat_clear_snapshot()")
            val waiting=jdbc.query("""SELECT pid,query FROM pg_stat_activity
                WHERE pid=? AND ?=ANY(pg_blocking_pids(pid))""",{ rs,_ -> rs.getInt(1) to normalized(rs.getString(2)) },expectedPid,holder)
            if(waiting.singleOrNull()==(expectedPid to normalized(sql))) return
            Thread.yield()
        }
        fail<Unit>("No exact waiter worker=$expectedPid holder=$holder sql=$sql: "+jdbc.queryForList(
            "SELECT pid,query,pg_blocking_pids(pid) blockers FROM pg_stat_activity WHERE cardinality(pg_blocking_pids(pid))>0"))
    }

    private fun waitBothBlocked(holder:Int,pending:List<Future<*>>) {
        val pids=pending.map { workerPids.getValue(it) }.toSet()
        assertEquals(2,pids.size)
        val deadline=System.nanoTime()+Duration.ofSeconds(8).toNanos()
        while(System.nanoTime()<deadline) {
            jdbc.execute("SELECT pg_stat_clear_snapshot()")
            val rows=jdbc.query("""SELECT pid,query,pg_blocking_pids(pid) FROM pg_stat_activity
                WHERE cardinality(pg_blocking_pids(pid))>0""", { rs,_ ->
                val array=rs.getArray(3)
                val blockers=(array.array as Array<*>).map { (it as Number).toInt() }; array.free()
                Triple(rs.getInt(1),normalized(rs.getString(2)),blockers)
            }).associateBy { it.first }
            fun reachesRoot(pid:Int,seen:Set<Int> = emptySet()):Boolean = pid==holder ||
                (pid !in seen && rows[pid]?.third?.any { reachesRoot(it,seen+pid) }==true)
            if(pids.all { rows[it]?.second==normalized(participantSql) && reachesRoot(it) }) return
            Thread.yield()
        }
        fail<Unit>("Expected workers $pids to wait on exact participant SQL behind root $holder: "+
            jdbc.queryForList("SELECT pid,query,pg_blocking_pids(pid) blockers FROM pg_stat_activity WHERE cardinality(pg_blocking_pids(pid))>0"))
    }

    @Test
    fun `changed order parties refuse rather than following new participant locks`() {
        for(adminFirst in listOf(false,true)) for(party in listOf("customer_id","seller_id")) {
        val outsider=participant() // Below every ID in the original lock set.
        val first=if(adminFirst) admin() else 0L
        val f=agentFixture(); val a=if(adminFirst) first else admin(); val before=histories(f.order)
        val pool=Executors.newSingleThreadExecutor()
        try {
            lateinit var pending:Future<Long>
            jdbc.dataSource!!.connection.use { newPartyHolder ->
            newPartyHolder.autoCommit=false
            try {
            newPartyHolder.createStatement().use { it.execute("SET LOCAL statement_timeout='15s'") }
            newPartyHolder.prepareStatement("SELECT id FROM participant WHERE id=? FOR NO KEY UPDATE").use {
                it.setLong(1,outsider); it.executeQuery().use { rows -> assertTrue(rows.next()) }
            }
            TransactionTemplate(transactions).execute {
                val holder=jdbc.queryForObject("SELECT pg_backend_pid()",Int::class.java)!!
                jdbc.queryForList("SELECT id FROM participant WHERE id=? FOR UPDATE",minOf(a,f.seller,f.buyer))
                pending=submitWork(pool) { execute(a,f) }
                waitBlocked(holder,participantSql,pending)
                jdbc.update("UPDATE \"order\" SET $party=? WHERE id=?",outsider,f.order)
            }
            val failure=assertThrows(ExecutionException::class.java) { pending.get(12,TimeUnit.SECONDS) }
            assertEquals(AgentOrderProblem.ORDER_NOT_FOUND,(failure.cause as AgentOrderFailure).problem)
            unchanged(f,before)
            } finally { newPartyHolder.rollback() }
            }
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS)) }
        }
    }

    @Test
    fun `target type and permitted statuses are checked freshly after participant wait`() {
        for(adminFirst in listOf(false,true)) for(change in listOf("is_agent=false","status='BLOCKED'","status='DELETED'")) {
            val first=if(adminFirst) admin() else 0L; val f=agentFixture(); val a=if(adminFirst) first else admin()
            val before=histories(f.order); val pool=Executors.newSingleThreadExecutor()
            try {
                lateinit var pending:Future<Long>
                TransactionTemplate(transactions).execute {
                    val holder=jdbc.queryForObject("SELECT pg_backend_pid()",Int::class.java)!!
                    jdbc.queryForList("SELECT id FROM participant WHERE id=? FOR UPDATE",minOf(a,f.seller,f.buyer))
                    pending=submitWork(pool) { execute(a,f,"CONFIRM") }
                    waitBlocked(holder,participantSql,pending)
                    jdbc.update("UPDATE participant SET $change WHERE id=?",f.seller)
                }
                if(change=="is_agent=false") {
                    val failure=assertThrows(ExecutionException::class.java) { pending.get(12,TimeUnit.SECONDS) }
                    assertInstanceOf(AgentProfileFailure.AgentNotFound::class.java,failure.cause)
                    unchanged(f,before)
                } else {
                    assertEquals(f.order,pending.get(12,TimeUnit.SECONDS))
                    assertEquals("AWAITING_PAYMENT",status(f.order)); assertEquals(1,audits(f.order))
                    assertEquals(before+1,histories(f.order)); assertEquals(3,stock(f.product))
                }
            } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS)) }
        }
    }

    @Test
    fun `stock overflow rolls back and nonpositive legacy quantities skip stock owner`() {
        val a=admin(); val f=agentFixture(); val before=histories(f.order)
        jdbc.update("UPDATE product SET count=2147483647 WHERE id=?",f.product)
        val failure=assertThrows(LifecycleFailure::class.java) { execute(a,f) }
        assertEquals(LifecycleProblem.STOCK_CONFLICT,failure.problem)
        assertEquals("BOOKED",status(f.order)); assertEquals(before,histories(f.order)); assertEquals(0,audits(f.order))
        for(quantity in listOf(0,-1)) {
            val other=agentFixture()
            jdbc.update("UPDATE \"order\" SET count=? WHERE id=?",quantity,other.order)
            execute(a,other)
            assertEquals(3,stock(other.product)); assertEquals("CANCELLED",status(other.order)); assertEquals(1,audits(other.order))
        }
    }

    @Test
    fun `simultaneous observed transitions have one CAS winner one history stock return and audit`() {
        for((operation,start) in mapOf("CANCEL" to "BOOKED","CONFIRM" to "BOOKED",
            "CONFIRM_PREPAYMENT" to "AWAITING_PREPAYMENT_APPROVAL","SHIP" to "ASSEMBLING"))
        for(adminFirst in listOf(false,true)) {
            val a=if(adminFirst) admin() else 0L
            val f=agentFixture(); val caller=if(adminFirst) a else admin()
            jdbc.update("UPDATE \"order\" SET status=CAST(? AS order_status) WHERE id=?",start,f.order)
            val before=histories(f.order)
            val pool=Executors.newFixedThreadPool(2)
            try {
                lateinit var one:Future<Long>; lateinit var two:Future<Long>
                TransactionTemplate(transactions).execute {
                    val holder=jdbc.queryForObject("SELECT pg_backend_pid()",Int::class.java)!!
                    jdbc.queryForList("SELECT id FROM participant WHERE id=? FOR UPDATE",minOf(caller,f.seller,f.buyer))
                    one=submitWork(pool) { execute(caller,f,operation) }; two=submitWork(pool) { execute(caller,f,operation) }
                    waitBothBlocked(holder,listOf(one,two))
                    assertFalse(one.isDone); assertFalse(two.isDone)
                    assertTrue(holder>0)
                }
                val results=listOf(one,two).map { future ->
                    try { future.get(12,TimeUnit.SECONDS) to null }
                    catch(e:ExecutionException) { null to e.cause }
                }
                assertEquals(1,results.count { it.first==f.order })
                val loser=results.single { it.second!=null }.second as com.model_store.modern.ordering.lifecycle.domain.LifecycleFailure
                assertEquals(com.model_store.modern.ordering.lifecycle.domain.LifecycleProblem.STATUS_CHANGED,loser.problem)
                assertEquals(when(operation) { "CANCEL" -> "CANCELLED"; "SHIP" -> "ON_THE_WAY"; else -> "AWAITING_PAYMENT" },status(f.order))
                assertEquals(if(operation=="CANCEL") 4 else 3,stock(f.product))
                assertEquals(before+1,histories(f.order)); assertEquals(1,audits(f.order))
            } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS)) }
        }
    }

    @Test
    fun `current admin revocation before and after action respects complete ID order`() {
        for((operation,start) in mapOf("CANCEL" to "BOOKED","CONFIRM" to "BOOKED",
            "CONFIRM_PREPAYMENT" to "AWAITING_PREPAYMENT_APPROVAL","SHIP" to "ASSEMBLING"))
        for(adminFirst in listOf(false,true)) for(change in listOf("role='USER'","status='BLOCKED'","is_agent=true"))
            for(actionFirst in listOf(false,true)) {
                val a=if(adminFirst) admin() else 0L; val f=agentFixture(); val caller=if(adminFirst) a else admin()
                jdbc.update("UPDATE \"order\" SET status=CAST(? AS order_status) WHERE id=?",start,f.order)
                val before=histories(f.order); val pool=Executors.newSingleThreadExecutor()
                try {
                    lateinit var pending:Future<*>
                    TransactionTemplate(transactions).execute {
                        val holder=jdbc.queryForObject("SELECT pg_backend_pid()",Int::class.java)!!
                        if(actionFirst) {
                            execute(caller,f,operation)
                            pending=submitWork(pool) { jdbc.update("UPDATE participant SET $change WHERE id=?",caller) }
                            waitBlocked(holder,"UPDATE participant SET $change WHERE id=?",pending)
                        } else {
                            jdbc.update("UPDATE participant SET $change WHERE id=?",caller)
                            pending=submitWork(pool) { execute(caller,f,operation) }
                            waitBlocked(holder,participantSql,pending)
                        }
                        assertFalse(pending.isDone)
                    }
                    if(actionFirst) {
                        assertEquals(1,pending.get(12,TimeUnit.SECONDS))
                        assertEquals(when(operation) { "CANCEL" -> "CANCELLED"; "SHIP" -> "ON_THE_WAY"; else -> "AWAITING_PAYMENT" },status(f.order))
                        assertEquals(if(operation=="CANCEL") 4 else 3,stock(f.product)); assertEquals(1,audits(f.order))
                        assertEquals(before+1,histories(f.order))
                    } else {
                        val failure=assertThrows(ExecutionException::class.java) { pending.get(12,TimeUnit.SECONDS) }
                        assertInstanceOf(AgentProfileFailure.AdminForbidden::class.java,failure.cause)
                        assertEquals(start,status(f.order)); assertEquals(before,histories(f.order))
                        assertEquals(3,stock(f.product)); assertEquals(0,audits(f.order))
                    }
                } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS)) }
            }
    }

    @Test
    fun `media participant owner protocol and delegated cancel have no opposing lock cycle`() {
        val f=agentFixture(); val a=admin() // agent ID is below admin, reproducing the old12.3 inversion.
        val pool=Executors.newSingleThreadExecutor()
        try {
            lateinit var pending:Future<List<Long>>
            TransactionTemplate(transactions).execute {
                val holder=jdbc.queryForObject("SELECT pg_backend_pid()",Int::class.java)!!
                jdbc.queryForList("SELECT id FROM participant WHERE id=? FOR UPDATE",f.seller)
                pending=submitWork(pool) {
                    images.insertBatch(com.model_store.modern.media.image.command.domain.UploadTarget(
                        com.model_store.modern.media.storage.domain.StorageLocation.PRODUCT,f.product,a),
                        listOf(com.model_store.modern.media.image.command.domain.ImageToInsert("ao-${f.order}.jpg",10,10)))
                }
                waitBlocked(holder,mediaParticipantSql,pending)
                execute(a,f)
            }
            assertEquals(1,pending.get(12,TimeUnit.SECONDS).size)
            assertEquals("CANCELLED",status(f.order)); assertEquals(4,stock(f.product)); assertEquals(1,audits(f.order))
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS)) }
    }
}
