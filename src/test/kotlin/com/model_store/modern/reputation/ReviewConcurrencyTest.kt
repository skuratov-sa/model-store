package com.model_store.modern.reputation

import com.model_store.modern.reputation.application.*
import com.model_store.modern.reputation.domain.*
import com.model_store.modern.ordering.lifecycle.application.*
import com.model_store.modern.shared.domain.Actor
import com.model_store.modern.identity.participant.application.ParticipantStore
import com.model_store.modern.identity.participant.application.ParticipantProfileRead
import com.model_store.modern.catalog.product.detail.application.ProductDetailReadPort
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import java.sql.Connection
import java.util.concurrent.*

@ActiveProfiles("modern")
class ReviewConcurrencyTest : ReviewFixture() {
    @Autowired lateinit var reviews: Reviews
    @Autowired lateinit var store: ReviewStore
    @Autowired lateinit var read: ReviewRead
    @Autowired lateinit var orders: ReviewOrderEligibility
    @Autowired lateinit var lifecycleLocks: LifecycleStore
    @Autowired lateinit var lifecycle: OrderLifecycle
    @Autowired lateinit var transactions: PlatformTransactionManager
    @Autowired lateinit var participants: ParticipantStore
    @Autowired lateinit var profiles: ParticipantProfileRead
    @Autowired lateinit var products: ProductDetailReadPort
    @Autowired lateinit var media: com.model_store.modern.media.image.command.application.ImageCommandRows
    fun actor(id: Long) = Actor(id,"review","USER")
    fun <T> tx(block: () -> T): T = TransactionTemplate(transactions).execute { block() }!!

    @Test fun `two seller reviews wait at participant frontier and preserve complete aggregate`() {
        for(sellerFirst in listOf(true,false)) {
            val seller=if(sellerFirst) participant() else null
            val buyers=listOf(participant(),participant())
            val sellerId=seller ?: participant()
            val a=fixture(sellerId,buyers[0]);val b=fixture(sellerId,buyers[1])
            heldParticipant(sellerId) { holder ->
                workers(holder,{ reviews.create(actor(a.buyer),a.order,1,null) }, { reviews.create(actor(b.buyer),b.order,5,null) }) { first,second ->
                    awaitBlocked(holder,listOf(first.pid(),second.pid()))
                    holder.commit()
                    assertNotEquals(first.get(20,TimeUnit.SECONDS),second.get(20,TimeUnit.SECONDS))
                }
            }
            assertRating(sellerId,"3.00",2)
            assertEquals(2,count("SELECT count(*) FROM review WHERE seller_id=$sellerId"))
        }
    }

    @Test fun `duplicate concurrent order has one winner and exact loser`() {
        val f=fixture()
        fun outcome(worker: Running<Long>): Any = try { worker.get(20,TimeUnit.SECONDS) }
            catch(e: ExecutionException) { (e.cause as? ReviewFailure)?.problem ?: throw e }
        heldParticipant(f.seller) { holder ->
            workers(holder,{ reviews.create(actor(f.buyer),f.order,5,null) }, { reviews.create(actor(f.buyer),f.order,5,null) }) { first,second ->
                awaitBlocked(holder,listOf(first.pid(),second.pid()));holder.commit()
                // Observe the typed denial after its whole transaction rolled back.
                // Catching it inside a joined outer JPA would instead mask it with UnexpectedRollback.
                val results=listOf(outcome(first),outcome(second))
                assertEquals(1,results.count { it is Long });assertEquals(1,results.count { it==ReviewProblem.ALREADY_EXISTS })
            }
        }
        assertRating(f.seller,"5.00",1)
    }

    @Test fun `create and delete serialize before trigger and keep survivor rating`() {
        val seller=participant();val a=fixture(seller);val b=fixture(seller)
        val id=reviews.create(actor(a.buyer),a.order,1,null)
        heldParticipant(seller) { holder ->
            workers(holder,{ reviews.delete(actor(a.buyer),id);0L }, { reviews.create(actor(b.buyer),b.order,5,null) }) { first,second ->
                awaitBlocked(holder,listOf(first.pid(),second.pid()));holder.commit();first.get(20,TimeUnit.SECONDS);second.get(20,TimeUnit.SECONDS)
            }
        }
        assertRating(seller,"5.00",1)
        assertNull(store.find(id));assertEquals(b.order,jdbc.queryForObject("SELECT order_id FROM review WHERE seller_id=?",Long::class.java,seller))
    }

    @Test fun `accepted lifecycle participant then order path never reverses review FK locks`() {
        val f=fixture(state="ON_THE_WAY")
        // The accepted path holds its first participant, then must be able to get the
        // second participant and order while the real review command is waiting.
        val first=minOf(f.buyer,f.seller);val second=maxOf(f.buyer,f.seller)
        val executor=Executors.newSingleThreadExecutor()
        lateinit var future: Running<Long>
        try {
            tx {
                lifecycleLocks.lockParticipant(first)
                val pid=jdbc.queryForObject("SELECT pg_backend_pid()",Int::class.java)!!
                future=start(executor) { reviews.create(actor(f.buyer),f.order,4,null) }
                awaitBlockedPid(pid,listOf(future.pid()))
                jdbc.execute("SET LOCAL lock_timeout='1s'")
                lifecycleLocks.lockParticipant(second)
                assertNotNull(lifecycleLocks.lockOrder(f.order))
                val images=media.insertBatch(
                    com.model_store.modern.media.image.command.domain.UploadTarget(
                        com.model_store.modern.media.storage.domain.StorageLocation.ORDER,f.order,f.buyer),
                    listOf(com.model_store.modern.media.image.command.domain.ImageToInsert("review-audit-order.jpg",10,10)))
                assertEquals(1,images.size)
                lifecycle.execute(actor(f.buyer),f.order,com.model_store.modern.ordering.lifecycle.domain.OrderAction.COMPLETE)
                // If review locked order before waiting for participant, this would fail.
                assertFalse(future.future.isDone)
            }
            assertTrue(future.get(20,TimeUnit.SECONDS)>0)
            executor.shutdown();assertTrue(executor.awaitTermination(20,TimeUnit.SECONDS))
        } finally { executor.shutdownNow() }
        assertRating(f.seller,"4.00",1)
    }

    @Test fun `rollback preserves rating and guard rejects missing readonly and JDBC transactions`() {
        val f=fixture()
        assertThrows(ReviewTransactionRequired::class.java) { store.lockSeller(f.seller) }
        assertThrows(ReviewTransactionRequired::class.java) { store.insert(NewReview(f.order,f.buyer,f.seller,f.product,5,null)) }
        assertThrows(LifecycleTransactionRequired::class.java) { orders.lockForReview(orders.observe(f.order)!!) }
        assertThrows(ReviewTransactionRequired::class.java) {
            TransactionTemplate(transactions).apply { isReadOnly=true }.execute { reviews.create(actor(f.buyer),f.order,5,null) }
        }
        assertThrows(ReviewTransactionRequired::class.java) {
            TransactionTemplate(DataSourceTransactionManager(jdbc.dataSource!!)).execute { store.lockSeller(f.seller) }
        }
        val foreign=org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean().apply {
            setDataSource(jdbc.dataSource!!)
            setPackagesToScan("com.model_store.modern.reputation")
            persistenceUnitName="reputation-foreign"
            jpaVendorAdapter=org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter()
            setJpaPropertyMap(mapOf("hibernate.hbm2ddl.auto" to "none"));afterPropertiesSet()
        }
        try {
            val foreignTx=TransactionTemplate(org.springframework.orm.jpa.JpaTransactionManager(foreign.`object`!!))
            assertThrows(ReviewTransactionRequired::class.java) { foreignTx.execute { store.lockSeller(f.seller) } }
            assertThrows(LifecycleTransactionRequired::class.java) { foreignTx.execute { orders.lockForReview(orders.observe(f.order)!!) } }
        } finally { foreign.destroy() }
        assertThrows(IllegalStateException::class.java) {
            tx { reviews.create(actor(f.buyer),f.order,1,null);assertRating(f.seller,"1.00",1);error("rollback") }
        }
        assertRating(f.seller,null,0);assertEquals(0,count("SELECT count(*) FROM review WHERE order_id=${f.order}"))
        val id=reviews.create(actor(f.buyer),f.order,4,null)
        assertThrows(IllegalStateException::class.java) { tx { reviews.delete(actor(f.buyer),id);assertRating(f.seller,null,0);error("rollback") } }
        assertRating(f.seller,"4.00",1);assertNotNull(store.find(id))
        tx {
            val before=jdbc.queryForObject("SELECT txid_current()",Long::class.java)
            reviews.delete(actor(f.buyer),id)
            assertEquals(before,jdbc.queryForObject("SELECT txid_current()",Long::class.java))
        }
    }

    @Test fun `owner scalar port rechecks changed frontier and includes historic parties for delete`() {
        val f=fixture();val observed=orders.observe(f.order)!!;val replacement=participant()
        val replacementCustomer=participant()
        jdbc.update("UPDATE \"order\" SET seller_id=?,customer_id=? WHERE id=?",replacement,replacementCustomer,f.order)
        heldParticipant(replacement) {
            tx {
                jdbc.execute("SET LOCAL lock_timeout='500ms'")
                assertNull(orders.lockForReview(observed)) // Must not follow/lock held replacement.
            }
        }
        // A historical review can reference another seller/reviewer than the current order.
        val id=jdbc.queryForObject("INSERT INTO review(order_id,reviewer_id,seller_id,product_id,rating) VALUES (?,?,?,?,1) RETURNING id",
            Long::class.java,f.order,f.buyer,f.seller,f.product)!!
        // Both historical parties differ from current order parties; reviewer must
        // be acquired before order, not belatedly by a review FK/trigger operation.
        heldParticipant(f.buyer) { holder ->
            val executor=Executors.newSingleThreadExecutor()
            try {
                val deletion=start(executor) { reviews.delete(actor(f.buyer),id) }
                awaitBlocked(holder,listOf(deletion.pid()))
                holder.createStatement().use { it.execute("SELECT id FROM \"order\" WHERE id=${f.order} FOR UPDATE NOWAIT") }
                holder.commit();deletion.get(20,TimeUnit.SECONDS)
            } finally { executor.shutdownNow() }
        }
        assertNull(store.find(id));assertRating(f.seller,null,0)
    }

    @Test fun `rating profile and product readers observe create delete nullable rows and empty aggregate`() {
        val f=fixture()
        fun check(average: Float?, count: Int?) = tx {
            val profile=profiles.fullProfile(participants.find(f.seller)!!)
            assertEquals(average,profile.averageRating);assertEquals(count,profile.totalReviews)
            val card=products.publicCard(f.product)!!
            assertEquals(average ?: 0f,card.sellerRating);assertEquals(count ?: 0,card.totalReviews)
        }
        check(null,null)
        val id=reviews.create(actor(f.buyer),f.order,5,null);check(5f,1)
        assertEquals(1,read.forProduct(f.product).size)
        jdbc.update("UPDATE participant SET full_name=NULL WHERE id=?",f.buyer)
        assertEquals("unknown",read.forProduct(f.product).single().reviewerName)
        // Existing schema permits NULL ratings: AVG ignores null, COUNT still counts it.
        tx {
            jdbc.update("UPDATE review SET rating=NULL WHERE id=?",id)
            assertEquals(java.math.BigDecimal("0.00"),read.rating(f.seller)!!.averageRating)
            assertEquals(1,read.rating(f.seller)!!.totalReviews)
        }
        check(0f,1)
        reviews.delete(actor(f.buyer),id);check(null,null)
    }

    private fun heldParticipant(id: Long, block: (Connection) -> Unit) {
        jdbc.dataSource!!.connection.use { holder ->
            holder.autoCommit=false
            holder.createStatement().use { it.execute("SET LOCAL lock_timeout='5s'");it.execute("SELECT id FROM participant WHERE id=$id FOR UPDATE") }
            try { block(holder) } finally { holder.rollback() }
        }
    }
    private fun awaitBlocked(holder: Connection, expectedPids: List<Int>) {
        val pid=holder.createStatement().use { statement -> statement.executeQuery("SELECT pg_backend_pid()").use { it.next();it.getInt(1) } }
        awaitBlockedPid(pid,expectedPids)
    }
    private fun awaitBlockedPid(pid: Int, expectedPids: List<Int>) {
        assertEquals(expectedPids.size,expectedPids.toSet().size,"Distinct worker backends required")
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15)
        while(System.nanoTime()<deadline) {
            jdbc.execute("SELECT pg_stat_clear_snapshot()")
            val waiting=jdbc.query("""WITH RECURSIVE waiting(pid) AS (
                SELECT pid FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid))
                UNION SELECT a.pid FROM pg_stat_activity a JOIN waiting w ON w.pid = ANY(pg_blocking_pids(a.pid))
            ) SELECT pid,query FROM pg_stat_activity WHERE pid IN (SELECT pid FROM waiting)""",
                { rs, _ -> rs.getInt("pid") to rs.getString("query").replace(Regex("\\s+")," ").trim() },pid).toMap()
            if(expectedPids.all { waiting[it]=="SELECT id FROM participant WHERE id=\$1 FOR UPDATE" }) {
                println("REVIEW_LOCK_PROOF blocker=$pid workers=${expectedPids.associateWith { waiting[it] }}")
                return
            }
            Thread.sleep(20)
        }
        fail<Unit>("Expected worker PIDs $expectedPids at participant SQL, blocked by backend $pid")
    }
    private data class Running<T>(val backend: CompletableFuture<Int>,val future: Future<T>) {
        fun pid() = backend.get(10,TimeUnit.SECONDS)
        fun get(timeout: Long,unit: TimeUnit): T = future.get(timeout,unit)
    }
    private fun <T> start(executor: ExecutorService,block: () -> T): Running<T> {
        val backend=CompletableFuture<Int>()
        val future=executor.submit(Callable { tx {
            backend.complete(jdbc.queryForObject("SELECT pg_backend_pid()",Int::class.java)!!)
            block()
        } })
        return Running(backend,future)
    }
    private fun <A,B> workers(holder: Connection,a: () -> A,b: () -> B, block: (Running<A>,Running<B>) -> Unit) {
        val executor=Executors.newFixedThreadPool(2)
        try { block(start(executor,a),start(executor,b)) }
        finally { holder.rollback();executor.shutdownNow();assertTrue(executor.awaitTermination(25,TimeUnit.SECONDS)) }
    }
}
