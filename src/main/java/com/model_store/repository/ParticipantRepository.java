package com.model_store.repository;

import com.model_store.model.FindParticipantRequest;
import com.model_store.model.base.Participant;
import com.model_store.model.constant.ParticipantStatus;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface ParticipantRepository extends ReactiveCrudRepository<Participant, Long> {

    @Query(value = """
            SELECT *
            FROM participant p
            WHERE
                (:fullName IS NULL OR p.full_name = :fullName) AND
                (:mail IS NULL OR p.mail = :mail) AND
                (:phoneNumber IS NULL OR p.phone_number = :phoneNumber) AND
                (:login IS NULL OR p.login = :login) AND
                (:status IS NULL OR p.status = :status::participant_status)
            ORDER BY p.created_at
            """)
    Flux<Participant> findByParams(String fullName,
                                   String mail,
                                   String phoneNumber,
                                   String login,
                                   ParticipantStatus status);


    default Flux<Participant> findByParams(FindParticipantRequest searchParams) {
        return findByParams(
                searchParams.getFullName(),
                searchParams.getMail(),
                searchParams.getPhoneNumber(),
                searchParams.getLogin(),
                searchParams.getStatus()
        );
    }

    @Query("SELECT * FROM participant WHERE status = 'ACTIVE' AND id = :participantId")
    Mono<Participant> findActualParticipant(Long participantId);

}