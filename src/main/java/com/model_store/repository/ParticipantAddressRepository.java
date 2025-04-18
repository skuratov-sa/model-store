package com.model_store.repository;

import com.model_store.model.base.ParticipantAddress;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;

@Repository
public interface ParticipantAddressRepository extends ReactiveCrudRepository<ParticipantAddress, Long> {
    Flux<ParticipantAddress> findByParticipantId(Long participantId);
}