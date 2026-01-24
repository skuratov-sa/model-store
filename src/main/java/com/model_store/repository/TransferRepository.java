package com.model_store.repository;

import com.model_store.model.base.Transfer;
import com.model_store.model.constant.ShippingMethodsType;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface TransferRepository extends ReactiveCrudRepository<Transfer, Long> {
    Flux<Transfer> findByParticipantId(Long participantId);

    @Query("SELECT EXISTS (SELECT 1 FROM transfer WHERE participant_id = :participantId AND status = 'ACTIVE' AND sending = CAST(:sending AS shipping_methods_type))")
    Mono<Boolean> existsByParticipantIdAndType(Long participantId, ShippingMethodsType sending);
}