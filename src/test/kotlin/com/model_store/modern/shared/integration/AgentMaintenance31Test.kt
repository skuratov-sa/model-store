package com.model_store.modern.shared.integration

import com.model_store.modern.catalog.product.maintenance.application.ProductMaintenance
import com.model_store.modern.identity.participant.maintenance.application.MaintenanceParticipantLocks
import com.model_store.modern.media.image.maintenance.application.*
import com.model_store.modern.ordering.agent.application.AgentOrderAudit
import com.model_store.modern.ordering.agent.application.AgentOrderCommands
import com.model_store.modern.ordering.lifecycle.application.LifecycleStore
import com.model_store.modern.ordering.lifecycle.domain.LifecycleFailure
import com.model_store.modern.ordering.lifecycle.domain.LifecycleProblem
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.UUID
import java.util.concurrent.*

/** Actual accepted29.3 and30 beans, actual PostgreSQL locks and triggers.
 * Barriers observe a real write or storage phase; they never replace command SQL. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AgentMaintenance31Test : Integration31Fixture() {
    @Autowired lateinit var commands: AgentOrderCommands
    @Autowired lateinit var products: ProductMaintenance
    @Autowired lateinit var images: ImageMaintenance
    @MockitoSpyBean lateinit var audit: AgentOrderAudit
    @MockitoSpyBean lateinit var participants: MaintenanceParticipantLocks
    @MockitoSpyBean lateinit var lifecycle: LifecycleStore
    @AfterAll fun closeFixtures() = disposePostgresWithContext(pg)

    private fun deletedProductImage(f: Offer): Long {
        // Initial imported DELETED product is still referenced by the historical order.
        jdbc.update("UPDATE product SET status='DELETED' WHERE id=?",f.product)
        return jdbc.queryForObject("""INSERT INTO image(filename,tag,status,entity_id,uploaded_by)
            VALUES (?,'PRODUCT','ACTIVE',?,?) RETURNING id""",Long::class.java,"${UUID.randomUUID()}.jpg",f.product,f.buyer)!!
    }
    private fun snapshot(f: Offer): List<Map<String,Any?>> = jdbc.queryForList("""SELECT row_to_json(o)::text AS order_row,
        row_to_json(p)::text AS product_row,(SELECT row_to_json(s)::text FROM participant s WHERE s.id=o.seller_id) AS seller_row,
        (SELECT row_to_json(b)::text FROM participant b WHERE b.id=o.customer_id) AS buyer_row,
        (SELECT count(*) FROM order_status_history h WHERE h.order_id=o.id) AS histories,
        (SELECT count(*) FROM admin_agent_order_action a WHERE a.order_id=o.id) AS audits
        FROM "order" o JOIN product p ON p.id=o.product_id WHERE p.id=?""",f.product)

    @Test fun `actual CANCEL holds order stock audit then both cleaners wait with commit and late rollback`() {
        for (lateRollback in listOf(false,true)) {
            val (sentinel,_)=importedAgentOrder(); val foreign=snapshot(sentinel)
            val (f,order)=importedAgentOrder(); val image=deletedProductImage(f); val admin=person("ADMIN")
            val reached=LinkedBlockingQueue<Int>(); val cleanPids=LinkedBlockingQueue<Int>(); val release=CountDownLatch(1)
            doAnswer { call ->
                call.callRealMethod()
                reached.offer(jdbc.queryForObject("SELECT pg_backend_pid()",Int::class.java)!!)
                if(lateRollback) TransactionSynchronizationManager.registerSynchronization(object: TransactionSynchronization {
                    override fun beforeCommit(readOnly: Boolean) { throw IllegalStateException("fixture31 late cancellation rollback") }
                })
                assertTrue(release.await(10,TimeUnit.SECONDS)); null
            }.`when`(audit).record(admin,f.seller,order,"CANCEL")
            doAnswer { call -> cleanPids.offer(jdbc.queryForObject("SELECT pg_backend_pid()",Int::class.java)!!); call.callRealMethod() }
                .`when`(participants).lockIds(anyList())
            val pool=Executors.newFixedThreadPool(3)
            try {
                val cancel=pool.submit(Callable { commands.execute(admin,f.seller,order,"CANCEL","cancel31",null) })
                val blocker=reached.poll(8,TimeUnit.SECONDS) ?: error("Cancellation did not reach real audit insert")
                val product=pool.submit(Callable { products.cleanupDeletedBatch(f.product-1,1,f.product) })
                val first=cleanPids.poll(8,TimeUnit.SECONDS) ?: error("Product cleaner did not enter JPA")
                awaitBlocked(first,blocker,"FROM participant")
                val cleanup=pool.submit(Callable { images.cleanupOne(image) })
                val second=cleanPids.poll(8,TimeUnit.SECONDS) ?: error("Image cleaner did not enter JPA")
                awaitBlocked(second,blocker,"FROM participant")
                release.countDown()
                if(lateRollback) {
                    val failure=assertThrows(ExecutionException::class.java) { cancel.get(12,TimeUnit.SECONDS) }
                    assertTrue(generateSequence<Throwable>(failure) {it.cause}.any {it.message=="fixture31 late cancellation rollback"})
                } else assertEquals(order,cancel.get(12,TimeUnit.SECONDS))
                val batch=product.get(12,TimeUnit.SECONDS); assertEquals(1,batch.selected); assertEquals(0,batch.deleted); assertEquals(0,batch.failed)
                assertEquals(ImageCleanupOutcome.DELETED,cleanup.get(12,TimeUnit.SECONDS))
                assertEquals(if(lateRollback) "BOOKED" else "CANCELLED",status(order))
                assertEquals(if(lateRollback) 3 else 4,stock(f.product))
                assertEquals(if(lateRollback) 1 else 2,history(order)); assertEquals(if(lateRollback) 0 else 1,audits(order))
                if(lateRollback) {
                    // Remove the observation/failure hook before a real retry of the same command.
                    reset(audit)
                    assertEquals(order,commands.execute(admin,f.seller,order,"CANCEL","retry31",null))
                }
                assertEquals(4,stock(f.product)); assertEquals(2,history(order)); assertEquals(1,audits(order))
                val retained=products.cleanupDeletedBatch(f.product-1,1,f.product)
                assertEquals(1,retained.selected); assertEquals(0,retained.deleted); assertEquals(0,retained.failed)
                assertEquals(ImageCleanupOutcome.SKIPPED,images.cleanupOne(image))
                val replay=assertThrows(LifecycleFailure::class.java) { commands.execute(admin,f.seller,order,"CANCEL","replay",null) }
                assertEquals(LifecycleProblem.INVALID_TRANSITION,replay.problem)
                assertEquals(4,stock(f.product)); assertEquals(2,history(order)); assertEquals(1,audits(order))
                assertEquals(foreign,snapshot(sentinel)); assertEquals(0,number("SELECT count(*) FROM image WHERE id=$image"))
                println("31 cancel-first rollback=$lateRollback blocker=$blocker cleaners=$first,$second order=$order history=2 audit=1 stock=4")
            } finally {
                release.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(15,TimeUnit.SECONDS)); reset(audit,participants,objects)
            }
        }
    }

    @Test fun `image cleaner holds real participant product image locks before CANCEL with storage retry`() {
        for (storageFailure in listOf(false,true)) {
            val (sentinel,_)=importedAgentOrder(); val foreign=snapshot(sentinel)
            val (f,order)=importedAgentOrder(); val image=deletedProductImage(f); val admin=person("ADMIN")
            val storage=LinkedBlockingQueue<Int>(); val cancelPids=LinkedBlockingQueue<Int>(); val release=CountDownLatch(1)
            doAnswer { call -> cancelPids.offer(jdbc.queryForObject("SELECT pg_backend_pid()",Int::class.java)!!); call.callRealMethod() }
                .`when`(lifecycle).lockParticipant(anyLong())
            doAnswer {
                storage.offer(jdbc.queryForObject("SELECT pg_backend_pid()",Int::class.java)!!)
                // A separate physical connection must already see the durable tombstone.
                jdbc.dataSource!!.connection.use { c -> c.prepareStatement("SELECT status::text FROM image WHERE id=?").use { s ->
                    s.setLong(1,image); s.executeQuery().use {r -> assertTrue(r.next()); assertEquals("DELETE",r.getString(1))}
                }}
                assertTrue(release.await(10,TimeUnit.SECONDS))
                if(storageFailure) throw IllegalStateException("fixture31 ambiguous storage failure")
                null
            }.`when`(objects).deleteVariants("PRODUCT",jdbc.queryForObject("SELECT filename FROM image WHERE id=?",String::class.java,image)!!)
            val pool=Executors.newFixedThreadPool(2)
            try {
                val cleanup=pool.submit(Callable { images.cleanupOne(image) })
                val blocker=storage.poll(8,TimeUnit.SECONDS) ?: error("Cleaner did not reach storage phase")
                val cancel=pool.submit(Callable { commands.execute(admin,f.seller,order,"CANCEL","cleaner-first",null) })
                val waiter=cancelPids.poll(8,TimeUnit.SECONDS) ?: error("Actual cancellation worker never reached participant lock")
                awaitBlocked(waiter,blocker,"FROM participant")
                release.countDown()
                if(storageFailure) {
                    val failure=assertThrows(ExecutionException::class.java) { cleanup.get(12,TimeUnit.SECONDS) }
                    assertTrue(generateSequence<Throwable>(failure) {it.cause}.any {it.message=="fixture31 ambiguous storage failure"})
                    assertEquals("DELETE",jdbc.queryForObject("SELECT status::text FROM image WHERE id=?",String::class.java,image))
                } else assertEquals(ImageCleanupOutcome.DELETED,cleanup.get(12,TimeUnit.SECONDS))
                assertEquals(order,cancel.get(12,TimeUnit.SECONDS))
                assertEquals("CANCELLED",status(order)); assertEquals(4,stock(f.product)); assertEquals(2,history(order)); assertEquals(1,audits(order))
                reset(objects)
                assertEquals(if(storageFailure) ImageCleanupOutcome.DELETED else ImageCleanupOutcome.SKIPPED,images.cleanupOne(image))
                val retained=products.cleanupDeletedBatch(f.product-1,1,f.product)
                assertEquals(1,retained.selected); assertEquals(0,retained.deleted); assertEquals(0,retained.failed)
                assertEquals(foreign,snapshot(sentinel)); assertEquals(4,stock(f.product)); assertEquals(2,history(order)); assertEquals(1,audits(order))
                println("31 cleaner-first storageFailure=$storageFailure blocker=$blocker cancelWaiter=$waiter order=$order history=2 audit=1 stock=4")
            } finally { release.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(15,TimeUnit.SECONDS)); reset(objects,lifecycle) }
        }
    }

    private fun awaitBlocked(waiter: Int,blocker: Int,sql: String) {
        val actual=awaitAnyBlocked(blocker,sql,waiter); assertEquals(waiter,actual)
    }

    @Test fun `ORDER image cleanup refuses status changed by actual CANCEL then retries without losing proof`() {
        val (f,order)=importedAgentOrder(); val admin=person("ADMIN")
        val stale=jdbc.queryForObject("""INSERT INTO image(filename,tag,status,entity_id,uploaded_by,created_at)
            VALUES (?,'ORDER','TEMPORARY',?,?,now()-interval '2 days') RETURNING id""",Long::class.java,"${UUID.randomUUID()}.jpg",order,f.buyer)!!
        val proof=jdbc.queryForObject("""INSERT INTO image(filename,tag,status,entity_id,uploaded_by,created_at)
            VALUES (?,'ORDER','TEMPORARY',?,?,now()-interval '2 days') RETURNING id""",Long::class.java,"${UUID.randomUUID()}.jpg",order,f.buyer)!!
        // Imported evidence reference is an initial fixture, not a simulated transition.
        jdbc.update("UPDATE \"order\" SET image_payment_proof_id=? WHERE id=?",proof,order)
        val reached=LinkedBlockingQueue<Int>(); val captured=LinkedBlockingQueue<Int>(); val release=CountDownLatch(1)
        doAnswer { call -> call.callRealMethod(); reached.offer(jdbc.queryForObject("SELECT pg_backend_pid()",Int::class.java)!!)
            assertTrue(release.await(10,TimeUnit.SECONDS)); null
        }.`when`(audit).record(admin,f.seller,order,"CANCEL")
        doAnswer { call -> captured.offer(jdbc.queryForObject("SELECT pg_backend_pid()",Int::class.java)!!); call.callRealMethod() }
            .`when`(participants).lockIds(anyList())
        val pool=Executors.newFixedThreadPool(2)
        try {
            val cancel=pool.submit(Callable {commands.execute(admin,f.seller,order,"CANCEL","order-image-race",null)})
            val blocker=reached.poll(8,TimeUnit.SECONDS) ?: error("Cancellation never reached audit")
            val cleanup=pool.submit(Callable {images.cleanupOne(stale)})
            val waiter=captured.poll(8,TimeUnit.SECONDS) ?: error("ORDER cleaner never reached locks")
            awaitBlocked(waiter,blocker,"FROM participant"); release.countDown()
            assertEquals(order,cancel.get(12,TimeUnit.SECONDS)); assertEquals(ImageCleanupOutcome.CONFLICT,cleanup.get(12,TimeUnit.SECONDS))
            assertEquals("TEMPORARY",jdbc.queryForObject("SELECT status::text FROM image WHERE id=?",String::class.java,stale))
            verifyNoInteractions(objects)
            assertEquals(ImageCleanupOutcome.DELETED,images.cleanupOne(stale))
            assertEquals(ImageCleanupOutcome.SKIPPED,images.cleanupOne(proof))
            assertEquals(proof,jdbc.queryForObject("SELECT image_payment_proof_id FROM \"order\" WHERE id=?",Long::class.java,order))
            assertEquals(1,number("SELECT count(*) FROM image WHERE id=$proof"))
            assertEquals(4,stock(f.product)); assertEquals(2,history(order)); assertEquals(1,audits(order))
            println("31 ORDER-cleaner conflict blocker=$blocker waiter=$waiter fresh retry deleted=$stale retainedProof=$proof order=$order history=2 audit=1 stock=4")
        } finally {release.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(15,TimeUnit.SECONDS)); reset(audit,participants,objects)}
    }
    private fun awaitAnyBlocked(blocker: Int,sql: String,waiter: Int): Int {
        val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8)
        while(System.nanoTime()<end) {
            jdbc.execute("SELECT pg_stat_clear_snapshot()")
            val rows=jdbc.queryForList("SELECT pid,query FROM pg_stat_activity WHERE ?=ANY(pg_blocking_pids(pid))",blocker)
            val row=rows.firstOrNull {(it["pid"] as Number).toInt()==waiter && it["query"].toString().contains(sql) &&
                it["query"].toString().trim().replace(Regex("\\s+")," ")=="SELECT id FROM participant WHERE id=\$1 FOR UPDATE"}
            if(row!=null) {
                println("31 actual contention blocker=$blocker waiter=${row["pid"]} SQL=${row["query"]}")
                return (row["pid"] as Number).toInt()
            }
            Thread.yield()
        }
        error("No actual waiter=$waiter behind backend=$blocker on $sql")
    }
    companion object {
        private val pg=EmbeddedPostgres.start()
        @JvmStatic @DynamicPropertySource fun database(registry: DynamicPropertyRegistry) = properties(registry,pg)
    }
}
