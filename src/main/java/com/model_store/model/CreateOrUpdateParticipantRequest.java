package com.model_store.model;

import com.model_store.model.base.Address;
import com.model_store.model.base.SocialNetwork;
import com.model_store.model.constant.ParticipantStatus;
import lombok.Data;

import java.util.List;

@Data
public class CreateOrUpdateParticipantRequest {
    private Long id;
    private String login;
    private String mail;
    private String fullName;
    private String phoneNumber;
    private ParticipantStatus status;

    private List<SocialNetwork> socialNetworks;
    private List<Address> address;
    private List<String> images;
}