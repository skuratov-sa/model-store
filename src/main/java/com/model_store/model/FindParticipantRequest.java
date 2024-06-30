package com.model_store.model;

import com.model_store.model.constant.ParticipantStatus;
import lombok.Data;

@Data
public class FindParticipantRequest {
    private String fullName;
    private String mail;
    private String phoneNumber;
    private String login;
    private ParticipantStatus status;
}