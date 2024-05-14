package com.model_store.repository;

import com.model_store.model.base.SocialNetwork;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SocialNetworkRepository extends ReactiveCrudRepository<SocialNetwork, Long> {}