package com.model_store.mapper;

import com.model_store.model.base.Account;
import com.model_store.model.dto.AccountDto;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface AccountMapper {
    Account toAccount(AccountDto dto, Long participantId);
}