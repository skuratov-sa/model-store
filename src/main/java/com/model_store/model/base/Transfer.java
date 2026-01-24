package com.model_store.model.base;

import com.model_store.model.constant.Currency;
import com.model_store.model.constant.ShippingMethodsType;
import com.model_store.model.constant.TransferStatus;
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
@Table(name = "transfer")
public class Transfer {

    @Id
    private Long id;
    private ShippingMethodsType sending;
    private Integer price;
    private Currency currency;
    private Long participantId;
    private TransferStatus status;
}