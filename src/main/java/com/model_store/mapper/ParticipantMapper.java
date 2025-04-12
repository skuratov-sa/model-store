package com.model_store.mapper;

import com.model_store.model.CreateOrUpdateParticipantRequest;
import com.model_store.model.base.Account;
import com.model_store.model.base.Address;
import com.model_store.model.base.Participant;
import com.model_store.model.base.SocialNetwork;
import com.model_store.model.base.Transfer;
import com.model_store.model.constant.ParticipantStatus;
import com.model_store.model.dto.AddressDto;
import com.model_store.model.dto.FindParticipantByLoginDto;
import com.model_store.model.dto.FindParticipantsDto;
import com.model_store.model.dto.FullParticipantDto;
import com.model_store.model.dto.SocialNetworkDto;
import com.model_store.model.dto.UserInfoDto;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Mapper(componentModel = "spring")
public interface ParticipantMapper {
    Participant toParticipant(CreateOrUpdateParticipantRequest request, ParticipantStatus status);

    FindParticipantByLoginDto toFindParticipantByLoginDto(Participant participant, Long imageId);

    List<Address> toAddress(List<AddressDto> addressDto);

    FindParticipantsDto toFindParticipantDto(Participant participant, Long imageId);

    FullParticipantDto toFullParticipantDto(Participant participant, List<Address> addresses, List<Account> accounts, List<Transfer> transfers, List<Long> imageIds);

    default List<SocialNetwork> toSocialNetwork(List<SocialNetworkDto> requests, List<SocialNetwork> socialNetworks, Long participantId) {
        var socialNetworkMap = socialNetworks.stream()
                .collect(Collectors.toMap(SocialNetwork::getType, Function.identity()));

        requests.forEach(request -> socialNetworkMap.put(request.getType(),
                SocialNetwork.builder()
                        .id(Optional.ofNullable(socialNetworkMap.get(request.getType()))
                                .map(SocialNetwork::getId)
                                .orElse(null))
                        .type(request.getType())
                        .login(request.getLogin())
                        .participantId(participantId)
                        .build()
        ));

        return new ArrayList<>(socialNetworkMap.values());
    }

    UserInfoDto toUserInfoDto(Participant participant, Long imageId);

    default Participant toParticipant(String login, String mail, String fullName, String passwordEncoder) {
        return Participant.builder()
                .login(login)
                .password(passwordEncoder)
                .fullName(fullName)
                .mail(mail)
                .status(ParticipantStatus.ACTIVE)  // Указываем статус
                .build();
    }
}