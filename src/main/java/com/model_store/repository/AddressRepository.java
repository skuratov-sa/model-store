package com.model_store.repository;

import com.model_store.model.base.Address;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;

@Repository
public interface AddressRepository extends ReactiveCrudRepository<Address, Long> {

    @Query("""
            SELECT a.*
            FROM participant
                     INNER JOIN participant_address pa on participant.id = pa.participant_id
                     INNER JOIN address a on pa.address_id = a.id
            WHERE participant_id = :participantId
            """)
    Flux<Address> findByParticipantId(Long participantId);

    @Query("SELECT DISTINCT country FROM address")
    Flux<String> findDistinctCountry();
}