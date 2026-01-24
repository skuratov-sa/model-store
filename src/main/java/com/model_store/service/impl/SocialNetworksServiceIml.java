package com.model_store.service.impl;

import com.model_store.exception.ApiErrors;
import com.model_store.exception.constant.ErrorCode;
import com.model_store.mapper.SocialNetworkMapper;
import com.model_store.model.base.SocialNetwork;
import com.model_store.model.dto.SocialNetworkDto;
import com.model_store.repository.SocialNetworkRepository;
import com.model_store.service.SocialNetworksService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class SocialNetworksServiceIml implements SocialNetworksService {
    private final SocialNetworkRepository socialNetworkRepository;
    private final SocialNetworkMapper socialNetworkMapper;

    @Override
    @Transactional
    public Mono<Void> create(Long participantId, SocialNetworkDto dto) {
        return socialNetworkRepository.existsByParticipantIdAndType(participantId, dto.getType())
                .flatMap(exists -> exists
                        ? Mono.error(ApiErrors.alreadyExist(ErrorCode.SOCIAL_NETWORK_ALREADY_EXISTS, "Такая соцсеть уже добавлена"))
                        : socialNetworkRepository.save(socialNetworkMapper.toSocialNetwork(dto, participantId)).then()
                );
    }

    @Override
    public Flux<SocialNetwork> findByParticipantId(Long participantId) {
        return socialNetworkRepository.findByParticipantId(participantId)
                .switchIfEmpty(Mono.error(ApiErrors.notFound(ErrorCode.SOCIAL_NETWORK_NOT_FOUND, "Социальные сети отсутствуют")));
    }

    @Override
    public Mono<Void> delete(Long participantId, Long id) {
        return socialNetworkRepository.findById(id)
                .filter(s -> s.getParticipantId().equals(participantId))
                .switchIfEmpty(Mono.error(ApiErrors.notFound(ErrorCode.SOCIAL_NETWORK_NOT_FOUND, "Социальная сеть не найдена")))
                .flatMap(socialNetworkRepository::delete);
    }

    @Transactional
    @Override
    public Mono<SocialNetwork> update(Long participantId, Long id, SocialNetworkDto dto) {
        return socialNetworkRepository.findById(id)
                .filter(sn -> sn.getParticipantId().equals(participantId))
                .switchIfEmpty(Mono.error(ApiErrors.notFound(ErrorCode.SOCIAL_NETWORK_NOT_FOUND, "Социальная сеть не найдена")))
                .flatMap(sn -> {
                    var updateSN = socialNetworkMapper.toUpdateSocialNetwork(sn, dto, participantId);
                    return socialNetworkRepository.save(updateSN);
                });
    }
}