package com.model_store.model.dto;

import com.model_store.model.base.Participant;
import com.model_store.model.constant.ParticipantStatus;

public record AgentProfileDto(
        Long id,
        String login,
        String fullName,
        String phoneNumber,
        Long imageId,
        Integer deadlineSending,
        Integer deadlinePayment,
        ParticipantStatus status
) {
    public static AgentProfileDto from(Participant participant, Long imageId) {
        return new AgentProfileDto(participant.getId(), participant.getLogin(), participant.getFullName(),
                participant.getPhoneNumber(), imageId, participant.getDeadlineSending(),
                participant.getDeadlinePayment(), participant.getStatus());
    }
}
