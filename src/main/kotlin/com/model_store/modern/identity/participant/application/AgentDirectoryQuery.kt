package com.model_store.modern.identity.participant.application

data class AgentDirectoryEntry(val id: Long, val login: String?, val status: String)

/** Identity authorizes the current non-agent ACTIVE administrator, exposing only scalar snapshots. */
interface AgentDirectoryQuery {
    fun agents(callerAdminId: Long): List<AgentDirectoryEntry>
    fun requireActiveAdmin(callerAdminId: Long)
}
