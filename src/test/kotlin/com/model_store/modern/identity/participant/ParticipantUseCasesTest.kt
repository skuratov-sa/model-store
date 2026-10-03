package com.model_store.modern.identity.participant

import com.model_store.modern.identity.participant.application.*
import com.model_store.modern.identity.participant.domain.*
import com.model_store.modern.identity.participant.infrastructure.BCryptParticipantPasswords
import com.model_store.modern.shared.domain.Actor
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import java.time.Instant

class ParticipantUseCasesTest {
    private val passwords = BCryptParticipantPasswords()
    private val original = Participant(
        7, "old", "user@example.com", "Old Name", null, ParticipantStatus.ACTIVE,
        ParticipantRole.ADMIN, BCryptPasswordEncoder().encode("old-secret")!!, 2, 3,
        SellerStatus.PRO, Instant.now(), 25, false,
    )

    @Test
    fun `registration hashes password and keeps unique mail`() {
        val store = MemoryStore()
        val register = RegisterParticipant(store, passwords)
        val id = register.execute("new@example.com", "secret", 25)
        assertEquals(ParticipantStatus.WAITING_VERIFY, store.find(id)?.status)
        assertEquals(ParticipantRole.USER, store.find(id)?.role)
        assertTrue(BCryptPasswordEncoder().matches("secret", store.find(id)?.passwordHash))
        assertThrows(MailAlreadyRegistered::class.java) { register.execute("new@example.com", "secret", 25) }
        assertThrows(IllegalArgumentException::class.java) { register.execute("other@example.com", "secret", 151) }
    }

    @Test
    fun `profile update preserves protected fields and rejects a blocked actor`() {
        val store = MemoryStore(original)
        val images = object : ParticipantImagePort {
            override fun replace(participantId: Long, imageId: Long) = Unit
        }
        val update = UpdateParticipant(store, images)
        update.execute(7, "new", "New Name", null, null, null, null)
        val result = store.find(7)!!
        assertEquals("new", result.login)
        assertEquals(original.mail, result.mail)
        assertEquals(original.passwordHash, result.passwordHash)
        assertEquals(ParticipantRole.ADMIN, result.role)
        assertEquals(SellerStatus.PRO, result.sellerStatus)
        store.save(original.copy(id = 8, login = "taken"))
        assertThrows(LoginAlreadyExists::class.java) {
            update.execute(7, "taken", null, null, null, null, null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            update.execute(7, "safe", null, null, Int.MAX_VALUE, null, null)
        }
        assertEquals("new", store.find(7)?.login)
        store.save(result.copy(status = ParticipantStatus.BLOCKED))
        assertThrows(ParticipantUnavailable::class.java) {
            update.execute(7, "attempt", null, null, null, null, null)
        }
    }

    @Test
    fun `password and status changes enforce current state`() {
        val store = MemoryStore(original)
        val password = ChangeParticipantPassword(store, passwords)
        assertThrows(BadCredentialsException::class.java) { password.execute(7, "wrong", "new-secret") }
        password.execute(7, "old-secret", "new-secret")
        assertTrue(BCryptPasswordEncoder().matches("new-secret", store.find(7)?.passwordHash))
        DeleteParticipant(store).execute(7)
        assertEquals(ParticipantStatus.DELETED, store.find(7)?.status)
        assertThrows(IllegalArgumentException::class.java) { password.execute(7, "new-secret", "") }
        password.execute(7, "new-secret", "after-delete")
        assertTrue(BCryptPasswordEncoder().matches("after-delete", store.find(7)?.passwordHash))
        assertThrows(ParticipantAdminForbidden::class.java) {
            BlockParticipant(store).execute(Actor(7, "user7", "USER"), 7)
        }
        assertThrows(ParticipantStatusUnavailable::class.java) {
            BlockParticipant(store).execute(Actor(8, "admin", "ADMIN"), 7)
        }
    }

    @Test
    fun `verification activation has explicit outcomes for every status`() {
        for (status in ParticipantStatus.entries) {
            val store = MemoryStore(original.copy(status = status))
            val command: ParticipantVerificationCommands = ParticipantVerificationService(store, passwords)
            when (status) {
                ParticipantStatus.WAITING_VERIFY -> {
                    assertEquals(7L, command.activate(7))
                    assertEquals(ParticipantStatus.ACTIVE, store.find(7)?.status)
                    assertEquals(original.passwordHash, store.find(7)?.passwordHash)
                }
                ParticipantStatus.ACTIVE -> assertThrows(ParticipantVerificationFailure.AlreadyActive::class.java) { command.activate(7) }
                ParticipantStatus.BLOCKED -> assertThrows(ParticipantVerificationFailure.Blocked::class.java) { command.activate(7) }
                ParticipantStatus.DELETED -> assertThrows(ParticipantVerificationFailure.Deleted::class.java) { command.activate(7) }
            }
            if (status != ParticipantStatus.WAITING_VERIFY) assertEquals(original.copy(status = status), store.find(7))
        }
        assertThrows(ParticipantVerificationFailure.NotFound::class.java) {
            ParticipantVerificationService(MemoryStore(), passwords).activate(7)
        }
    }

    @Test
    fun `verification reset changes password only for waiting and active participants`() {
        for (status in ParticipantStatus.entries) {
            val store = MemoryStore(original.copy(status = status))
            val command: ParticipantVerificationCommands = ParticipantVerificationService(store, passwords)
            when (status) {
                ParticipantStatus.WAITING_VERIFY, ParticipantStatus.ACTIVE -> {
                    assertEquals(7L, command.resetPassword(7, "temporary-secret"))
                    assertEquals(ParticipantStatus.ACTIVE, store.find(7)?.status)
                    assertTrue(passwords.matches("temporary-secret", store.find(7)!!.passwordHash))
                }
                ParticipantStatus.BLOCKED -> assertThrows(ParticipantVerificationFailure.Blocked::class.java) { command.resetPassword(7, "temporary-secret") }
                ParticipantStatus.DELETED -> assertThrows(ParticipantVerificationFailure.Deleted::class.java) { command.resetPassword(7, "temporary-secret") }
            }
            if (status == ParticipantStatus.BLOCKED || status == ParticipantStatus.DELETED)
                assertEquals(original.copy(status = status), store.find(7))
        }
        assertThrows(ParticipantVerificationFailure.NotFound::class.java) {
            ParticipantVerificationService(MemoryStore(), passwords).resetPassword(7, "temporary-secret")
        }
        val store = MemoryStore(original)
        assertThrows(IllegalArgumentException::class.java) { ParticipantVerificationService(store, passwords).resetPassword(7, "") }
        assertEquals(original, store.find(7))
    }

    private class MemoryStore(initial: Participant? = null) : ParticipantStore {
        private val values = mutableMapOf<Long, Participant>()
        init { if (initial != null) values[initial.id] = initial }
        override fun find(id: Long) = values[id]
        override fun findForUpdate(id: Long) = values[id]
        override fun findActive(id: Long) = values[id]?.takeIf { it.status == ParticipantStatus.ACTIVE }
        override fun mailExists(mail: String) = values.values.any { it.mail == mail }
        override fun loginExistsForAnother(login: String, id: Long) = values.values.any { it.id != id && it.login == login }
        override fun create(mail: String?, passwordHash: String, age: Int): Participant {
            val id = 100L + values.size
            return Participant(id, "user$id", mail, null, null, ParticipantStatus.WAITING_VERIFY,
                ParticipantRole.USER, passwordHash, 1, 1, SellerStatus.DEFAULT, Instant.now(), age, false)
                .also { values[id] = it }
        }
        override fun save(participant: Participant) = participant.also { values[it.id] = it }
    }
}
