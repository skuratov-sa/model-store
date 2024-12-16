package com.model_store.service.impl;

import com.amazonaws.services.kms.model.NotFoundException;
import com.model_store.exeption.ParticipantNotFoundException;
import com.model_store.mapper.ParticipantMapper;
import com.model_store.model.CreateOrUpdateParticipantRequest;
import com.model_store.model.FindParticipantRequest;
import com.model_store.model.base.Participant;
import com.model_store.model.base.ParticipantAddress;
import com.model_store.model.constant.ImageStatus;
import com.model_store.model.constant.ImageTag;
import com.model_store.model.dto.AddressDto;
import com.model_store.model.dto.FindParticipantsDto;
import com.model_store.model.dto.FullParticipantDto;
import com.model_store.model.dto.SocialNetworkDto;
import com.model_store.repository.AddressRepository;
import com.model_store.repository.ParticipantAddressRepository;
import com.model_store.repository.ParticipantRepository;
import com.model_store.repository.SocialNetworkRepository;
import com.model_store.service.ImageService;
import com.model_store.service.ParticipantService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

import static com.model_store.model.constant.ParticipantStatus.ACTIVE;
import static com.model_store.model.constant.ParticipantStatus.DELETED;
import static java.util.Objects.isNull;

@Slf4j
@Service
@RequiredArgsConstructor
public class ParticipantServiceImpl implements ParticipantService {
    private final ImageService imageService;
    private final ParticipantMapper participantMapper;

    private final ParticipantAddressRepository participantAddressRepository;
    private final SocialNetworkRepository socialNetworkRepository;
    private final ParticipantRepository participantRepository;
    private final AddressRepository addressRepository;

    public Mono<FullParticipantDto> findById(Long id) {
        return participantRepository.findById(id)
                .flatMap(participant ->
                        imageService.findActualByParticipantId(id).collectList()
                                .map(imageIds -> participantMapper.toFullParticipantDto(participant, imageIds))
                );
    }

    public Flux<FindParticipantsDto> findByParams(FindParticipantRequest searchParams) {
        return participantRepository.findByParams(searchParams)
                .flatMap(participant ->
                        imageService.findActualByParticipantId(participant.getId())
                                .collectList()
                                .map(imageList -> {
                                    Long imageId = imageList.isEmpty() ? null : imageList.getFirst();
                                    return participantMapper.toFindParticipantDto(participant, imageId);
                                })
                );
    }

    @Transactional
    public Mono<Void> createParticipant(CreateOrUpdateParticipantRequest request) {
        Participant participant = participantMapper.toParticipant(request);

        return participantRepository.save(participant)
                .flatMap(savedParticipant -> {
                    Mono<Void> savedAddressesMono = saveAddresses(request.getAddress(), savedParticipant.getId());
                    Mono<Void> savedSocialNetworksMono = saveSocialNetworks(request.getSocialNetworks(), savedParticipant.getId());
                    return Mono.when(savedAddressesMono, savedSocialNetworksMono).then();
                });
    }

    /**
     * Сохраняем адреса соц сети картинки (Если они есть)
     *
     * @param id      - идетификатор пользователя
     * @param request - обновленные параметры
     */
    @Transactional
    public Mono<Void> updateParticipant(Long id, CreateOrUpdateParticipantRequest request) {
        return participantRepository.findById(id)
                .filter(participant -> ACTIVE.equals(participant.getStatus()))
                .switchIfEmpty(Mono.error(new ParticipantNotFoundException(id)))
                .flatMap(existingParticipant -> {
                    Participant updatedParticipant = participantMapper.toParticipant(request);
                    updatedParticipant.setId(existingParticipant.getId());

                    Mono<Void> savedAddressesMono = saveAddresses(request.getAddress(), updatedParticipant.getId());
                    Mono<Void> savedSocialNetworksMono = saveSocialNetworks(request.getSocialNetworks(), updatedParticipant.getId());
                    Mono<Void> updateImagesStatus = updateImagesStatus(request.getImageIds(), existingParticipant.getId());

                    return Mono.when(savedAddressesMono, savedSocialNetworksMono, updateImagesStatus)
                            .then(Mono.defer(() -> participantRepository.save(updatedParticipant)))
                            .doOnError(throwable -> log.error("Пользователь {} не сохранен причина: {}", updatedParticipant.getId(), throwable.getMessage()))
                            .doOnSuccess(participantId -> log.info("Пользователь {} успешно сохоанен", participantId))
                            .then();
                });
    }

    public Mono<Void> deleteParticipant(Long id) {
        return participantRepository.findById(id)
                .filter(participant -> ACTIVE.equals(participant.getStatus()))
                .switchIfEmpty(Mono.error(new NotFoundException("No participant found with id: " + id)))
                .flatMap(participant -> {
                    participant.setStatus(DELETED);
                    return participantRepository.save(participant);
                }).then();
    }

    private Mono<Void> saveAddresses(List<AddressDto> addresses, Long participantId) {
        if (addresses == null || addresses.isEmpty()) {
            return Mono.empty();
        }
        return addressRepository.saveAll(participantMapper.toAddress(addresses))
                .flatMap(address -> participantAddressRepository.save(ParticipantAddress.builder()
                        .addressId(address.getId())
                        .participantId(participantId)
                        .build())
                )
                .doOnError(throwable -> log.error("Ошибка при сохраниении ParticipantAddress: {}", throwable.getMessage()))
                .then();
    }

    private Mono<Void> saveSocialNetworks(List<SocialNetworkDto> socialNetworksDto, Long participantId) {
        if (socialNetworksDto == null || socialNetworksDto.isEmpty()) {
            return Mono.empty();
        }

        return Flux.fromIterable(socialNetworksDto)
                .map(dto -> participantMapper.toSocialNetwork(dto, participantId))
                .flatMap(socialNetworkRepository::save)
                .doOnError(throwable -> log.error("Ошибка при сохраниении SocialNetwork: {}", throwable.getMessage()))
                .then();
    }

    private Mono<Void> updateImagesStatus(List<Long> imageIds, Long id) {
        if (isNull(imageIds) || imageIds.isEmpty()) {
            return Mono.empty();
        }
        return imageService.updateImagesStatus(imageIds, id, ImageStatus.ACTIVE, ImageTag.PARTICIPANT);
    }

}