package com.model_store.repository;

import com.model_store.model.base.Participant;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ParticipantRepository extends ReactiveCrudRepository<Participant, Long> {

//    @Query(value = """
//            SELECT *
//            FROM participant p
//            WHERE
//                (:participantId IS NULL OR p.id = :participantId) AND
//                (:fullName IS NULL OR upper(p.fullName) LIKE '%' || upper(:fullName) || '%') AND
//                (:mail IS NULL OR upper(p.mail) LIKE '%' || upper(:mail) || '%') AND
//                (:phoneNumber IS NULL OR p.phone = :phoneNumber)
//            ORDER BY p.createdAt
//            """, nativeQuery = true)
//    Flux<Participant> findByParams(@Param("participantId") Long participantId,
//                                   @Param("fullName") String fullName,
//                                   @Param("mail") String mail,
//                                   @Param("phoneNumber") String phoneNumber);
//
//
//    default Flux<Participant> findByParams(FindParticipantRequest searchParams) {
//        return findByParams(
//                searchParams.getParticipantId(),
//                searchParams.getFullName(),
//                searchParams.getMail(),
//                searchParams.getPhoneNumber()
//        );
//    }
}