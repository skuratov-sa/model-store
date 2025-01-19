package com.model_store.mapper;

import com.model_store.model.base.Address;
import com.model_store.model.dto.AddressDto;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface AddressMapper {
    Address toAddress(AddressDto addressDto);
}