package com.model_store.controller;

import com.model_store.model.CreateOrUpdateParticipantRequest;
import com.model_store.model.FindParticipantRequest;
import com.model_store.model.dto.FindParticipantsDto;
import com.model_store.model.dto.FullParticipantDto;
import com.model_store.service.ParticipantService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequiredArgsConstructor
public class ParticipantController {
    private final ParticipantService participantService;

    @Operation(summary = "Поиск пользователя по id")
    @GetMapping(path = "/participant/{id}")
    public Mono<FullParticipantDto> getParticipant(@PathVariable Long id) {
        return participantService.findActualById(id);
    }

    @Operation(summary = "Поиск пользователей по параметрам")
    @PostMapping(path = "/participants/find")
    public Flux<FindParticipantsDto> findParticipants(@RequestBody FindParticipantRequest searchParams) {
        return participantService.findByParams(searchParams);
    }

    @Operation(summary = "Создать пользователя")
    @PostMapping(path = "/participant")
    public Mono<Long> createParticipant(@RequestBody CreateOrUpdateParticipantRequest request) {
        return participantService.createParticipant(request);
    }

    @Operation(summary = "Обновить пользователя по id")
    @PutMapping(path = "/participant/{id}")
    public Mono<Void> updateParticipant(@PathVariable Long id,
                                        @RequestBody CreateOrUpdateParticipantRequest request) {
        return participantService.updateParticipant(id, request);
    }

    @Operation(summary = "Удалить пользователя по id")
    @DeleteMapping(path = "/admin/actions/participant/{id}")
    public Mono<Void> deleteParticipant(@PathVariable Long id) {
        return participantService.deleteParticipant(id);
    }
}