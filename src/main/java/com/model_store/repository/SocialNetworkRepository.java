package com.model_store.repository;

import com.model_store.model.base.SocialNetwork;
import com.model_store.model.constant.SocialNetworkType;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface SocialNetworkRepository extends ReactiveCrudRepository<SocialNetwork, Long> {
    Mono<Void> deleteByParticipantId(Long participantId);

    Flux<SocialNetwork> findByParticipantId(Long participantId);

    @Query("SELECT EXISTS (SELECT 1 FROM social_network WHERE participant_id = :participantId AND type = CAST(:type AS social_network_type) )")
    Mono<Boolean> existsByParticipantIdAndType(Long participantId, SocialNetworkType type);
}