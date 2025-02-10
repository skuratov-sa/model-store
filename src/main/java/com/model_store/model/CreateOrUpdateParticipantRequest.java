package com.model_store.model;

import com.model_store.model.dto.AccountDto;
import com.model_store.model.dto.AddressDto;
import com.model_store.model.dto.SocialNetworkDto;
import com.model_store.model.dto.TransferDto;
import lombok.Data;

import java.util.List;

@Data
public class CreateOrUpdateParticipantRequest {
    private String login;
    private String mail;
    private String password;
    private String fullName;
    private String phoneNumber;
    private Integer deadlineSending;
    private Integer deadlinePayment;
    private List<SocialNetworkDto> socialNetworks;
    private List<AddressDto> address;
    private List<Long> imageIds;
    private List<AccountDto> accounts;
    private List<TransferDto> transfers;
}