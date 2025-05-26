package com.model_store.service;

import com.model_store.model.base.SocialNetwork;
import com.model_store.model.dto.SocialNetworkDto;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface SocialNetworksService {
    Mono<Void> create(Long participantId, SocialNetworkDto dto);
    Flux<SocialNetwork> findByParticipantId(Long participantId);
    Mono<Void> delete(Long participantId, Long id);
}