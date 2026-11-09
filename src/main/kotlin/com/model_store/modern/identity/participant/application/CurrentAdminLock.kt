package com.model_store.modern.identity.participant.application

/**
 * Locks the current ACTIVE, non-agent ADMIN until the caller's writable JPA transaction ends.
 * Call before product/image locks. This port neither authorizes nor locks a target owner.
 * If a scenario also needs other participants, it must acquire the complete set in ID order.
 */
interface CurrentAdminLock {
    fun lockActiveAdmin(callerId: Long)
}

class CurrentAdminForbidden : RuntimeException("Доступ запрещён")
class CurrentAdminTransactionRequired : RuntimeException("A writable participant JPA transaction is required")
