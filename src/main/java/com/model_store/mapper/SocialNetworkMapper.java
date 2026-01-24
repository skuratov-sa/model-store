package com.model_store.mapper;

import com.model_store.model.base.SocialNetwork;
import com.model_store.model.dto.SocialNetworkDto;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface SocialNetworkMapper {
    SocialNetwork toSocialNetwork(SocialNetworkDto socialNetworkDto, Long participantId);

    default SocialNetwork toUpdateSocialNetwork(SocialNetwork sn, SocialNetworkDto dto, Long participantId){
        var fromDto = toSocialNetwork(dto,participantId);
        fromDto.setId(sn.getId());
        return fromDto;
    }
}
