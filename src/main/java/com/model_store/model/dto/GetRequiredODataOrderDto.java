package com.model_store.model.dto;

import com.model_store.model.base.Address;
import com.model_store.model.base.Transfer;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class GetRequiredODataOrderDto {
    private List<Address> addresses;
    private List<Transfer> sellerTransfers;
}
