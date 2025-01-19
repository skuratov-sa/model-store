package com.model_store.service.impl;

import com.model_store.model.base.Address;
import com.model_store.repository.AddressRepository;
import com.model_store.service.AddressService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AddressServiceImpl implements AddressService {
    private final AddressRepository addressRepository;

    @Override
    public Mono<List<String>> getAllRegions() {
        return addressRepository.findDistinctCountry().collectList();
    }

    @Override
    public Mono<Address> findById(Long id) {
        return addressRepository.findById(id);
    }
}