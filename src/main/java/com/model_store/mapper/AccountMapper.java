package com.model_store.mapper;

import com.model_store.model.base.Account;
import com.model_store.model.dto.AccountDto;
import org.mapstruct.Mapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Mapper(componentModel = "spring")
public interface AccountMapper {


    default List<Account> toAccount(List<AccountDto> requests, List<Account> accounts, Long participantId) {
        var accountMap = accounts.stream()
                .collect(Collectors.toMap(Account::getTransferMoney, Function.identity()));

        requests.forEach(request -> accountMap.put(request.getTransferMoney(),
                Account.builder()
                        .id(Optional.ofNullable(accountMap.get(request.getTransferMoney()))
                                .map(Account::getId)
                                .orElse(null))
                        .entityValue(request.getEntityValue())
                        .username(request.getUsername())
                        .transferMoney(request.getTransferMoney())
                        .comment(request.getComment())
                        .participantId(participantId)
                        .build()
        ));
        return new ArrayList<>(accountMap.values());
    }
}