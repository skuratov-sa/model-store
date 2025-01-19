package com.model_store.model.dto;

import com.model_store.model.constant.TransferMoneyType;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AccountDto {
    private TransferMoneyType transferMoney;
    private String username;
    private String entityValue;
    private String comment;
}