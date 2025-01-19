package com.model_store.repository;

import com.model_store.model.FindParticipantRequest;
import com.model_store.model.base.Participant;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface ParticipantRepository extends ReactiveCrudRepository<Participant, Long> {

    @Query(value = """
            SELECT DISTINCT p.*
            FROM participant p
             LEFT JOIN participant_address pa ON p.id = pa.participant_id
             LEFT JOIN address a ON pa.address_id = a.id
            WHERE (:country IS NULL OR a.country = :country)
            ORDER BY p.created_at
            """)
    Flux<Participant> findBySearchParams(String country);


    default Flux<Participant> findByParams(FindParticipantRequest searchParams) {
        return findBySearchParams(searchParams.getCountry());
    }

    @Query("SELECT * FROM participant WHERE status = 'ACTIVE' AND id = :participantId")
    Mono<Participant> findActualParticipant(Long participantId);
}