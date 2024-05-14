package com.model_store.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.model_store.model.base.Address;
import com.model_store.model.base.SocialNetwork;
import com.model_store.model.constant.ParticipantStatus;
import lombok.Data;

import java.time.Instant;
import java.util.List;

@Data
public class CreateOrUpdateParticipantRequest {
    private String login;
    private String mail;
    private String fullName;
    private String phone;
    private ParticipantStatus status;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
    private Instant createdAt;

    private List<SocialNetwork> socialNetworks;
    private List<Address> address;

    private List<String> images;

}