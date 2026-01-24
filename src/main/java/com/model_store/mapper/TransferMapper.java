package com.model_store.mapper;

import com.model_store.model.base.Transfer;
import com.model_store.model.dto.TransferDto;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface TransferMapper {

    Transfer toTransfer(TransferDto dto, Long participantId);

    default Transfer toUpdateTransfer(Transfer transfer, TransferDto dto, Long participantId){
        var fromDto = toTransfer(dto, participantId);
        fromDto.setId(transfer.getId());
        fromDto.setStatus(transfer.getStatus());
        return fromDto;
    }
}