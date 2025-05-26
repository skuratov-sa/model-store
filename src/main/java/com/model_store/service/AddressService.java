package com.model_store.service;

import com.model_store.model.base.Address;
import com.model_store.model.dto.AddressDto;
import lombok.NonNull;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

public interface AddressService {
    Mono<List<String>> getAllRegions();
    Mono<Address> findById(Long id);
    Flux<Address> findByParticipantId(Long id);
    Mono<Long> addAddresses(Long participantId, @NonNull AddressDto addresses);
    Mono<Void> deleteAddresses(Long participantId, @NonNull Long addressId);
    Flux<Address> getAddress(Long participantId);
}