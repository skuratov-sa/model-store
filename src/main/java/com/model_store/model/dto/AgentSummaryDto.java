package com.model_store.model.dto;

import com.model_store.model.constant.ParticipantStatus;

public record AgentSummaryDto(Long id, String login, ParticipantStatus status) {
}
