package com.model_store.model;

import com.model_store.model.constant.ParticipantStatus;
import com.model_store.model.dto.AddressDto;
import com.model_store.model.dto.SocialNetworkDto;
import lombok.Data;

import java.util.List;

@Data
public class CreateOrUpdateParticipantRequest {
    private String login;
    private String mail;
    private String fullName;
    private String phoneNumber;
    private ParticipantStatus status;
    private List<SocialNetworkDto> socialNetworks;
    private List<AddressDto> address;
    private List<Long> imageIds;
}