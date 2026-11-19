package com.model_store.model.dto;

public record OrderPaymentRequest(Long expectedVersion, Long imageId, String reason) { }
