package com.model_store.model.dto;

public record UpdateAgentProfileRequest(
        String fullName,
        String phoneNumber,
        Integer deadlineSending,
        Integer deadlinePayment,
        Long imageId
) {
}
