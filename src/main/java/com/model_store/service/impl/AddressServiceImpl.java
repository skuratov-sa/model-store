package com.model_store.service.impl;

import com.amazonaws.services.kms.model.NotFoundException;
import com.model_store.mapper.AddressMapper;
import com.model_store.model.base.Address;
import com.model_store.model.base.ParticipantAddress;
import com.model_store.model.dto.AddressDto;
import com.model_store.repository.AddressRepository;
import com.model_store.repository.ParticipantAddressRepository;
import com.model_store.service.AddressService;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AddressServiceImpl implements AddressService {
    private final ParticipantAddressRepository participantAddressRepository;
    private final AddressRepository addressRepository;
    private final AddressMapper addressMapper;

    @Override
    public Mono<List<String>> getAllRegions() {
        return addressRepository.findDistinctCountry().collectList();
    }

    @Override
    public Mono<Address> findById(Long id) {
        return addressRepository.findById(id);
    }

    @Override
    public Flux<Address> findByParticipantId(Long participantId) {
        return addressRepository.findByParticipantId(participantId);
    }

    @Override
    public Flux<Address> findByParticipantIdOrException(Long participantId) {
        return addressRepository.findByParticipantId(participantId);
    }

    @Override
    @Transactional
    public Mono<Long> addAddresses(Long participantId, @NonNull AddressDto addresses) {
        return addressRepository.save(addressMapper.toAddress(addresses))
                .flatMap(savedAddress -> participantAddressRepository.save( // Создаём новые связи
                        ParticipantAddress.builder()
                                .addressId(savedAddress.getId())
                                .participantId(participantId)
                                .build())
                ).map(ParticipantAddress::getAddressId)
                .doOnError(throwable -> log.error("Ошибка при сохранении адреса: {}", throwable.getMessage()));
    }

    @Override
    @Transactional
    public Mono<Void> deleteAddresses(Long participantId, @NonNull Long addressId) {
        return participantAddressRepository.findByParticipantId(participantId)
                .filter(address -> address.getAddressId().equals(addressId))
                .switchIfEmpty(Mono.error(new NotFoundException("Адрес не был найден у данного пользователя")))
                .flatMap(participantAddress ->
                                addressRepository.deleteById(addressId)
                                        .then(participantAddressRepository.deleteById(participantAddress.getId()))
                ).then();
    }

    @Override
    public Flux<Address> getAddress(Long participantId) {
        return addressRepository.findByParticipantId(participantId);
    }
}