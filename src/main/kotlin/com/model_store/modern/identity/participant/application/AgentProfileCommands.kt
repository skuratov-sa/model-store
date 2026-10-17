package com.model_store.modern.identity.participant.application

import com.model_store.modern.identity.participant.domain.Participant
import com.model_store.modern.identity.participant.domain.ParticipantRole
import com.model_store.modern.identity.participant.domain.ParticipantStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** Scalar contract for the administrative agent-profile boundary. Null patch fields mean unchanged. */
data class AgentProfilePatch(
    val fullName: String? = null,
    val phoneNumber: String? = null,
    val deadlineSending: Int? = null,
    val deadlinePayment: Int? = null,
    val imageId: Long? = null,
)

data class AgentProfileSnapshot(
    val id: Long,
    val login: String?,
    val fullName: String?,
    val phoneNumber: String?,
    val imageId: Long?,
    val deadlineSending: Int,
    val deadlinePayment: Int,
    val status: String,
)

sealed class AgentProfileFailure(message: String) : RuntimeException(message) {
    class AdminForbidden : AgentProfileFailure("Active administrator required")
    class AgentNotFound(val agentId: Long) : AgentProfileFailure("Agent $agentId not found")
    class InvalidDeadline(val field: String) : AgentProfileFailure("Invalid $field")
}

interface AgentProfileCommands {
    fun read(callerAdminId: Long, agentId: Long): AgentProfileSnapshot
    fun update(callerAdminId: Long, agentId: Long, patch: AgentProfilePatch): AgentProfileSnapshot
}

@Service
class AgentProfileService(
    private val store: ParticipantStore,
    private val images: ParticipantImagePort,
    private val profiles: ParticipantProfileRead,
    private val accessLock: AgentAccessLockService,
) : AgentProfileCommands {
    @Transactional("transactionManager", readOnly = true)
    override fun read(callerAdminId: Long, agentId: Long): AgentProfileSnapshot {
        requireAdmin(store.find(callerAdminId))
        return snapshot(requireAgent(store.find(agentId), agentId))
    }

    @Transactional("transactionManager")
    override fun update(callerAdminId: Long, agentId: Long, patch: AgentProfilePatch): AgentProfileSnapshot {
        val agent = accessLock.lockAndRequireAgent(callerAdminId, agentId)
        validateDeadline(patch.deadlineSending, "deadlineSending")
        validateDeadline(patch.deadlinePayment, "deadlinePayment")
        if (patch.imageId != null) images.replace(agentId, patch.imageId)
        // The regular profile method also changes login and overwrites nullable fields.
        val saved = store.save(agent.copy(
            fullName = patch.fullName ?: agent.fullName,
            phoneNumber = patch.phoneNumber ?: agent.phoneNumber,
            deadlineSending = patch.deadlineSending ?: agent.deadlineSending,
            deadlinePayment = patch.deadlinePayment ?: agent.deadlinePayment,
        ))
        return snapshot(saved)
    }

    private fun requireAdmin(caller: Participant?) {
        if (caller?.role != ParticipantRole.ADMIN || caller.status != ParticipantStatus.ACTIVE || caller.isAgent)
            throw AgentProfileFailure.AdminForbidden()
    }

    private fun requireAgent(target: Participant?, agentId: Long): Participant =
        target?.takeIf { it.isAgent } ?: throw AgentProfileFailure.AgentNotFound(agentId)

    private fun validateDeadline(value: Int?, field: String) {
        if (value != null && value !in Short.MIN_VALUE..Short.MAX_VALUE)
            throw AgentProfileFailure.InvalidDeadline(field)
    }

    private fun snapshot(agent: Participant) = AgentProfileSnapshot(
        agent.id, agent.login, agent.fullName, agent.phoneNumber, profiles.mainImageId(agent.id),
        agent.deadlineSending, agent.deadlinePayment, agent.status.name,
    )
}
