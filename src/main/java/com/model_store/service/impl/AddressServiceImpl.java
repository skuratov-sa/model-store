package com.model_store.service.impl;

import com.model_store.exception.ApiErrors;
import com.model_store.exception.constant.ErrorCode;
import com.model_store.mapper.AddressMapper;
import com.model_store.model.base.Address;
import com.model_store.model.base.ParticipantAddress;
import com.model_store.model.constant.AddressStatus;
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
        return addressRepository.findByParticipantId(participantId)
                .filter(address -> address.getStatus() == AddressStatus.ACTIVE)
                .switchIfEmpty(Mono.error(ApiErrors.notFound(ErrorCode.ADDRESS_NOT_FOUND, "У данного пользователя отсутствуют адреса доставки")));
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

    @Transactional
    @Override
    public Mono<Address> updateAddress(Long participantId, Long addressId, @NonNull AddressDto dto) {
        return participantAddressRepository.findByParticipantId(participantId)
                .filter(pa -> pa.getAddressId().equals(addressId))
                .switchIfEmpty(Mono.error(ApiErrors.notFound(ErrorCode.ADDRESS_NOT_FOUND, "Адрес доставки не найден")))
                .then(addressRepository.findById(addressId)
                        .filter(address -> AddressStatus.ACTIVE.equals(address.getStatus()))
                        .switchIfEmpty(Mono.error(ApiErrors.notFound(ErrorCode.ADDRESS_NOT_FOUND, "Адрес доставки не найден")))
                )
                .flatMap(address -> {
                    var updateAdress = addressMapper.toUpdateAddress(address, dto);
                    return addressRepository.save(updateAdress);
                })
                .doOnError(e -> log.error("Ошибка при обновлении адреса {}: {}", addressId, e.getMessage()));
    }

    @Override
    @Transactional
    public Mono<Void> softDelete(Long participantId, @NonNull Long addressId) {
        return participantAddressRepository.findByParticipantId(participantId)
                .filter(pa -> pa.getAddressId().equals(addressId))
                .switchIfEmpty(Mono.error(ApiErrors.notFound(ErrorCode.ADDRESS_NOT_FOUND, "Адрес доставки не найден")))
                .flatMap(pa ->
                        participantAddressRepository.deleteById(pa.getId())
                                .then(addressRepository.findById(addressId)
                                        .filter(address -> AddressStatus.ACTIVE.equals(address.getStatus()))
                                        .switchIfEmpty(Mono.error(ApiErrors.notFound(ErrorCode.ADDRESS_NOT_FOUND, "Адрес доставки не найден")))
                                        .flatMap(address -> {
                                            address.setStatus(AddressStatus.DELETED);
                                            return addressRepository.save(address);
                                        })
                                )
                )
                .then();
    }

    @Override
    public Flux<Address> getAddress(Long participantId) {
        return addressRepository.findByParticipantId(participantId);
    }
}