package com.model_store.service;

import com.model_store.model.CreateOrUpdateParticipantRequest;
import com.model_store.model.FindParticipantRequest;
import com.model_store.model.base.Participant;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface ParticipantService {
    Mono<Participant> findById(Long id);

    Flux<Participant> findByParams(FindParticipantRequest searchParams);

    Mono<Void> createParticipant(CreateOrUpdateParticipantRequest request);

    /**
     * Сохраняем адреса соц сети картинки (Если они есть)
     *
     * @param id      - идетификатор пользователя
     * @param request - обновленные параметры
     */
    Mono<Void> updateParticipant(Long id, CreateOrUpdateParticipantRequest request);

    Mono<Void> deleteParticipant(Long id);
}