package com.model_store.repository;

import com.model_store.model.base.Account;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;

@Repository
public interface AccountRepository extends ReactiveCrudRepository<Account, Long> {
    Flux<Account> findByParticipantId(Long participantId);

    @Query("SELECT transfer_money::text FROM account WHERE participant_id = :participantId")
    Flux<String> findTypeByParticipantId(Long participantId);
}