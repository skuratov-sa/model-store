package com.model_store.modern.catalog.product.agent

import com.amazonaws.services.s3.AmazonS3
import com.model_store.modern.catalog.product.agent.application.*
import com.model_store.modern.catalog.product.command.application.*
import com.model_store.modern.catalog.product.command.domain.ProductChanges
import com.model_store.modern.catalog.product.command.domain.ProductState
import com.model_store.modern.catalog.giveaway.admin.application.*
import com.model_store.modern.catalog.giveaway.admin.domain.*
import com.model_store.modern.catalog.product.reservation.application.*
import com.model_store.modern.identity.participant.application.*
import com.model_store.modern.identity.agent.application.ActiveAgentLock
import com.model_store.modern.identity.verification.application.VerificationMail
import com.model_store.modern.media.image.command.application.ImageCommandRows
import com.model_store.modern.media.image.command.domain.*
import com.model_store.modern.media.storage.domain.StorageLocation
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.SQLException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicReference

@SpringBootTest(properties=["app.public-key-path=keys/test_public_key.pem","app.private-key-path=keys/test_private_key.pem"])
@ActiveProfiles("modern")
class AgentProductTransactionsTest {
    @MockitoBean lateinit var s3:AmazonS3
    @MockitoBean lateinit var mail:VerificationMail
    @MockitoBean lateinit var decoder:JwtDecoder
    @Autowired lateinit var commands:AgentProducts
    @Autowired lateinit var ordinary:ProductCommands
    @Autowired lateinit var giveaways:AdminGiveaways
    @Autowired lateinit var reservation:ProductReservation
    @Autowired lateinit var media:ImageCommandRows
    @Autowired lateinit var jdbc:JdbcTemplate
    @Autowired lateinit var manager:PlatformTransactionManager
    @Autowired lateinit var activeAgent:ActiveAgentLock
    private val tx get()=TransactionTemplate(manager)
    private val patch get()=AgentProductPatch(name="item-${UUID.randomUUID()}",price=10f,currency="RUB",externalUrl="https://example.test/item")
    private fun person(role:String="USER",agent:Boolean=false):Long {
        val login="tx29-${UUID.randomUUID()}"
        return jdbc.queryForObject("""INSERT INTO participant(login,mail,password,role,status,is_agent)
            VALUES (?,?,'hash',?::participant_role,'ACTIVE',?) RETURNING id""",Long::class.java,login,"$login@example.test",role,agent)!!
    }
    private fun admin(id:Long)=AgentProductCaller(id,"ADMIN",false)
    private fun agent(id:Long)=AgentProductCaller(id,"USER",true)
    private fun create(owner:Long)=commands.create(agent(owner),patch)
    private fun image(owner:Long,status:String="TEMPORARY",tag:String="PRODUCT",entity:Long?=null):Long =
        jdbc.queryForObject("""INSERT INTO image(filename,tag,status,uploaded_by,entity_id)
            VALUES (?,?::image_tag,?::image_status,?,?) RETURNING id""",Long::class.java,"${UUID.randomUUID()}.jpg",tag,status,owner,entity)!!
    private fun category():Long=jdbc.queryForObject("INSERT INTO category(name) VALUES (?) RETURNING id",Long::class.java,"category-${UUID.randomUUID()}")!!
    private fun schedule()=AgentProductSchedule(true,"https://t.me/agent",Instant.parse("2091-01-01T00:00:00Z"),
        Instant.parse("2091-01-02T00:00:00Z"),1,"Rules","Home")
    @BeforeEach fun clearWindows() { jdbc.update("UPDATE product SET giveaway_enabled=false") }

    private fun pool(action:(ExecutorService)->Unit) {
        val executor=Executors.newFixedThreadPool(2)
        try { action(executor) } finally { executor.shutdownNow();assertTrue(executor.awaitTermination(10,TimeUnit.SECONDS)) }
    }
    private fun awaitBlocked(pid:Int,blockingPid:Int) {
        val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(8)
        while(System.nanoTime()<until) {
            val waiting=jdbc.queryForObject("SELECT ?=ANY(pg_blocking_pids(?))",Boolean::class.java,blockingPid,pid)
            if(waiting==true)return
            Thread.sleep(10)
        }
        fail<Unit>("Backend $pid did not block behind $blockingPid")
    }
    private fun pid()=jdbc.queryForObject("SELECT pg_backend_pid()",Int::class.java)!!
    private fun timeout() { jdbc.execute("SET LOCAL lock_timeout='8s'");jdbc.execute("SET LOCAL statement_timeout='12s'") }
    private fun state(error:Throwable?):String? {
        var current=error
        while(current!=null) { if(current is SQLException)return current.sqlState;current=current.cause }
        return null
    }

    @Test fun `late category error rolls back creation update settings and media claim`() {
        val owner=person(agent=true);val caller=admin(person("ADMIN"));val img=image(owner);val cat=category()
        val proposed=patch.copy(imageIds=listOf(img),categoryIds=listOf(cat,Long.MAX_VALUE))
        assertThrows(org.springframework.dao.DataIntegrityViolationException::class.java) { commands.create(agent(owner),proposed) }
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM product WHERE participant_id=?",Int::class.java,owner))
        assertEquals("TEMPORARY",jdbc.queryForObject("SELECT status::text FROM image WHERE id=?",String::class.java,img))
        assertNull(jdbc.queryForObject("SELECT entity_id FROM image WHERE id=?",Long::class.java,img))
        val id=create(owner)
        assertThrows(org.springframework.dao.DataIntegrityViolationException::class.java) {
            commands.update(caller,owner,id,proposed.copy(availability="GIVEAWAY",giveaway=schedule()))
        }
        val p=commands.product(caller,id)
        assertEquals("EXTERNAL_PRODUCT",p.availability);assertFalse(p.giveawayEnabled);assertNull(p.giveawayStartAt)
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM product_category WHERE product_id=?",Int::class.java,id))
        assertEquals("TEMPORARY",jdbc.queryForObject("SELECT status::text FROM image WHERE id=?",String::class.java,img))
        assertThrows(IllegalStateException::class.java) { tx.executeWithoutResult {
            commands.update(caller,owner,id,AgentProductPatch(name="must roll back",imageIds=listOf(img),categoryIds=listOf(cat)))
            error("outer failure")
        } }
        assertNotEquals("must roll back",commands.product(caller,id).name)
        assertEquals("TEMPORARY",jdbc.queryForObject("SELECT status::text FROM image WHERE id=?",String::class.java,img))
    }

    @Test fun `media ownership tags duplicates limits and empty category replacement are atomic`() {
        val owner=person(agent=true);val other=person(agent=true);val caller=admin(person("ADMIN"));val id=create(owner)
        val mine=image(owner);val foreign=image(other);val order=image(owner,tag="ORDER");val participant=image(owner,tag="PARTICIPANT")
        listOf(foreign,order,participant,image(owner,status="DELETE")).forEach { bad ->
            assertThrows(ProductCommandFailure::class.java) { commands.update(caller,owner,id,AgentProductPatch(name="stolen",imageIds=listOf(mine,bad))) }
            assertNotEquals("stolen",commands.product(caller,id).name)
            assertEquals("TEMPORARY",jdbc.queryForObject("SELECT status::text FROM image WHERE id=?",String::class.java,mine))
        }
        assertThrows(ProductCommandFailure::class.java) { commands.update(caller,owner,id,AgentProductPatch(imageIds=listOf(mine,mine))) }
        val cat=category()
        commands.update(caller,owner,id,AgentProductPatch(imageIds=listOf(mine),categoryIds=listOf(cat)))
        commands.update(caller,owner,id,AgentProductPatch(imageIds=listOf(mine),categoryIds=null))
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM product_category WHERE product_id=?",Int::class.java,id))
        commands.update(caller,owner,id,AgentProductPatch(imageIds=emptyList(),categoryIds=emptyList()))
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM product_category WHERE product_id=?",Int::class.java,id))
        assertEquals("ACTIVE",jdbc.queryForObject("SELECT status::text FROM image WHERE id=?",String::class.java,mine))
        val many=(1..31).map { image(owner) }
        assertThrows(ProductCommandFailure::class.java) { commands.update(caller,owner,id,AgentProductPatch(imageIds=many)) }
        assertEquals(31,jdbc.queryForObject("SELECT count(*) FROM image WHERE id IN (${many.joinToString(",")}) AND status='TEMPORARY'",Int::class.java))
    }

    @Test fun `foreign owner claim cannot move already attached images`() {
        val one=person(agent=true);val two=person(agent=true);val a=create(one);val b=create(two)
        val image=image(one);val caller=admin(person("ADMIN"))
        commands.update(caller,one,a,AgentProductPatch(imageIds=listOf(image)))
        val failure=assertThrows(ProductCommandFailure::class.java) {
            commands.update(caller,two,b,AgentProductPatch(imageIds=listOf(image)))
        }
        assertEquals(FailureKind.FORBIDDEN,failure.kind);assertEquals("ACCESS_DENIED",failure.code)
        assertEquals(a,jdbc.queryForObject("SELECT entity_id FROM image WHERE id=?",Long::class.java,image))
    }

    @Test fun `concurrent claims on two products retain exactly one association`() {
        val owner=person(agent=true);val caller=admin(person("ADMIN"));val a=create(owner);val b=create(owner);val image=image(owner)
        pool { executor ->
            val locked=CountDownLatch(1);val release=CountDownLatch(1);val pid=ArrayBlockingQueue<Int>(1);val blocker=ArrayBlockingQueue<Int>(1)
            val first=executor.submit {tx.executeWithoutResult {
                blocker.put(pid())
                commands.update(caller,owner,a,AgentProductPatch(imageIds=listOf(image)));locked.countDown()
                assertTrue(release.await(10,TimeUnit.SECONDS))
            }}
            assertTrue(locked.await(10,TimeUnit.SECONDS))
            val second=executor.submit<Throwable?> {try {tx.executeWithoutResult {
                timeout();pid.put(pid());commands.update(caller,owner,b,AgentProductPatch(name="loser",imageIds=listOf(image)))
            };null}catch(e:Throwable){e}}
            try {awaitBlocked(pid.poll(10,TimeUnit.SECONDS)!!,blocker.poll(10,TimeUnit.SECONDS)!!);release.countDown()
                first.get(15,TimeUnit.SECONDS)
                val failure=second.get(15,TimeUnit.SECONDS)
                assertTrue(failure is ProductCommandFailure,failure?.stackTraceToString())
                assertEquals(FailureKind.CONFLICT,(failure as ProductCommandFailure).kind)
                assertEquals("INVALID_REQUEST",failure.code)
                assertEquals(a,jdbc.queryForObject("SELECT entity_id FROM image WHERE id=?",Long::class.java,image))
                assertNotEquals("loser",commands.product(caller,b).name)
            } finally {release.countDown()}
        }
    }

    @Test fun `ordinary command remains forbidden for agents and foreign target cannot be substituted`() {
        val owner=person(agent=true);val caller=admin(person("ADMIN"));val id=create(owner)
        val forbidden=assertThrows(ProductCommandFailure::class.java) { ordinary.update(id,owner,"ADMIN",ProductChanges(name="wrong")) }
        assertEquals(FailureKind.FORBIDDEN,forbidden.kind);assertEquals("ACCESS_DENIED",forbidden.code)
        val missing=assertThrows(ProductCommandFailure::class.java) { commands.update(caller,person(agent=true),id,AgentProductPatch(name="wrong")) }
        assertEquals(FailureKind.NOT_FOUND,missing.kind);assertEquals("PRODUCT_NOT_FOUND",missing.code)
        assertThrows(AgentProfileFailure.AgentNotFound::class.java) { commands.update(caller,person(),id,AgentProductPatch(name="wrong")) }
        assertThrows(AgentProductDenied::class.java) { commands.update(agent(owner),owner,id,AgentProductPatch(name="wrong")) }
        assertNotEquals("wrong",commands.product(caller,id).name)
    }

    @Test fun `parallel partial updates preserve both values after product row serialization`() {
        val owner=person(agent=true);val caller=admin(person("ADMIN"));val id=create(owner)
        pool { executor ->
            val locked=CountDownLatch(1);val release=CountDownLatch(1);val pid=ArrayBlockingQueue<Int>(1);val blocker=ArrayBlockingQueue<Int>(1)
            val first=executor.submit { tx.executeWithoutResult {
                blocker.put(pid())
                commands.update(caller,owner,id,AgentProductPatch(name="first"));locked.countDown()
                assertTrue(release.await(10,TimeUnit.SECONDS))
            } }
            assertTrue(locked.await(10,TimeUnit.SECONDS))
            val second=executor.submit { tx.executeWithoutResult {
                timeout();pid.put(pid());commands.update(caller,owner,id,AgentProductPatch(description="second"))
            } }
            try { awaitBlocked(pid.poll(10,TimeUnit.SECONDS)!!,blocker.poll(10,TimeUnit.SECONDS)!!);release.countDown()
                first.get(15,TimeUnit.SECONDS);second.get(15,TimeUnit.SECONDS)
                val result=commands.product(caller,id);assertEquals("first",result.name);assertEquals("second",result.description)
            } finally { release.countDown() }
        }
    }

    @Test fun `current admin role and target agent flag are checked after waiting`() {
        listOf(false,true).forEach { revokeTarget ->
            val owner=person(agent=true);val admin=person("ADMIN");val id=create(owner)
            pool { executor ->
                val locked=CountDownLatch(1);val release=CountDownLatch(1);val pid=ArrayBlockingQueue<Int>(1);val blocker=ArrayBlockingQueue<Int>(1)
                val revocation=executor.submit { tx.executeWithoutResult {
                    blocker.put(pid())
                    jdbc.update(if(revokeTarget) "UPDATE participant SET is_agent=false WHERE id=?" else "UPDATE participant SET role='USER' WHERE id=?",
                        if(revokeTarget)owner else admin)
                    locked.countDown();assertTrue(release.await(10,TimeUnit.SECONDS))
                } }
                assertTrue(locked.await(10,TimeUnit.SECONDS))
                val write=executor.submit<Throwable?> { try { tx.executeWithoutResult {
                    timeout();pid.put(pid());commands.update(admin(admin),owner,id,AgentProductPatch(name="denied"))
                };null } catch(e:Throwable) {e} }
                try { awaitBlocked(pid.poll(10,TimeUnit.SECONDS)!!,blocker.poll(10,TimeUnit.SECONDS)!!);release.countDown();revocation.get(15,TimeUnit.SECONDS)
                    val failure=write.get(15,TimeUnit.SECONDS)
                    if(revokeTarget) assertTrue(failure is AgentProfileFailure.AgentNotFound,failure.toString())
                    else assertTrue(failure is AgentProfileFailure.AdminForbidden,failure.toString())
                    assertNotEquals("denied",jdbc.queryForObject("SELECT name FROM product WHERE id=?",String::class.java,id))
                } finally {release.countDown()}
            }
        }
    }

    @Test fun `giveaway dictionary serializes agent transition against accepted giveaway settings`() {
        val owner=person(agent=true);val admin=person("ADMIN");val id=create(owner);val competitor=create(owner)
        // Accepted giveaway settings only accepts a product with saved history.
        jdbc.update("UPDATE product SET giveaway_end_at='2091-01-02T00:00:00Z' WHERE id=?",competitor)
        pool { executor ->
            val locked=CountDownLatch(1);val release=CountDownLatch(1);val pid=ArrayBlockingQueue<Int>(1);val blocker=ArrayBlockingQueue<Int>(1)
            val first=executor.submit { tx.executeWithoutResult {
                blocker.put(pid())
                commands.update(admin(admin),owner,id,AgentProductPatch(availability="GIVEAWAY",giveaway=schedule()))
                locked.countDown();assertTrue(release.await(10,TimeUnit.SECONDS))
            } }
            assertTrue(locked.await(10,TimeUnit.SECONDS))
            val second=executor.submit<Throwable?> {try { tx.executeWithoutResult {
                timeout();pid.put(pid());val s=schedule()
                giveaways.update(competitor,GiveawayCaller(admin,"ADMIN",false),GiveawaySettings(s.enabled,s.telegramUrl,s.startAt,s.endAt,s.winnersCount,s.rules,s.homeText))
            };null }catch(e:Throwable){e}}
            try { awaitBlocked(pid.poll(10,TimeUnit.SECONDS)!!,blocker.poll(10,TimeUnit.SECONDS)!!);release.countDown();first.get(15,TimeUnit.SECONDS)
                val failure=second.get(15,TimeUnit.SECONDS)
                assertTrue(failure is GiveawayFailure,failure.toString())
                assertEquals("GIVEAWAY_WINDOW_OVERLAP",(failure as GiveawayFailure).code)
            } finally {release.countDown()}
        }
    }

    @Test fun `reservation locks product before an agent update reads its new state`() {
        val owner=person(agent=true);val caller=admin(person("ADMIN"));val id=create(owner)
        pool { executor ->
            val locked=CountDownLatch(1);val release=CountDownLatch(1);val pid=ArrayBlockingQueue<Int>(1);val blocker=ArrayBlockingQueue<Int>(1)
            val reserve=executor.submit {tx.executeWithoutResult {
                blocker.put(pid())
                assertEquals(owner,reservation.reserve(listOf(ProductQuantity(id,1))).single().ownerId)
                locked.countDown();assertTrue(release.await(10,TimeUnit.SECONDS))
            }}
            assertTrue(locked.await(10,TimeUnit.SECONDS))
            val write=executor.submit {tx.executeWithoutResult {timeout();pid.put(pid());commands.update(caller,owner,id,AgentProductPatch(price=22f))}}
            try {awaitBlocked(pid.poll(10,TimeUnit.SECONDS)!!,blocker.poll(10,TimeUnit.SECONDS)!!);release.countDown();reserve.get(15,TimeUnit.SECONDS);write.get(15,TimeUnit.SECONDS)
                assertEquals(22f,commands.product(caller,id).price)
            } finally {release.countDown()}
        }
    }

    @Test fun `ordinary status command and agent patch serialize without losing the committed status`() {
        val owner=person(agent=true);val caller=admin(person("ADMIN"));val id=create(owner)
        pool { executor ->
            val locked=CountDownLatch(1);val release=CountDownLatch(1);val pid=ArrayBlockingQueue<Int>(1);val blocker=ArrayBlockingQueue<Int>(1)
            val status=executor.submit {tx.executeWithoutResult {
                blocker.put(pid())
                ordinary.changeStatus(id,ProductState.BLOCKED,caller.id);locked.countDown()
                assertTrue(release.await(10,TimeUnit.SECONDS))
            }}
            assertTrue(locked.await(10,TimeUnit.SECONDS))
            val patch=executor.submit {tx.executeWithoutResult {
                timeout();pid.put(pid());commands.update(caller,owner,id,AgentProductPatch(description="kept"))
            }}
            try {awaitBlocked(pid.poll(10,TimeUnit.SECONDS)!!,blocker.poll(10,TimeUnit.SECONDS)!!);release.countDown()
                status.get(15,TimeUnit.SECONDS);patch.get(15,TimeUnit.SECONDS)
                val result=commands.product(caller,id)
                assertEquals("BLOCKED",result.status);assertEquals("kept",result.description)
            } finally {release.countDown()}
        }
    }

    @Test fun `agent lock then admin product update serializes media upload and delete without deadlock`() {
        // Same interleaving as the historical 40P01 repro, now through the real owner lock.
        // owner < admin makes retaining this first participant lock consistent with 04.5's order.
        listOf(false,true).forEach { deleting ->
            val owner=person(agent=true);val admin=person("ADMIN");assertTrue(owner<admin)
            val id=create(owner);val image=image(owner,status="ACTIVE",entity=id)
            val uploaded=AtomicReference<List<Long>>(emptyList())
            pool { executor ->
                val ownerLocked=CountDownLatch(1);val proceed=CountDownLatch(1)
                val ownerPid=ArrayBlockingQueue<Int>(1);val mediaPid=ArrayBlockingQueue<Int>(1)
                val catalog=executor.submit<Throwable?> {try {tx.executeWithoutResult {
                    timeout();jdbc.execute("SET LOCAL deadlock_timeout='100ms'")
                    ownerPid.put(pid());activeAgent.lockActiveAgent(owner)
                    ownerLocked.countDown();assertTrue(proceed.await(10,TimeUnit.SECONDS))
                    commands.update(admin(admin),owner,id,AgentProductPatch(description="serialized"))
                };null}catch(e:Throwable){e}}
                assertTrue(ownerLocked.await(10,TimeUnit.SECONDS))
                val foreign=executor.submit<Throwable?> {try {tx.executeWithoutResult {
                    timeout();jdbc.execute("SET LOCAL deadlock_timeout='100ms'");mediaPid.put(pid())
                    if(deleting)media.markDeleted(listOf(image),StorageLocation.PRODUCT,admin)
                    else uploaded.set(media.insertBatch(UploadTarget(StorageLocation.PRODUCT,id,admin),
                        listOf(ImageToInsert("serialized-${UUID.randomUUID()}.jpg",1,1))))
                };null}catch(e:Throwable){e}}
                try {awaitBlocked(mediaPid.poll(10,TimeUnit.SECONDS)!!,ownerPid.poll(10,TimeUnit.SECONDS)!!);proceed.countDown()
                    val outcomes=listOf(catalog.get(15,TimeUnit.SECONDS),foreign.get(15,TimeUnit.SECONDS))
                    outcomes.forEach { failure ->
                        assertNotEquals("40P01",state(failure),failure.toString())
                        assertNull(failure,failure?.stackTraceToString())
                    }
                } finally {proceed.countDown()}
            }
            val product=commands.product(admin(admin),id)
            assertEquals(owner,product.participantId);assertEquals("serialized",product.description)
            assertEquals("ACTIVE",product.status)
            assertEquals(if(deleting)"DELETE" else "ACTIVE",
                jdbc.queryForObject("SELECT status::text FROM image WHERE id=?",String::class.java,image))
            assertEquals(id,jdbc.queryForObject("SELECT entity_id FROM image WHERE id=?",Long::class.java,image))
            assertEquals(owner,jdbc.queryForObject("SELECT uploaded_by FROM image WHERE id=?",Long::class.java,image))
            if(!deleting) {
                val newImage=uploaded.get().single()
                assertEquals(id,jdbc.queryForObject("SELECT entity_id FROM image WHERE id=?",Long::class.java,newImage))
                assertEquals(admin,jdbc.queryForObject("SELECT uploaded_by FROM image WHERE id=?",Long::class.java,newImage))
                assertEquals("TEMPORARY",jdbc.queryForObject("SELECT status::text FROM image WHERE id=?",String::class.java,newImage))
            }
        }
    }

    @Test fun `catalog updates serialize media for both administrator owner ID orders`() {
        listOf(false,true).forEach { adminFirst -> listOf(false,true).forEach { deleting ->
            val first=person(if(adminFirst)"ADMIN" else "USER",agent=!adminFirst)
            val second=person(if(adminFirst)"USER" else "ADMIN",agent=adminFirst)
            val owner=if(adminFirst)second else first;val caller=if(adminFirst)first else second
            val id=create(owner);val image=image(owner,status="ACTIVE",entity=id)
            val uploaded=AtomicReference<List<Long>>(emptyList())
            pool { executor ->
                val locked=CountDownLatch(1);val release=CountDownLatch(1)
                val catalogPid=ArrayBlockingQueue<Int>(1);val mediaPid=ArrayBlockingQueue<Int>(1)
                val catalog=executor.submit {tx.executeWithoutResult {
                    timeout();catalogPid.put(pid())
                    commands.update(admin(caller),owner,id,AgentProductPatch(description="both-orders"))
                    locked.countDown();assertTrue(release.await(10,TimeUnit.SECONDS))
                }}
                assertTrue(locked.await(10,TimeUnit.SECONDS))
                val imageWrite=executor.submit {tx.executeWithoutResult {
                    timeout();mediaPid.put(pid())
                    if(deleting)media.markDeleted(listOf(image),StorageLocation.PRODUCT,caller)
                    else uploaded.set(media.insertBatch(UploadTarget(StorageLocation.PRODUCT,id,caller),
                        listOf(ImageToInsert("ordered-${UUID.randomUUID()}.jpg",1,1))))
                }}
                try {
                    awaitBlocked(mediaPid.poll(10,TimeUnit.SECONDS)!!,catalogPid.poll(10,TimeUnit.SECONDS)!!)
                    release.countDown();catalog.get(15,TimeUnit.SECONDS);imageWrite.get(15,TimeUnit.SECONDS)
                } finally {release.countDown()}
            }
            assertEquals("both-orders",commands.product(admin(caller),id).description)
            assertEquals(owner,jdbc.queryForObject("SELECT participant_id FROM product WHERE id=?",Long::class.java,id))
            assertEquals(if(deleting)"DELETE" else "ACTIVE",
                jdbc.queryForObject("SELECT status::text FROM image WHERE id=?",String::class.java,image))
            assertEquals(id,jdbc.queryForObject("SELECT entity_id FROM image WHERE id=?",Long::class.java,image))
            if(!deleting) {
                val created=uploaded.get().single()
                assertEquals(id,jdbc.queryForObject("SELECT entity_id FROM image WHERE id=?",Long::class.java,created))
                assertEquals(caller,jdbc.queryForObject("SELECT uploaded_by FROM image WHERE id=?",Long::class.java,created))
                assertEquals("TEMPORARY",jdbc.queryForObject("SELECT status::text FROM image WHERE id=?",String::class.java,created))
            }
        }}
    }

    @Test fun `waiting media rechecks revoked administrator role and target agent flag`() {
        listOf(false,true).forEach { revokeTarget -> listOf(false,true).forEach { deleting ->
            val owner=person(agent=true);val caller=person("ADMIN");val id=create(owner)
            val image=image(owner,status="ACTIVE",entity=id)
            val before=jdbc.queryForObject("SELECT count(*) FROM image WHERE entity_id=? AND tag='PRODUCT'",Int::class.java,id)
            pool { executor ->
                val locked=CountDownLatch(1);val release=CountDownLatch(1)
                val catalogPid=ArrayBlockingQueue<Int>(1);val mediaPid=ArrayBlockingQueue<Int>(1)
                val catalog=executor.submit {tx.executeWithoutResult {
                    timeout();catalogPid.put(pid())
                    commands.update(admin(caller),owner,id,AgentProductPatch(description="committed-before-revoke"))
                    // Test fixture revocation shares the already locked participant transaction.
                    jdbc.update(if(revokeTarget)"UPDATE participant SET is_agent=false WHERE id=?"
                        else "UPDATE participant SET role='USER' WHERE id=?",if(revokeTarget)owner else caller)
                    locked.countDown();assertTrue(release.await(10,TimeUnit.SECONDS))
                }}
                assertTrue(locked.await(10,TimeUnit.SECONDS))
                val imageWrite=executor.submit<Throwable?> {try {tx.executeWithoutResult {
                    timeout();mediaPid.put(pid())
                    if(deleting)media.markDeleted(listOf(image),StorageLocation.PRODUCT,caller)
                    else media.insertBatch(UploadTarget(StorageLocation.PRODUCT,id,caller),
                        listOf(ImageToInsert("denied-${UUID.randomUUID()}.jpg",1,1)))
                };null}catch(error:Throwable){error}}
                try {
                    awaitBlocked(mediaPid.poll(10,TimeUnit.SECONDS)!!,catalogPid.poll(10,TimeUnit.SECONDS)!!)
                    release.countDown();catalog.get(15,TimeUnit.SECONDS)
                    val failure=imageWrite.get(15,TimeUnit.SECONDS)
                    assertTrue(failure is ImageCommandDenied,failure?.stackTraceToString())
                    assertNotEquals("40P01",state(failure))
                } finally {release.countDown()}
            }
            assertEquals("committed-before-revoke",jdbc.queryForObject("SELECT description FROM product WHERE id=?",String::class.java,id))
            assertEquals("ACTIVE",jdbc.queryForObject("SELECT status::text FROM image WHERE id=?",String::class.java,image))
            assertEquals(before,jdbc.queryForObject("SELECT count(*) FROM image WHERE entity_id=? AND tag='PRODUCT'",Int::class.java,id))
            assertEquals(owner,jdbc.queryForObject("SELECT participant_id FROM product WHERE id=?",Long::class.java,id))
            if(revokeTarget)assertFalse(jdbc.queryForObject("SELECT is_agent FROM participant WHERE id=?",Boolean::class.java,owner)!!)
            else assertEquals("USER",jdbc.queryForObject("SELECT role::text FROM participant WHERE id=?",String::class.java,caller))
        }}
    }

    companion object {
        private val pg=EmbeddedPostgres.start()
        @JvmStatic @DynamicPropertySource fun database(r:DynamicPropertyRegistry)=
            com.model_store.modern.seller.agent.product.AgentProductHttpContract.database(r,pg)
        @JvmStatic @AfterAll fun close()=pg.close()
    }
}
