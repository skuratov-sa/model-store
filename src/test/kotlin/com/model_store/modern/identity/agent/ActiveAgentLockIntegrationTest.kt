package com.model_store.modern.identity.agent

import com.amazonaws.services.s3.AmazonS3
import com.model_store.modern.identity.agent.application.*
import com.model_store.modern.identity.agent.domain.AgentTokenFailure
import com.model_store.modern.identity.agent.infrastructure.JdbcActiveAgentLock
import com.model_store.modern.identity.participant.infrastructure.ParticipantEntity
import com.model_store.modern.identity.verification.application.VerificationMail
import com.model_store.modern.shared.domain.Actor
import com.model_store.modern.shared.domain.TokenType
import com.model_store.modern.catalog.product.agent.application.AgentProductPatch
import com.model_store.modern.seller.agent.product.application.AgentProductActions
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import jakarta.persistence.EntityManager
import jakarta.persistence.EntityManagerFactory
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.IllegalTransactionStateException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import java.util.concurrent.*
import javax.sql.DataSource

@SpringBootTest(properties=["app.public-key-path=keys/test_public_key.pem","app.private-key-path=keys/test_private_key.pem"])
@ActiveProfiles("modern")
class ActiveAgentLockIntegrationTest {
    @MockitoBean lateinit var s3:AmazonS3
    @MockitoBean lateinit var mail:VerificationMail
    @MockitoBean lateinit var decoder:JwtDecoder
    @Autowired lateinit var lock:ActiveAgentLock
    @Autowired lateinit var verify:VerifyAgentAccess
    @Autowired lateinit var jdbc:JdbcTemplate
    @Autowired lateinit var named:NamedParameterJdbcTemplate
    @Autowired lateinit var manager:PlatformTransactionManager
    @Autowired lateinit var em:EntityManager
    @Autowired lateinit var emf:EntityManagerFactory
    @Autowired lateinit var actions:AgentProductActions
    private val tx get()=TransactionTemplate(manager)

    private fun agent():Long {
        val login="lock29-${UUID.randomUUID()}"
        return jdbc.queryForObject("""INSERT INTO participant(login,mail,password,status,is_agent)
            VALUES (?,?,'hash','ACTIVE',true) RETURNING id""",Long::class.java,login,"$login@example.test")!!
    }
    private fun pool(action:(ExecutorService)->Unit) {
        val executor=Executors.newFixedThreadPool(2)
        try { action(executor) } finally {
            executor.shutdownNow();assertTrue(executor.awaitTermination(10,TimeUnit.SECONDS))
        }
    }
    private fun awaitLock(pid:Int,blockingPid:Int) {
        val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(8)
        while(System.nanoTime()<until) {
            if(jdbc.queryForObject("SELECT ?=ANY(pg_blocking_pids(?))",Boolean::class.java,blockingPid,pid)==true) return
            Thread.sleep(10)
        }
        fail<Unit>("Backend $pid did not wait for blocker $blockingPid")
    }

    @Test fun `rejects absent wrong read-only and foreign EMF transactions`() {
        val id=agent()
        assertThrows(IllegalTransactionStateException::class.java) { lock.lockActiveAgent(id) }
        assertThrows(IllegalTransactionStateException::class.java) {
            TransactionTemplate(DataSourceTransactionManager(jdbc.dataSource!!)).executeWithoutResult { lock.lockActiveAgent(id) }
        }
        assertThrows(ActiveAgentTransactionRequired::class.java) {
            TransactionTemplate(manager).apply { isReadOnly=true }.executeWithoutResult { lock.lockActiveAgent(id) }
        }
        val foreign=JdbcActiveAgentLock(named,mock(EntityManagerFactory::class.java),jdbc.dataSource!!)
        assertThrows(ActiveAgentTransactionRequired::class.java) { tx.executeWithoutResult { foreign.lockActiveAgent(id) } }
        val foreignSource=JdbcActiveAgentLock(named,emf,mock(DataSource::class.java))
        assertThrows(ActiveAgentTransactionRequired::class.java) {tx.executeWithoutResult {foreignSource.lockActiveAgent(id)}}
        tx.executeWithoutResult { lock.lockActiveAgent(id) }
    }

    @Test fun `missing non-agent and every inactive status are rejected under lock`() {
        assertThrows(AgentTokenFailure.InvalidAgentAccess::class.java) {tx.executeWithoutResult {lock.lockActiveAgent(Long.MAX_VALUE)}}
        listOf("status='BLOCKED'","status='DELETED'","status='WAITING_VERIFY'","is_agent=false").forEach { change ->
            val id=agent();jdbc.update("UPDATE participant SET $change WHERE id=?",id)
            assertThrows(AgentTokenFailure.InvalidAgentAccess::class.java) {tx.executeWithoutResult {lock.lockActiveAgent(id)}}
        }
    }

    @Test fun `rejects a JDBC template whose datasource is not enlisted in caller JPA`() {
        val id=agent()
        val unbound=DriverManagerDataSource("jdbc:postgresql://localhost:${pg.port}/postgres","postgres","")
        val miswired=JdbcActiveAgentLock(NamedParameterJdbcTemplate(unbound),emf,jdbc.dataSource!!)
        // An EMF and its regular DataSource are bound, but this template would use autocommit.
        assertThrows(ActiveAgentTransactionRequired::class.java) {
            tx.executeWithoutResult {miswired.lockActiveAgent(id)}
        }
    }

    @Test fun `read-only guard race is deterministic and the new lock rejects revocation in its gap`() {
        listOf("status='BLOCKED'","is_agent=false").forEach { change ->
            val id=agent();val actor=Actor(id,"agent29","USER",TokenType.AGENT_ACCESS)
            pool { executor ->
                // This is the old read -> later write window: the returned scalar stays authorized
                // after another transaction has revoked eligibility. No lock is retained by Verify13.
                val readId=verify.requireActiveAgent(actor)
                executor.submit { jdbc.update("UPDATE participant SET $change WHERE id=?",id) }.get(10,TimeUnit.SECONDS)
                assertEquals(id,readId)
                assertThrows(AgentTokenFailure.InvalidAgentAccess::class.java) {
                    tx.executeWithoutResult { lock.lockActiveAgent(readId) }
                }
            }
        }
    }

    @Test fun `after waiting lock reads fresh state despite a preloaded JPA entity`() {
        listOf("status='DELETED'","is_agent=false").forEach { change ->
            val id=agent();pool { executor ->
                val preloaded=CountDownLatch(1);val changed=CountDownLatch(1);val release=CountDownLatch(1)
                val pid=ArrayBlockingQueue<Int>(1);val blocker=ArrayBlockingQueue<Int>(1)
                val candidate=executor.submit<Boolean> {
                    try { tx.executeWithoutResult {
                        assertTrue(em.find(ParticipantEntity::class.java,id).isAgent)
                        preloaded.countDown();assertTrue(changed.await(10,TimeUnit.SECONDS))
                        pid.put(jdbc.queryForObject("SELECT pg_backend_pid()",Int::class.java)!!)
                        lock.lockActiveAgent(id)
                    };false } catch(expected:AgentTokenFailure.InvalidAgentAccess) { true }
                }
                assertTrue(preloaded.await(10,TimeUnit.SECONDS))
                val revoke=executor.submit { tx.executeWithoutResult {
                    blocker.put(jdbc.queryForObject("SELECT pg_backend_pid()",Int::class.java)!!)
                    jdbc.update("UPDATE participant SET $change WHERE id=?",id)
                    changed.countDown();assertTrue(release.await(10,TimeUnit.SECONDS))
                } }
                try { awaitLock(pid.poll(10,TimeUnit.SECONDS)!!,blocker.poll(10,TimeUnit.SECONDS)!!);release.countDown()
                    revoke.get(10,TimeUnit.SECONDS);assertTrue(candidate.get(10,TimeUnit.SECONDS))
                } finally { release.countDown();changed.countDown() }
            }
        }
    }

    @Test fun `lock retains eligibility through outer rollback then releases to a waiting revoker`() {
        val id=agent();val marker="rollback29-${UUID.randomUUID()}";pool { executor ->
            val locked=CountDownLatch(1);val release=CountDownLatch(1);val pid=ArrayBlockingQueue<Int>(1);val blocker=ArrayBlockingQueue<Int>(1)
            val command=executor.submit {
                assertThrows(IllegalStateException::class.java) { tx.executeWithoutResult {
                    blocker.put(jdbc.queryForObject("SELECT pg_backend_pid()",Int::class.java)!!)
                    lock.lockActiveAgent(id)
                    // A write to the locked participant would itself block the revoker and mask
                    // a broken owner lock. Use an unrelated row to prove outer rollback instead.
                    jdbc.update("INSERT INTO category(name) VALUES (?)",marker)
                    locked.countDown();assertTrue(release.await(10,TimeUnit.SECONDS));error("outer failure")
                } }
            }
            assertTrue(locked.await(10,TimeUnit.SECONDS))
            val revoke=executor.submit { tx.executeWithoutResult {
                pid.put(jdbc.queryForObject("SELECT pg_backend_pid()",Int::class.java)!!)
                jdbc.update("UPDATE participant SET is_agent=false WHERE id=?",id)
            } }
            try { awaitLock(pid.poll(10,TimeUnit.SECONDS)!!,blocker.poll(10,TimeUnit.SECONDS)!!);release.countDown()
                command.get(10,TimeUnit.SECONDS);revoke.get(10,TimeUnit.SECONDS)
                assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM category WHERE name=?",Int::class.java,marker))
                assertFalse(jdbc.queryForObject("SELECT is_agent FROM participant WHERE id=?",Boolean::class.java,id)!!)
            } finally { release.countDown() }
        }
    }

    @Test fun `real seller creation holds agent authorization until product commit`() {
        val id=agent();val actor=Actor(id,"agent29","USER",TokenType.AGENT_ACCESS)
        pool { executor ->
            val locked=CountDownLatch(1);val release=CountDownLatch(1);val pid=ArrayBlockingQueue<Int>(1);val blocker=ArrayBlockingQueue<Int>(1)
            val creation=executor.submit<Long> {tx.execute {
                blocker.put(jdbc.queryForObject("SELECT pg_backend_pid()",Int::class.java)!!)
                val product=actions.create(actor,AgentProductPatch(name="committed-${UUID.randomUUID()}",price=1f,
                    currency="RUB",externalUrl="https://example.test/committed"))
                locked.countDown();assertTrue(release.await(10,TimeUnit.SECONDS));product
            }!!}
            assertTrue(locked.await(10,TimeUnit.SECONDS))
            val revocation=executor.submit {tx.executeWithoutResult {
                pid.put(jdbc.queryForObject("SELECT pg_backend_pid()",Int::class.java)!!)
                jdbc.update("UPDATE participant SET is_agent=false WHERE id=?",id)
            }}
            try {awaitLock(pid.poll(10,TimeUnit.SECONDS)!!,blocker.poll(10,TimeUnit.SECONDS)!!);release.countDown()
                val product=creation.get(15,TimeUnit.SECONDS);revocation.get(15,TimeUnit.SECONDS)
                assertEquals(id,jdbc.queryForObject("SELECT participant_id FROM product WHERE id=?",Long::class.java,product))
                assertFalse(jdbc.queryForObject("SELECT is_agent FROM participant WHERE id=?",Boolean::class.java,id)!!)
            } finally {release.countDown()}
        }
    }

    companion object {
        private val pg=EmbeddedPostgres.start()
        @JvmStatic @DynamicPropertySource fun database(r:DynamicPropertyRegistry)=
            com.model_store.modern.seller.agent.product.AgentProductHttpContract.database(r,pg)
        @JvmStatic @AfterAll fun close()=pg.close()
    }
}
