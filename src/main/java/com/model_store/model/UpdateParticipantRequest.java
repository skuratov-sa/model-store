package com.model_store.model;

import lombok.Data;

import java.util.List;

@Data
public class UpdateParticipantRequest {
    private String login;
    private String fullName;
    private String phoneNumber;
    private Integer deadlineSending;
    private Integer deadlinePayment;
    private List<Long> imageIds;
}