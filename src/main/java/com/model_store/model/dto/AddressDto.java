package com.model_store.model.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AddressDto {
    private String country;
    private String city;
    private String street;
    private String houseNumber;
    private String apartmentNumber;
    private Integer index;
}
