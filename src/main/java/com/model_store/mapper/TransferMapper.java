package com.model_store.mapper;

import com.model_store.model.base.Transfer;
import com.model_store.model.dto.TransferDto;
import org.mapstruct.Mapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Mapper(componentModel = "spring")
public interface TransferMapper {

    default List<Transfer> toTransfer(List<TransferDto> requests, List<Transfer> transfers, Long participantId) {
        var transferMap = transfers.stream()
                .collect(Collectors.toMap(Transfer::getSending, Function.identity()));

        requests.forEach(request -> transferMap.put(request.getSending(),
                Transfer.builder()
                        .id(Optional.ofNullable(transferMap.get(request.getSending()))
                                .map(Transfer::getId)
                                .orElse(null))
                        .sending(request.getSending())
                        .price(request.getPrice())
                        .currency(request.getCurrency())
                        .participantId(participantId)
                        .build()
        ));

        return new ArrayList<>(transferMap.values());
    }

}