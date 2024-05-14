package com.model_store.controller;

import com.model_store.model.CreateOrUpdateParticipantRequest;
import com.model_store.model.FindParticipantRequest;
import com.model_store.model.base.Participant;
import com.model_store.service.ParticipantService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequiredArgsConstructor
public class ParticipantController {
    private final ParticipantService participantService;

    @GetMapping(path = "/participant/{id}")
    public Mono<Participant> getParticipant(@PathVariable Long id) {
        return participantService.findById(id);
    }

    @PostMapping(path = "/find/participants")
    public Flux<Participant> findParticipants(@RequestBody FindParticipantRequest searchParams) {
        return participantService.findByParams(searchParams);
    }

    @PostMapping(path = "/participant")
    public Mono<Void> createParticipant(@RequestBody CreateOrUpdateParticipantRequest request) {
        return participantService.createParticipant(request);
    }

    @PutMapping(path = "/participant/{id}")
    public Mono<Void> updateParticipant(@PathVariable Long id, @RequestBody CreateOrUpdateParticipantRequest request) {
        return participantService.updateParticipant(id, request);
    }

    @DeleteMapping(path = "/participant/{id}")
    public Mono<Void> deleteParticipant(@PathVariable Long id) {
        return participantService.deleteParticipant(id);
    }
}