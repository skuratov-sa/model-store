package com.model_store.service;

import com.model_store.model.UpdateParticipantRequest;
import com.model_store.model.CreateParticipantRequest;
import com.model_store.model.FindParticipantRequest;
import com.model_store.model.base.Participant;
import com.model_store.model.constant.ParticipantStatus;
import com.model_store.model.dto.FindParticipantByLoginDto;
import com.model_store.model.dto.FindParticipantsDto;
import com.model_store.model.dto.FullParticipantDto;
import com.model_store.model.dto.UserInfoDto;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface ParticipantService {
    Mono<FullParticipantDto> findActualById(Long id);

    Mono<FindParticipantByLoginDto> findByLogin(String login);
    Mono<FindParticipantByLoginDto> findByMail(String mail);
    Mono<String> findFullNameById(Long participantId);

    Flux<FindParticipantsDto> findByParams(FindParticipantRequest searchParams);

    @Transactional
    Mono<Participant> activateUser(Long userId);

    Mono<Long> createParticipant(CreateParticipantRequest request);

    /**
     * Сохраняем адреса соц сети картинки (Если они есть)
     * Обновление фото происходит отдельным запросом, фото встают в статус temporary, этот метод обновляет все зависшие фото в active
     * Зависшие фото удаляются раз в 10 минут
     *
     * @param id      - идетификатор пользователя
     * @param request - обновленные параметры
     */
    Mono<Long> updateParticipant(Long id, UpdateParticipantRequest request);

    Mono<Void> deleteParticipant(Long id);

    /**
     * Поиск пользователя для отображения в списках заказов
     *
     * @param id
     * @return
     */
    Mono<UserInfoDto> findShortInfo(Long id);

    Mono<Void> updateParticipantStatus(Long participantId, ParticipantStatus status);

    Mono<Long> updateParticipantPassword(Long participantId, String password, String newPassword);
}