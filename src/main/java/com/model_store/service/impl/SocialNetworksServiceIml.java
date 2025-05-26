package com.model_store.service.impl;

import com.amazonaws.services.kms.model.NotFoundException;
import com.model_store.mapper.SocialNetworkMapper;
import com.model_store.model.base.Account;
import com.model_store.model.base.SocialNetwork;
import com.model_store.model.dto.SocialNetworkDto;
import com.model_store.repository.SocialNetworkRepository;
import com.model_store.service.SocialNetworksService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class SocialNetworksServiceIml implements SocialNetworksService {
    private final SocialNetworkRepository socialNetworkRepository;
    private final SocialNetworkMapper socialNetworkMapper;

    @Override
    public Mono<Void> create(Long participantId, SocialNetworkDto dto) {
        return socialNetworkRepository.findByParticipantId(participantId)
                .map(SocialNetwork::getLogin).collectList()
                .flatMap(socialNetworks -> {
                    if (socialNetworks.contains(dto.getLogin())) return Mono.empty();
                    else return socialNetworkRepository.save(socialNetworkMapper.toSocialNetwork(dto, participantId));
                }).then();
    }

    @Override
    public Flux<SocialNetwork> findByParticipantId(Long participantId) {
        return socialNetworkRepository.findByParticipantId(participantId)
                .switchIfEmpty(Mono.error(new NotFoundException("Social network not found with participantId: " + participantId)));
    }

    @Override
    public Mono<Void> delete(Long participantId, Long id) {
        return socialNetworkRepository.findById(id)
                .filter(s -> s.getParticipantId().equals(participantId))
                .switchIfEmpty(Mono.error(new NotFoundException("Social network not found with id: " + id)))
                .flatMap(socialNetworkRepository::delete);
    }
}