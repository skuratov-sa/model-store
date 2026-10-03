package com.model_store.modern.seller.account

import com.model_store.modern.seller.account.api.AccountRequest
import com.model_store.modern.seller.account.api.ModernAccountController
import com.model_store.modern.seller.account.application.AccountStore
import com.model_store.modern.seller.account.application.AccountActorRead
import com.model_store.modern.seller.account.application.AccountSelfAccess
import com.model_store.modern.seller.account.application.AccountUseCases
import com.model_store.modern.seller.account.domain.*
import com.model_store.modern.shared.domain.Actor
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AccountUseCasesTest {
    private val store = MemoryStore()
    private val useCases = AccountUseCases(store)
    private val agents = mutableSetOf<Long>()
    private val controller = ModernAccountController(useCases, AccountSelfAccess(object : AccountActorRead {
        override fun isAgent(participantId: Long) = participantId in agents
    }))
    private val owner = Actor(1, "owner", "USER")
    private val other = Actor(2, "other", "USER")
    private val card = AccountRequest(TransferMoney.BANK_CARD, "Name", "1234", "comment")

    @Test
    fun `create list update and delete preserve account fields and owner`() {
        controller.create(owner, card)
        val created = controller.mine(owner).single()
        assertEquals(1L, created.participantId)
        assertEquals(TransferMoney.BANK_CARD, created.transferMoney)
        assertEquals("1234", created.entityValue)
        assertEquals("comment", created.comment)

        val changed = controller.update(owner, created.id, AccountRequest(TransferMoney.BANK_SBP, "New", "987", null))
        assertEquals(TransferMoney.BANK_SBP, changed.transferMoney)
        assertEquals("987", controller.byParticipant(owner, 1).single().entityValue)
        controller.delete(owner, created.id)
        assertThrows(AccountNotFound::class.java) { controller.mine(owner) }
    }

    @Test
    fun `duplicate is checked by entity value as in legacy`() {
        controller.create(owner, card)
        assertThrows(AccountAlreadyExists::class.java) {
            controller.create(owner, AccountRequest(TransferMoney.CASH, null, "1234", null))
        }
        controller.create(other, card)
        assertEquals(2, store.rows.size)
        controller.create(owner, AccountRequest(TransferMoney.CASH))
        assertThrows(AccountAlreadyExists::class.java) {
            controller.create(owner, AccountRequest(TransferMoney.BANK_SBP))
        }
    }

    @Test
    fun `another user cannot read mutate or delete an account`() {
        controller.create(owner, card)
        val id = controller.mine(owner).single().id
        assertThrows(AccountAccessDenied::class.java) { controller.byParticipant(other, 1) }
        assertThrows(AccountNotFound::class.java) { controller.update(other, id, card) }
        assertThrows(AccountNotFound::class.java) { controller.delete(other, id) }
        assertEquals("1234", controller.mine(owner).single().entityValue)
        assertThrows(AccountAccessDenied::class.java) { controller.mine(null) }
    }

    @Test
    fun `agent cannot use ordinary accounts routes`() {
        agents.add(1)
        assertThrows(AgentAccountDenied::class.java) { controller.create(owner, card) }
        assertThrows(AgentAccountDenied::class.java) { controller.mine(owner) }
        assertThrows(AgentAccountDenied::class.java) { controller.byParticipant(owner, 1) }
    }

    private class MemoryStore : AccountStore {
        val rows = mutableMapOf<Long, SellerAccount>()
        private var next = 1L
        override fun lockForCreate(participantId: Long) = Unit
        override fun byParticipant(participantId: Long) = rows.values.filter { it.participantId == participantId }
        override fun byId(id: Long) = rows[id]
        override fun create(participantId: Long, details: AccountDetails) {
            val id = next++
            rows[id] = SellerAccount(id, requireNotNull(details.transferMoney), details.username,
                details.entityValue, details.comment, participantId)
        }
        override fun update(id: Long, participantId: Long, details: AccountDetails): SellerAccount =
            rows.getValue(id).copy(transferMoney = requireNotNull(details.transferMoney), username = details.username,
                entityValue = details.entityValue, comment = details.comment).also { rows[id] = it }
        override fun delete(id: Long) { rows.remove(id) }
    }
}
