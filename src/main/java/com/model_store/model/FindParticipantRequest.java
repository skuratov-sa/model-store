package com.model_store.model;

import lombok.Data;

@Data
public class FindParticipantRequest {
    private Long participantId;
    private String fullName;
    private String mail;
    private String phoneNumber;
}