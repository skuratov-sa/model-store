package com.model_store.service.impl;

import com.amazonaws.services.kms.model.NotFoundException;
import com.model_store.exeption.ParticipantNotFoundException;
import com.model_store.mapper.AccountMapper;
import com.model_store.mapper.AddressMapper;
import com.model_store.mapper.ParticipantMapper;
import com.model_store.mapper.TransferMapper;
import com.model_store.model.CreateOrUpdateParticipantRequest;
import com.model_store.model.FindParticipantRequest;
import com.model_store.model.base.Participant;
import com.model_store.model.base.ParticipantAddress;
import com.model_store.model.constant.ImageStatus;
import com.model_store.model.constant.ImageTag;
import com.model_store.model.constant.TransferMoneyType;
import com.model_store.model.dto.AccountDto;
import com.model_store.model.dto.AddressDto;
import com.model_store.model.dto.FindParticipantsDto;
import com.model_store.model.dto.FullParticipantDto;
import com.model_store.model.dto.SocialNetworkDto;
import com.model_store.model.dto.TransferDto;
import com.model_store.model.dto.UserInfoDto;
import com.model_store.repository.AccountRepository;
import com.model_store.repository.AddressRepository;
import com.model_store.repository.OrderRepository;
import com.model_store.repository.ParticipantAddressRepository;
import com.model_store.repository.ParticipantRepository;
import com.model_store.repository.SocialNetworkRepository;
import com.model_store.repository.TransferRepository;
import com.model_store.service.ImageService;
import com.model_store.service.ParticipantService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

import static com.model_store.model.constant.ParticipantStatus.ACTIVE;
import static com.model_store.model.constant.ParticipantStatus.DELETED;
import static com.model_store.service.util.UtilService.getExpensive;
import static java.util.Objects.isNull;

@Slf4j
@Service
@RequiredArgsConstructor
public class ParticipantServiceImpl implements ParticipantService {
    private final ImageService imageService;
    private final ParticipantMapper participantMapper;
    private final AccountMapper accountMapper;
    private final TransferMapper transferMapper;
    private final AddressMapper addressMapper;

    private final ParticipantAddressRepository participantAddressRepository;
    private final SocialNetworkRepository socialNetworkRepository;
    private final ParticipantRepository participantRepository;
    private final AddressRepository addressRepository;
    private final AccountRepository accountRepository;
    private final TransferRepository transferRepository;
    private final OrderRepository orderRepository;

    private final PasswordEncoder passwordEncoder;

    @Override
    public Mono<UserInfoDto> findShortInfo(Long id) {
        return participantRepository.findActualParticipant(id)
                .flatMap(p -> imageService.findMainImage(id, ImageTag.PARTICIPANT).defaultIfEmpty(-1L)
                        .map(imageId -> participantMapper.toUserInfoDto(p, imageId == -1 ? null : imageId))
                );
    }

    @Override
    public Mono<FullParticipantDto> findActualById(Long id) {
        return Mono.zip(
                        participantRepository.findActualParticipant(id),
                        addressRepository.findByParticipantId(id).collectList().defaultIfEmpty(List.of()),
                        accountRepository.findByParticipantId(id).collectList().defaultIfEmpty(List.of()),
                        transferRepository.findByParticipantId(id).collectList().defaultIfEmpty(List.of()),
                        imageService.findActualImages(id, ImageTag.PARTICIPANT).collectList().defaultIfEmpty(List.of())
                )
                .map(tuple5 ->
                        participantMapper.toFullParticipantDto(tuple5.getT1(), tuple5.getT2(), tuple5.getT3(), tuple5.getT4(), tuple5.getT5())
                );
    }

    public Flux<FindParticipantsDto> findByParams(FindParticipantRequest searchParams) {
        return participantRepository.findByParams(searchParams)
                .flatMap(participant -> {
                    // Создание DTO
                    Mono<FindParticipantsDto> dtoMono = imageService.findMainImage(participant.getId(), ImageTag.PARTICIPANT)
                            .defaultIfEmpty(-1L)
                            .map(imageId -> {
                                FindParticipantsDto dto = participantMapper.toFindParticipantDto(participant, imageId == -1L ? null : imageId);
                                dto.setExperience(getExpensive(participant.getCreatedAt())); // Рассчитываем стаж
                                dto.setCountry(searchParams.getCountry()); // Устанавливаем страну
                                return dto;
                            });

                    // Обогащение DTO
                    return dtoMono.flatMap(dto ->
                            orderRepository.findCompletedCountBySellerId(dto.getId())
                                    .defaultIfEmpty(0)
                                    .doOnNext(dto::setOrderCompletedCount)
                                    .then(orderRepository.findCompletedCountByCustomerId(dto.getId())
                                            .defaultIfEmpty(0)
                                            .doOnNext(dto::setOrderPurchaseCount))
                                    .thenMany(accountRepository.findTypeByParticipantId(dto.getId())
                                            .map(TransferMoneyType::valueOf)
                                            .collectList()
                                            .doOnNext(dto::setTransferMoneys))
                                    .then(Mono.just(dto))
                    );
                });
    }


    @Transactional
    public Mono<Long> createParticipant(CreateOrUpdateParticipantRequest request) {
        Participant participant = participantMapper.toParticipant(request, ACTIVE);

        return participantRepository.findByLogin(request.getLogin())
                .flatMap(existingParticipant -> Mono.error(new RuntimeException("Participant already exists")))
                .switchIfEmpty(Mono.defer(() -> {
                    participant.setPassword(passwordEncoder.encode(request.getPassword()));
                    return participantRepository.save(participant).map(Participant::getId);
                }))
                .flatMap(participantId -> {
                    Mono<Void> savedAddressesMono = saveAddresses(request.getAddress(), (Long) participantId);
                    Mono<Void> savedSocialNetworksMono = saveSocialNetworks(request.getSocialNetworks(), (Long) participantId);
                    Mono<Void> savedAccountsMono = saveAccounts(request.getAccounts(), (Long) participantId);
                    Mono<Void> savedTransfersMono = saveTransfers(request.getTransfers(), (Long) participantId);
                    return Mono.when(savedAddressesMono, savedSocialNetworksMono, savedAccountsMono, savedTransfersMono)
                            .thenReturn((Long) participantId);
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
                    Participant updatedParticipant = participantMapper.toParticipant(request, ACTIVE);
                    updatedParticipant.setId(existingParticipant.getId());

                    Mono<Void> savedAccountsMono = saveAccounts(request.getAccounts(), updatedParticipant.getId());
                    Mono<Void> savedTransfersMono = saveTransfers(request.getTransfers(), updatedParticipant.getId());
                    Mono<Void> savedAddressesMono = saveAddresses(request.getAddress(), updatedParticipant.getId());
                    Mono<Void> savedSocialNetworksMono = saveSocialNetworks(request.getSocialNetworks(), updatedParticipant.getId());
                    Mono<Void> updateImagesStatus = updateImagesStatus(request.getImageIds(), existingParticipant.getId());

                    return Mono.when(savedAddressesMono, savedSocialNetworksMono, updateImagesStatus, savedAccountsMono, savedTransfersMono)
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

        return participantAddressRepository.findByParticipantId(participantId)
                .flatMap(participantAddress ->
                        participantAddressRepository.deleteById(participantAddress.getId())
                                .thenMany(addressRepository.deleteById(participantAddress.getAddressId()))
                ).thenMany(Flux.fromIterable(addresses) // Обрабатываем новые адреса
                        .map(addressMapper::toAddress) // Преобразуем DTO в Address
                        .flatMap(addressRepository::save) // Сохраняем новые адреса
                        .flatMap(savedAddress -> participantAddressRepository.save( // Создаём новые связи
                                ParticipantAddress.builder()
                                        .addressId(savedAddress.getId())
                                        .participantId(participantId)
                                        .build()
                        ))
                )
                .doOnError(throwable -> log.error("Ошибка при сохранении адресов: {}", throwable.getMessage()))
                .then();
    }

    private Mono<Void> saveSocialNetworks(List<SocialNetworkDto> socialNetworksDto, Long participantId) {
        if (socialNetworksDto == null || socialNetworksDto.isEmpty()) {
            return Mono.empty();
        }
        return socialNetworkRepository.findByParticipantId(participantId).collectList()
                .map(socialNetworks -> participantMapper.toSocialNetwork(socialNetworksDto, socialNetworks, participantId))
                .flatMapMany(socialNetworkRepository::saveAll)
                .then();
    }


    private Mono<Void> saveAccounts(List<AccountDto> accountDtos, Long participantId) {
        if (accountDtos == null || accountDtos.isEmpty()) {
            return Mono.empty();
        }
        return accountRepository.findByParticipantId(participantId).collectList()
                .map(accounts -> accountMapper.toAccount(accountDtos, accounts, participantId))
                .flatMapMany(accountRepository::saveAll)
                .then();
    }

    private Mono<Void> saveTransfers(List<TransferDto> transferDtos, Long participantId) {
        if (transferDtos == null || transferDtos.isEmpty()) {
            return Mono.empty();
        }

        return transferRepository.findByParticipantId(participantId).collectList()
                .map(accounts -> transferMapper.toTransfer(transferDtos, accounts, participantId))
                .flatMapMany(transferRepository::saveAll)
                .then();
    }

    private Mono<Void> updateImagesStatus(List<Long> imageIds, Long id) {
        if (isNull(imageIds) || imageIds.isEmpty()) {
            return Mono.empty();
        }
        return imageService.updateImagesStatus(imageIds, id, ImageStatus.ACTIVE, ImageTag.PARTICIPANT);
    }
}