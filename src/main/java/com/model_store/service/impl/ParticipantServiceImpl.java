package com.model_store.service.impl;

import com.model_store.exeption.ParticipantNotFoundException;
import com.model_store.mapper.ParticipantMapper;
import com.model_store.model.CreateOrUpdateImages;
import com.model_store.model.CreateOrUpdateParticipantRequest;
import com.model_store.model.FindParticipantRequest;
import com.model_store.model.base.Address;
import com.model_store.model.base.Participant;
import com.model_store.model.base.ParticipantAddress;
import com.model_store.model.base.SocialNetwork;
import com.model_store.repository.AddressRepository;
import com.model_store.repository.ParticipantAddressRepository;
import com.model_store.repository.ParticipantRepository;
import com.model_store.repository.SocialNetworkRepository;
import com.model_store.service.ParticipantService;
import com.model_store.service.ProductService;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;

import static com.model_store.model.constant.ImageTag.PARTICIPANT;

@Slf4j
@Service
@RequiredArgsConstructor
public class ParticipantServiceImpl implements ParticipantService {
    private final ImageService imageService;
    private final ProductService productService;

    private final ParticipantMapper participantMapper;

    private final ParticipantAddressRepository participantAddressRepository;
    private final SocialNetworkRepository socialNetworkRepository;
    private final ParticipantRepository participantRepository;
    private final AddressRepository addressRepository;

    public Mono<Participant> findById(Long id) {
        return participantRepository.findById(id);
    }

    @Transactional
    public Mono<Void> deleteParticipant(Long id) {
        return Mono.when(productService.deleteProductsByParticipant(id),
                participantAddressRepository.deleteByParticipantId(id),
                socialNetworkRepository.deleteByParticipantId(id),
                participantRepository.deleteById(id)
        );
    }

    public Flux<Participant> findByParams(FindParticipantRequest searchParams) {
        return participantRepository.findByParams(searchParams);
    }

    @Transactional
    public Mono<Void> createParticipant(CreateOrUpdateParticipantRequest request) {
        Participant participant = participantMapper.toParticipant(request);
        participant.setCreatedAt(Instant.now());

        return participantRepository.save(participant)
                .flatMap(savedParticipant -> {
                    Mono<Void> savedAddressesMono = saveAddresses(request.getAddress(), savedParticipant.getId());
                    Mono<Void> savedSocialNetworksMono = saveSocialNetworks(request.getSocialNetworks(), savedParticipant.getId());
                    Mono<Void> imagesMono = saveImages(request.getImages(), savedParticipant.getId());
                    return Mono.when(savedAddressesMono, savedSocialNetworksMono, imagesMono).then();
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
                .switchIfEmpty(Mono.error(new ParticipantNotFoundException(id)))
                .flatMap(existingParticipant -> {
                    Participant updatedParticipant = participantMapper.toParticipant(request);
                    updatedParticipant.setId(existingParticipant.getId());

                    Mono<Void> savedAddressesMono = saveAddresses(request.getAddress(), updatedParticipant.getId());
                    Mono<Void> savedSocialNetworksMono = saveSocialNetworks(request.getSocialNetworks(), updatedParticipant.getId());
                    Mono<Void> imagesMono = saveImages(request.getImages(), updatedParticipant.getId());

                    return Mono.when(savedAddressesMono, savedSocialNetworksMono, imagesMono)
                            .then(Mono.defer(() -> participantRepository.save(updatedParticipant)))
                            .doOnError(throwable -> log.error("Пользователь {} не сохранен причина: {}", updatedParticipant.getId(), throwable.getMessage()))
                            .doOnSuccess(participantId -> log.info("Пользователь {} успешно сохоанен", participantId))
                            .then();
                });
    }

    private Mono<Void> saveAddresses(List<Address> addresses, Long participantId) {
        if (addresses == null || addresses.isEmpty()) {
            return Mono.empty();
        }
        return addressRepository.saveAll(addresses)
                .flatMap(address -> participantAddressRepository.save(ParticipantAddress.builder()
                        .addressId(address.getId())
                        .participantId(participantId)
                        .build())
                )
                .doOnError(throwable -> log.error("Ошибка при сохраниении ParticipantAddress: {}", throwable.getMessage()))
                .then();
    }

    private Mono<Void> saveSocialNetworks(List<SocialNetwork> socialNetworks, Long participantId) {
        if (socialNetworks == null || socialNetworks.isEmpty()) {
            return Mono.empty();
        }
        socialNetworks.forEach(socialNetwork -> socialNetwork.setParticipantId(participantId));
        return socialNetworkRepository.saveAll(socialNetworks).then();
    }

    private Mono<Void> saveImages(List<String> imagePaths, Long participantId) {
        if (imagePaths == null || imagePaths.isEmpty()) {
            return Mono.empty();
        }
        return imageService.saveImages(CreateOrUpdateImages.builder()
                .paths(imagePaths)
                .tag(PARTICIPANT)
                .entityId(participantId)
                .build()
        ).then();
    }
}