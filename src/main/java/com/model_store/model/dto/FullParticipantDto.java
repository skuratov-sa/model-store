package com.model_store.model.dto;

import com.model_store.model.base.Account;
import com.model_store.model.base.Address;
import com.model_store.model.base.Transfer;
import com.model_store.model.constant.ParticipantStatus;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class FullParticipantDto {

    private Long id;
    private String login;
    private String mail;
    private String fullName;
    private String phoneNumber;
    private ParticipantStatus status;
    private List<Long> imageIds;
    private List<Address> addresses;
    private List<Account> accounts;
    private List<Transfer> transfers;
}
