package com.model_store.mapper;

import com.model_store.model.CreateOrUpdateParticipantRequest;
import com.model_store.model.base.Address;
import com.model_store.model.base.Participant;
import com.model_store.model.base.SocialNetwork;
import com.model_store.model.dto.AddressDto;
import com.model_store.model.dto.FindParticipantsDto;
import com.model_store.model.dto.FullParticipantDto;
import com.model_store.model.dto.SocialNetworkDto;
import org.mapstruct.Mapper;

import java.util.List;

@Mapper(componentModel = "spring")
public interface ParticipantMapper {
    Participant toParticipant(CreateOrUpdateParticipantRequest request);

    List<Address> toAddress(List<AddressDto> addressDto);

    SocialNetwork toSocialNetwork(SocialNetworkDto socialNetworkDto, Long participantId);

    FindParticipantsDto toFindParticipantDto(Participant participant, Long imageId);

    FullParticipantDto toFullParticipantDto(Participant participant, List<Long> imageIds);
}