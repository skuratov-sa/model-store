package com.model_store.mapper;

import com.model_store.model.UpdateParticipantRequest;
import com.model_store.model.CreateParticipantRequest;
import com.model_store.model.base.Account;
import com.model_store.model.base.Address;
import com.model_store.model.base.Participant;
import com.model_store.model.base.SellerRating;
import com.model_store.model.base.SocialNetwork;
import com.model_store.model.base.Transfer;
import com.model_store.model.constant.ParticipantStatus;
import com.model_store.model.dto.AddressDto;
import com.model_store.model.dto.FindParticipantByLoginDto;
import com.model_store.model.dto.FindParticipantsDto;
import com.model_store.model.dto.FullParticipantDto;
import com.model_store.model.dto.UserInfoDto;
import org.mapstruct.Mapper;

import java.util.List;

@Mapper(componentModel = "spring")
public interface ParticipantMapper {
    Participant toParticipant(UpdateParticipantRequest request, ParticipantStatus status);

    Participant toParticipant(CreateParticipantRequest request, ParticipantStatus status);


    FindParticipantByLoginDto toFindParticipantByLoginDto(Participant participant, Long imageId);

    List<Address> toAddress(List<AddressDto> addressDto);

    FindParticipantsDto toFindParticipantDto(Participant participant, Long imageId);

    FullParticipantDto toFullParticipantDto(Participant participant, List<Address> addresses, List<Account> accounts,
                                            List<Transfer> transfers, Long imageId, SellerRating sellerRating, List<SocialNetwork> socialNetworks
    );

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