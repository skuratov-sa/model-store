package com.model_store.controller;

import com.model_store.model.UpdateParticipantRequest;
import com.model_store.model.CreateParticipantRequest;
import com.model_store.model.FindParticipantRequest;
import com.model_store.model.base.Participant;
import com.model_store.model.constant.ParticipantStatus;
import com.model_store.model.dto.FindParticipantsDto;
import com.model_store.model.dto.FullParticipantDto;
import com.model_store.service.JwtService;
import com.model_store.service.ParticipantService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequiredArgsConstructor
public class ParticipantController {
    private final ParticipantService participantService;
    private final JwtService jwtService;

    @Operation(summary = "Личный кабинет пользователя")
    @GetMapping(path = "/participant")
    public Mono<FullParticipantDto> getParticipant(@RequestHeader("Authorization") String authorizationHeader) {
        Long participantId = jwtService.getIdByAccessToken(authorizationHeader);
        return participantService.findActualById(participantId);
    }

    @Operation(summary = "Поиск пользователей по параметрам")
    @PostMapping(path = "/participants/find")
    public Flux<FindParticipantsDto> findParticipants(@RequestBody FindParticipantRequest searchParams) {
        return participantService.findByParams(searchParams);
    }

    @Operation(summary = "Создать пользователя")
    @PostMapping(path = "/participant")
    public Mono<Long> createParticipant(@RequestBody CreateParticipantRequest request) {
        return participantService.createParticipant(request);
    }

    @Operation(summary = "Обновить пользователя по id")
    @PutMapping(path = "/participant")
    public Mono<Long> updateParticipant(@RequestHeader("Authorization") String authorizationHeader,
                                               @RequestBody UpdateParticipantRequest request) {
        Long participantId = jwtService.getIdByAccessToken(authorizationHeader);
        return participantService.updateParticipant(participantId, request);
    }

    @Operation(summary = "Удалить анкету текущего пользователя")
    @DeleteMapping(path = "/participant")
    public Mono<Void> deleteParticipant(@RequestHeader("Authorization") String authorizationHeader) {
        Long participantId = jwtService.getIdByAccessToken(authorizationHeader);
        return participantService.updateParticipantStatus(participantId, ParticipantStatus.DELETED);
    }

    @Operation(summary = "Изменить пароль пользователя")
    @PutMapping("/participant/password")
    public Mono<Long> updatePassword(@RequestHeader("Authorization") String authorizationHeader, String oldPassword, String newPassword) {
        Long participantId = jwtService.getIdByAccessToken(authorizationHeader);
        return participantService.updateParticipantPassword(participantId, oldPassword, newPassword);
    }


    @Operation(summary = "Изменить статус пользователя")
    @PutMapping("/admin/actions/participants/{participantId}/status")
    public Mono<Void> updateParticipantStatus(@PathVariable Long participantId) {
        return participantService.updateParticipantStatus(participantId, ParticipantStatus.BLOCKED);
    }
}