package com.model_store.service;

import com.model_store.model.base.Address;
import reactor.core.publisher.Mono;

import java.util.List;

public interface AddressService {

    Mono<List<String>> getAllRegions();

    Mono<Address> findById(Long id);
}