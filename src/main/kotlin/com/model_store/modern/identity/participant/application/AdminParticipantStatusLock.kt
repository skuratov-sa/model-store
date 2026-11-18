package com.model_store.modern.identity.participant.application

/**
 * Requires the caller's writable participant JPA transaction before any SQL.
 * Locks distinct caller/target IDs in ascending order until that transaction ends.
 * Checks current ACTIVE non-agent ADMIN before reporting an absent target.
 * Returns only after an existing target is locked, including caller == target.
 * Uses CurrentAdminForbidden / CurrentAdminTransactionRequired for authorization/resources.
 */
interface AdminParticipantStatusLock {
    fun lockActiveAdminAndTarget(callerId: Long, targetId: Long)
}
