package com.model_store.model;

import com.model_store.model.dto.AccountDto;
import com.model_store.model.dto.AddressDto;
import com.model_store.model.dto.SocialNetworkDto;
import com.model_store.model.dto.TransferDto;
import lombok.Data;

import java.util.List;

@Data
public class CreateParticipantRequest {
    private String mail;
    private String password;
}