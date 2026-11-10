package com.model_store.modern.identity.participant.maintenance.application

/** No authorization policy. False means a discovered participant disappeared; caller skips the whole item. */
interface MaintenanceParticipantLocks {
    fun lockIds(ids: List<Long>): Boolean
}

class MaintenanceParticipantTransactionRequired : RuntimeException("Writable caller JPA transaction required")
