package com.model_store.repository;

import com.model_store.model.FindParticipantRequest;
import com.model_store.model.base.Participant;
import com.model_store.model.constant.SellerStatus;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface ParticipantRepository extends ReactiveCrudRepository<Participant, Long> {

    Mono<Participant> findByLogin(String login);

    Mono<Participant> findByMail(String mail);

    @Query("SELECT full_name FROM participant WHERE id = :id")
    Mono<String> findFullNameById(@Param("id") Long id);

    @Query("SELECT nextval('participant_id_seq') AS id")
    Mono<Long> findNextParticipantIdSeq();

    @Query("SELECT login FROM participant WHERE id = :id")
    Mono<String> findLoginById(Long id);

    @Query(value = """
               SELECT DISTINCT p.*
            FROM participant p
            WHERE
                p.status = 'ACTIVE' AND
                (
                    (:name IS NULL OR p.login ILIKE '%' || :name || '%') OR
                    (:name IS NULL OR p.mail ILIKE '%' || :name || '%') OR
                    (:name IS NULL OR p.full_name ILIKE '%' || :name || '%')
                ) AND
                (:id IS NULL OR p.id = :id)
            ORDER BY p.created_at DESC;
            """)
    Flux<Participant> findBySearchParams(Long id, String name);


    default Flux<Participant> findByParams(FindParticipantRequest searchParams) {
        return findBySearchParams(searchParams.getId(), searchParams.getName());
    }

    @Query("SELECT * FROM participant WHERE status = 'ACTIVE' AND id = :participantId")
    Mono<Participant> findActualParticipant(Long participantId);

    Flux<Participant> findBySellerStatus(SellerStatus status);


}