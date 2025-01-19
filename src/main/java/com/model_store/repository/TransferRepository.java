package com.model_store.repository;

import com.model_store.model.base.Transfer;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;

@Repository
public interface TransferRepository extends ReactiveCrudRepository<Transfer, Long> {
    Flux<Transfer> findByParticipantId(Long participantId);
}