package com.model_store.mapper;

import com.model_store.model.base.Address;
import com.model_store.model.dto.AddressDto;
import lombok.NonNull;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface AddressMapper {
    Address toAddress(AddressDto addressDto);

    default Address toUpdateAddress(Address address, @NonNull AddressDto dto) {
        var fromDto = toAddress(dto);
        fromDto.setId(address.getId());
        fromDto.setStatus(address.getStatus());
        return fromDto;
    }
}