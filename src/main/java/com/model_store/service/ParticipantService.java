package com.model_store.service;

import com.model_store.mapper.ParticipantMapper;
import com.model_store.model.*;
import com.model_store.model.base.Address;
import com.model_store.model.base.Image;
import com.model_store.model.base.Participant;
import com.model_store.model.base.SocialNetwork;
import com.model_store.repository.AddressRepository;
import com.model_store.repository.ParticipantRepository;
import com.model_store.repository.SocialNetworkRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

import static com.model_store.model.constant.ImageTag.PARTICIPANT;

@Service
@RequiredArgsConstructor
public class ParticipantService {
    private final ParticipantRepository participantRepository;
    private final AddressRepository addressRepository;
    private final SocialNetworkRepository socialNetworkRepository;
    private final ParticipantMapper participantMapper;
    private final ImageService imageService;

    public Mono<Participant> findById(Long id) {
        return participantRepository.findById(id);
    }

    public Flux<Participant> findByParams(FindParticipantRequest searchParams) {
//        return participantRepository.findByParams(searchParams);
        return Flux.empty();
    }

    @Transactional
    public Mono<Void> createParticipant(CreateOrUpdateParticipantRequest request) {
        Participant participant = participantMapper.toParticipant(request);

        // Сначала сохраняем участника
        return participantRepository.save(participant).then();
//                .flatMap(savedParticipant -> {
//                    // Сохраняем адреса, если они есть
//                    Mono<Void> savedAddressesMono = saveAddresses(request.getAddress(), savedParticipant.getId());
//
//                    // Сохраняем социальные сети, если они есть
//                    Mono<Void> savedSocialNetworksMono = saveSocialNetworks(request.getSocialNetworks(), savedParticipant.getId());
//
//                    // Сохраняем изображения, если они есть
//                    Mono<Void> imagesMono = saveImages(request.getImages(), savedParticipant.getId());
//
//                    // Объединение результатов
//                    return Mono.when(savedAddressesMono, savedSocialNetworksMono, imagesMono).then();
//                });
    }

    @Transactional
    public Mono<Void> updateParticipant(Long id, CreateOrUpdateParticipantRequest request) {
        return participantRepository.findById(id)
                .flatMap(existingParticipant -> {
                    Participant updatedParticipant = participantMapper.toParticipant(request);
                    updatedParticipant.setId(existingParticipant.getId());
                    return participantRepository.save(updatedParticipant).then();
                    // Сохраняем адреса, если они есть
//                    Mono<Void> savedAddressesMono = saveAddresses(request.getAddress(), updatedParticipant.getId());
//
//                    // Сохраняем социальные сети, если они есть
//                    Mono<Void> savedSocialNetworksMono = saveSocialNetworks(request.getSocialNetworks(), updatedParticipant.getId());
//
//                    // Сохраняем изображения, если они есть
//                    Mono<Void> imagesMono = saveImages(request.getImages(), updatedParticipant.getId());
//
//                    // Объединение результатов и обновление участника
//                    return Mono.zip(savedAddressesMono, savedSocialNetworksMono, imagesMono)
//                            .flatMap(tuple -> participantRepository.save(updatedParticipant))
//                            .then();
                });
    }

    private Mono<Void> saveAddresses(List<Address> addresses, Long participantId) {
        if (addresses == null || addresses.isEmpty()) {
            return Mono.empty();
        }
        addresses.forEach(address -> address.setParticipantId(participantId));
        return addressRepository.saveAll(addresses).then();
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
                .build()).then();
    }


    public Mono<Void> deleteParticipant(Long id) {
        return participantRepository.deleteById(id);
    }
}