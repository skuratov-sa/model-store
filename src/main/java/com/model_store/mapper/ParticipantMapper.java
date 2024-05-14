package com.model_store.mapper;

import com.model_store.model.CreateOrUpdateParticipantRequest;
import com.model_store.model.base.Participant;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface ParticipantMapper {

    @Mapping(target = "images", ignore = true)
    @Mapping(target = "address", ignore = true)
    @Mapping(target = "products", ignore = true)
    @Mapping(target = "socialNetworks", ignore = true)
    Participant toParticipant(CreateOrUpdateParticipantRequest i);
}