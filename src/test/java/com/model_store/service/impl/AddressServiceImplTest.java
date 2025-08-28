package com.model_store.service.impl;

import com.amazonaws.services.kms.model.NotFoundException;
import com.model_store.mapper.AddressMapper;
import com.model_store.model.base.Address;
import com.model_store.model.base.ParticipantAddress;
import com.model_store.model.dto.AddressDto;
import com.model_store.repository.AddressRepository;
import com.model_store.repository.ParticipantAddressRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Arrays;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
@Deprecated

@ExtendWith(MockitoExtension.class)
class AddressServiceImplTest {

    @Mock
    private ParticipantAddressRepository participantAddressRepository;

    @Mock
    private AddressRepository addressRepository;

    @Mock
    private AddressMapper addressMapper;

    @InjectMocks
    private AddressServiceImpl addressService;

    private AddressDto addressDto;
    private Address address;
    private ParticipantAddress participantAddress;

    @BeforeEach
    void setUp() {
        addressDto = AddressDto.builder().city("Test Country").city("Test City").build();


        address = new Address();
        address.setId(1L);
        address.setCountry("Test Country");
        address.setCity("Test City");

        participantAddress = new ParticipantAddress();
        participantAddress.setId(1L);
        participantAddress.setParticipantId(1L);
        participantAddress.setAddressId(1L);
    }

    @Test
    void getAllRegions_shouldReturnDistinctRegions() {
        List<String> regions = Arrays.asList("Region1", "Region2");
        when(addressRepository.findDistinctCountry()).thenReturn(Flux.fromIterable(regions));

        StepVerifier.create(addressService.getAllRegions())
                .expectNext(regions)
                .verifyComplete();

        verify(addressRepository).findDistinctCountry();
    }

    @Test
    void findById_shouldReturnAddress_whenAddressExists() {
        when(addressRepository.findById(1L)).thenReturn(Mono.just(address));

        StepVerifier.create(addressService.findById(1L))
                .expectNext(address)
                .verifyComplete();

        verify(addressRepository).findById(1L);
    }

    @Test
    void findById_shouldReturnEmpty_whenAddressDoesNotExist() {
        when(addressRepository.findById(1L)).thenReturn(Mono.empty());

        StepVerifier.create(addressService.findById(1L))
                .verifyComplete();

        verify(addressRepository).findById(1L);
    }

    @Test
    void findByParticipantId_shouldReturnAddresses_whenParticipantHasAddresses() {
        when(addressRepository.findByParticipantId(1L)).thenReturn(Flux.just(address));

        StepVerifier.create(addressService.findByParticipantId(1L))
                .expectNext(address)
                .verifyComplete();

        verify(addressRepository).findByParticipantId(1L);
    }

    @Test
    void findByParticipantId_shouldReturnEmpty_whenParticipantHasNoAddresses() {
        when(addressRepository.findByParticipantId(1L)).thenReturn(Flux.empty());

        StepVerifier.create(addressService.findByParticipantId(1L))
                .verifyComplete();

        verify(addressRepository).findByParticipantId(1L);
    }

    @Test
    void getAddress_shouldReturnAddresses_whenParticipantHasAddresses() {
        when(addressRepository.findByParticipantId(1L)).thenReturn(Flux.just(address));

        StepVerifier.create(addressService.getAddress(1L))
                .expectNext(address)
                .verifyComplete();

        verify(addressRepository).findByParticipantId(1L);
    }

    @Test
    void getAddress_shouldReturnEmpty_whenParticipantHasNoAddresses() {
        when(addressRepository.findByParticipantId(1L)).thenReturn(Flux.empty());

        StepVerifier.create(addressService.getAddress(1L))
                .verifyComplete();

        verify(addressRepository).findByParticipantId(1L);
    }

    @Test
    void addAddresses_shouldSaveAddressAndParticipantAddress() {
        when(addressMapper.toAddress(addressDto)).thenReturn(address);
        when(addressRepository.save(address)).thenReturn(Mono.just(address));
        when(participantAddressRepository.save(any(ParticipantAddress.class))).thenReturn(Mono.just(participantAddress));

        StepVerifier.create(addressService.addAddresses(1L, addressDto))
                .expectNext(1L) // Expecting the addressId
                .verifyComplete();

        verify(addressMapper).toAddress(addressDto);
        verify(addressRepository).save(address);
        verify(participantAddressRepository).save(any(ParticipantAddress.class));
    }

    @Test
    void addAddresses_shouldReturnError_whenSaveAddressFails() {
        when(addressMapper.toAddress(addressDto)).thenReturn(address);
        when(addressRepository.save(address)).thenReturn(Mono.error(new RuntimeException("Save failed")));

        StepVerifier.create(addressService.addAddresses(1L, addressDto))
                .expectError(RuntimeException.class)
                .verify();

        verify(addressMapper).toAddress(addressDto);
        verify(addressRepository).save(address);
    }

    @Test
    void addAddresses_shouldReturnError_whenSaveParticipantAddressFails() {
        when(addressMapper.toAddress(addressDto)).thenReturn(address);
        when(addressRepository.save(address)).thenReturn(Mono.just(address));
        when(participantAddressRepository.save(any(ParticipantAddress.class))).thenReturn(Mono.error(new RuntimeException("Save failed")));

        StepVerifier.create(addressService.addAddresses(1L, addressDto))
                .expectError(RuntimeException.class)
                .verify();

        verify(addressMapper).toAddress(addressDto);
        verify(addressRepository).save(address);
        verify(participantAddressRepository).save(any(ParticipantAddress.class));
    }

    @Test
    void deleteAddresses_shouldDeleteAddressAndParticipantAddress_whenAddressExistsForParticipant() {
        when(participantAddressRepository.findByParticipantId(1L)).thenReturn(Flux.just(participantAddress));
        when(participantAddressRepository.deleteById(1L)).thenReturn(Mono.empty());
        when(addressRepository.deleteById(1L)).thenReturn(Mono.empty());

        StepVerifier.create(addressService.deleteAddresses(1L, 1L))
                .verifyComplete();

        verify(participantAddressRepository).findByParticipantId(1L);
        verify(participantAddressRepository).deleteById(1L);
        verify(addressRepository).deleteById(1L);
    }

    @Test
    void deleteAddresses_shouldFail_whenAddressNotFoundForParticipant() {
        when(participantAddressRepository.findByParticipantId(1L)).thenReturn(Flux.empty());

        StepVerifier.create(addressService.deleteAddresses(1L, 1L))
                .expectError(NotFoundException.class)
                .verify();

        verify(participantAddressRepository).findByParticipantId(1L);
    }

    @Test
    void deleteAddresses_shouldFail_whenParticipantAddressDeletionFails() {
        when(participantAddressRepository.findByParticipantId(1L)).thenReturn(Flux.just(participantAddress));
        when(participantAddressRepository.deleteById(1L)).thenReturn(Mono.error(new RuntimeException("Deletion failed")));

        StepVerifier.create(addressService.deleteAddresses(1L, 1L))
                .expectError(RuntimeException.class)
                .verify();

        verify(participantAddressRepository).findByParticipantId(1L);
        verify(participantAddressRepository).deleteById(1L);
    }

    @Test
    void deleteAddresses_shouldFail_whenAddressDeletionFails() {
        when(participantAddressRepository.findByParticipantId(1L)).thenReturn(Flux.just(participantAddress));
        when(participantAddressRepository.deleteById(1L)).thenReturn(Mono.empty());
        when(addressRepository.deleteById(1L)).thenReturn(Mono.error(new RuntimeException("Deletion failed")));

        StepVerifier.create(addressService.deleteAddresses(1L, 1L))
                .expectError(RuntimeException.class)
                .verify();

        verify(participantAddressRepository).findByParticipantId(1L);
        verify(participantAddressRepository).deleteById(1L);
        verify(addressRepository).deleteById(1L);
    }
}
