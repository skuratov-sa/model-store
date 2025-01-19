package com.model_store.service;

import com.model_store.model.CreateOrUpdateParticipantRequest;
import com.model_store.model.FindParticipantRequest;
import com.model_store.model.dto.FindParticipantsDto;
import com.model_store.model.dto.FullParticipantDto;
import com.model_store.model.dto.UserInfoDto;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface ParticipantService {
    Mono<FullParticipantDto> findActualById(Long id);

    Flux<FindParticipantsDto> findByParams(FindParticipantRequest searchParams);

    Mono<Void> createParticipant(CreateOrUpdateParticipantRequest request);

    /**
     * Сохраняем адреса соц сети картинки (Если они есть)
     * Обновление фото происходит отдельным запросом, фото встают в статус temporary, этот метод обновляет все зависшие фото в active
     * Зависшие фото удаляются раз в 10 минут
     *
     * @param id      - идетификатор пользователя
     * @param request - обновленные параметры
     */
    Mono<Void> updateParticipant(Long id, CreateOrUpdateParticipantRequest request);

    Mono<Void> deleteParticipant(Long id);

    /**
     * Поиск пользователя для отображения в списках заказов
     *
     * @param id
     * @return
     */
    Mono<UserInfoDto> findShortInfo(Long id);
}