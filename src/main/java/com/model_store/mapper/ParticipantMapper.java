package com.model_store.mapper;

import com.model_store.model.CreateOrUpdateParticipantRequest;
import com.model_store.model.base.Participant;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface ParticipantMapper {
    Participant toParticipant(CreateOrUpdateParticipantRequest i);
}