package com.model_store.model.base;

import com.model_store.model.constant.TransferMoneyType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "account")
public class Account {

    @Id
    private Long id;
    private TransferMoneyType transferMoney;
    private String username;
    private String entityValue;
    private String comment;
    private Long participantId;
}