package com.model_store.modern.ordering.agent

import com.model_store.modern.seller.agent.order.AgentOrderFixture
import com.model_store.modern.ordering.agent.application.AgentOrderQueries
import com.model_store.modern.ordering.lifecycle.application.SelectedOrderRead
import com.model_store.modern.ordering.lifecycle.application.LifecycleTransaction
import com.model_store.modern.ordering.agent.application.AgentOrderCommands
import com.model_store.modern.ordering.agent.infrastructure.JdbcAgentOrderQueue
import com.model_store.modern.identity.participant.application.AgentAccessLock
import com.model_store.modern.identity.participant.application.CurrentAdminLock
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.ActiveProfiles

@ActiveProfiles("modern")
class AgentOrderQueueIntegrationTest : AgentOrderFixture() {
    @Autowired lateinit var queries: AgentOrderQueries
    @Autowired lateinit var selected: SelectedOrderRead
    @Autowired lateinit var commands: AgentOrderCommands
    @Autowired lateinit var admins: CurrentAdminLock
    @Autowired lateinit var access: AgentAccessLock
    @Autowired lateinit var transaction: LifecycleTransaction
    @Autowired lateinit var transactions: org.springframework.transaction.PlatformTransactionManager

    @Test
    fun queueRechecksSelectedMembershipAndLateLogin() {
        for(change in listOf("status","seller","login")) {
            val f=agentFixture(); val a=admin(); val otherAdmin=admin(); val otherSeller=participant()
            jdbc.update("UPDATE \"order\" SET created_at='2199-01-01' WHERE id=?",f.order)
            val pool=java.util.concurrent.Executors.newSingleThreadExecutor()
            try {
                val duringProjection=object : SelectedOrderRead {
                    override fun ordersByIds(ids:List<Long>,seller:Boolean):List<com.model_store.modern.ordering.lifecycle.application.OrderView> {
                        assertEquals(listOf(f.order),ids)
                        pool.submit {
                            when(change) {
                                "status" -> commands.execute(otherAdmin,f.seller,f.order,"CANCEL",null,null)
                                "seller" -> jdbc.update("UPDATE \"order\" SET seller_id=? WHERE id=?",otherSeller,f.order)
                                else -> jdbc.update("UPDATE participant SET login=? WHERE id=?","late-${f.seller}",f.seller)
                            }
                        }.get(10,java.util.concurrent.TimeUnit.SECONDS)
                        return selected.ordersByIds(ids,seller)
                    }
                }
                val adapter=JdbcAgentOrderQueue(org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc),duringProjection)
                val service=AgentOrderQueries(admins,access,transaction,adapter)
                val page=org.springframework.transaction.support.TransactionTemplate(transactions).execute {
                    service.queue(a,null,"BOOKED",0,1)
                }!!
                if(change=="login") assertEquals("late-${f.seller}",page.single().sellerLogin)
                else assertTrue(page.isEmpty(),"stale candidate $change must not escape the queue filter: $page")
                if(change=="status") { assertEquals("CANCELLED",status(f.order)); assertEquals(1,audits(f.order)) }
            } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS)) }
        }
    }

    @Test
    fun `selected projection is empty bounded and only enriches chosen page`() {
        val a=admin(); val f=agentFixture(); val bad=agentFixture()
        jdbc.update("UPDATE \"order\" SET created_at='2091-01-01' WHERE id=?",f.order)
        // Deliberately malformed row in this private DB: suspend and restore NOT NULL only for this test.
        jdbc.execute("ALTER TABLE \"order\" ALTER COLUMN address_id DROP NOT NULL")
        try {
            jdbc.update("UPDATE \"order\" SET seller_id=?,address_id=null,created_at='2090-01-01' WHERE id=?",f.seller,bad.order)
            assertTrue(selected.ordersByIds(emptyList(),true).isEmpty())
            assertThrows(org.springframework.dao.InvalidDataAccessApiUsageException::class.java) { selected.ordersByIds(List(101) { f.order },true) }
            val page=queries.queue(a,f.seller,null,0,1)
            assertEquals(listOf(f.order),page.map { it.order.orderId })
            assertEquals(listOf(f.order),selected.ordersByIds(listOf(f.order),true).map { it.orderId })
            assertEquals(f.buyer,page.single().order.userInfo.id)
            assertThrows(com.model_store.modern.ordering.lifecycle.domain.LifecycleFailure::class.java) {
                selected.ordersByIds(listOf(bad.order),true)
            }
        } finally {
            jdbc.update("UPDATE \"order\" SET address_id=? WHERE id=?",bad.address,bad.order)
            jdbc.execute("ALTER TABLE \"order\" ALTER COLUMN address_id SET NOT NULL")
        }
    }
}
