package com.model_store.service.impl;

import com.amazonaws.services.kms.model.NotFoundException;
import com.model_store.exeption.ParticipantNotFoundException;
import com.model_store.mapper.ParticipantMapper;
import com.model_store.model.UpdateParticipantRequest;
import com.model_store.model.CreateParticipantRequest;
import com.model_store.model.FindParticipantRequest;
import com.model_store.model.base.Participant;
import com.model_store.model.base.SellerRating;
import com.model_store.model.constant.ImageStatus;
import com.model_store.model.constant.ImageTag;
import com.model_store.model.constant.ParticipantStatus;
import com.model_store.model.constant.TransferMoneyType;
import com.model_store.model.dto.FindParticipantByLoginDto;
import com.model_store.model.dto.FindParticipantsDto;
import com.model_store.model.dto.FullParticipantDto;
import com.model_store.model.dto.UserInfoDto;
import com.model_store.repository.AccountRepository;
import com.model_store.repository.AddressRepository;
import com.model_store.repository.OrderRepository;
import com.model_store.repository.ParticipantRepository;
import com.model_store.repository.SellerRatingRepository;
import com.model_store.repository.TransferRepository;
import com.model_store.service.EmailService;
import com.model_store.service.ImageService;
import com.model_store.service.ParticipantService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

import static com.model_store.model.constant.ParticipantStatus.ACTIVE;
import static com.model_store.model.constant.ParticipantStatus.DELETED;
import static com.model_store.model.constant.ParticipantStatus.WAITING_VERIFY;
import static com.model_store.service.util.UtilService.getExpensive;
import static java.util.Objects.isNull;

@Slf4j
@Service
@RequiredArgsConstructor
public class ParticipantServiceImpl implements ParticipantService {
    private final ImageService imageService;
    private final ParticipantMapper participantMapper;
    private final ParticipantRepository participantRepository;
    private final AddressRepository addressRepository;
    private final AccountRepository accountRepository;
    private final TransferRepository transferRepository;
    private final OrderRepository orderRepository;
    private final EmailService emailService;
    private final SellerRatingRepository sellerRatingRepository;

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
                        imageService.findActualImages(id, ImageTag.PARTICIPANT).collectList().defaultIfEmpty(List.of()),
                        sellerRatingRepository.findBySellerId(id).defaultIfEmpty(new SellerRating())
                        )
                .map(tuple5 ->
                        participantMapper.toFullParticipantDto(tuple5.getT1(), tuple5.getT2(), tuple5.getT3(), tuple5.getT4(), tuple5.getT5(), tuple5.getT6())
                );
    }

    @Override
    public Mono<FindParticipantByLoginDto> findByLogin(String login) {
        Mono<Participant> participantMono = participantRepository.findByLogin(login);
        Mono<Long> imageMono = participantMono.flatMap(participant -> imageService.findMainImage(participant.getId(), ImageTag.PARTICIPANT));
        return Mono.zip(participantMono, imageMono.defaultIfEmpty(0L))
                .map(tuple2 ->
                        participantMapper.toFindParticipantByLoginDto(tuple2.getT1(), tuple2.getT2())
                );
    }

    @Override
    public Mono<FindParticipantByLoginDto> findByMail(String mail) {
        Mono<Participant> participantMono = participantRepository.findByMail(mail);
        Mono<Long> imageMono = participantMono.flatMap(participant -> imageService.findMainImage(participant.getId(), ImageTag.PARTICIPANT));
        return Mono.zip(participantMono, imageMono.defaultIfEmpty(0L))
                .map(tuple2 ->
                        participantMapper.toFindParticipantByLoginDto(tuple2.getT1(), tuple2.getT2())
                );
    }

    @Override
    public Mono<String> findFullNameById(Long participantId) {
        return participantRepository.findFullNameById(participantId);
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
    public Mono<Long> createParticipant(CreateParticipantRequest request) {
        Participant participant = participantMapper.toParticipant(request, ParticipantStatus.WAITING_VERIFY);

        return participantRepository.findByMail(request.getMail())
                .filter(p -> p.getStatus() != WAITING_VERIFY)
                .flatMap(existing -> Mono.error(new RuntimeException("Пользователь с таким email уже зарегистрирован")))
                .switchIfEmpty(Mono.defer(() -> {
                    participant.setPassword(passwordEncoder.encode(request.getPassword()));
                    return participantRepository.save(participant).map(Participant::getId);
                })).flatMap(participantId -> emailService.sendVerificationCode((Long) participantId, participant.getMail())
                        .thenReturn((Long) participantId)
                );
    }

    @Transactional
    public Mono<Participant> activateUser(Long userId) {
        return participantRepository.findById(userId)
                .flatMap(user -> {
                    if (user.getStatus() == ParticipantStatus.WAITING_VERIFY) {
                        user.setStatus(ParticipantStatus.ACTIVE);
                        return participantRepository.save(user);
                    }
                    return Mono.error(new RuntimeException("User already activated"));
                });
    }

    @Transactional
    public Mono<Long> updateParticipant(Long id, UpdateParticipantRequest request) {
        return participantRepository.findById(id)
                .filter(participant -> ACTIVE.equals(participant.getStatus()))
                .switchIfEmpty(Mono.error(new ParticipantNotFoundException(id)))
                .flatMap(existingParticipant -> {
                    Participant updatedParticipant = participantMapper.toParticipant(request, ACTIVE);
                    updatedParticipant.setId(existingParticipant.getId());
                    updatedParticipant.setRole(existingParticipant.getRole());
                    updatedParticipant.setMail(existingParticipant.getMail());
                    updatedParticipant.setPassword(existingParticipant.getPassword());
                    updatedParticipant.setCreatedAt(existingParticipant.getCreatedAt());
                    updatedParticipant.setSellerStatus(existingParticipant.getSellerStatus());
                    return participantRepository.save(updatedParticipant).map(Participant::getId);
                })
                .doOnSuccess(participantId -> log.info("Пользователь {} успешно сохранен", participantId))
                .doOnError(throwable -> log.error("Пользователь {} не сохранен причина: {}", id, throwable.getMessage()));
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

    @Override
    public Mono<Void> updateParticipantStatus(Long participantId, ParticipantStatus status) {
        return participantRepository.findById(participantId)
                .filter(participant -> ACTIVE.equals(participant.getStatus()))
                .switchIfEmpty(Mono.error(new NotFoundException("No participant found with id: " + participantId)))
                .flatMap(participant -> {
                    participant.setStatus(status);
                    return participantRepository.save(participant);
                }).then();
    }

    @Override
    public Mono<Long> updateParticipantPassword(Long participantId, String oldPassword, String newPassword) {
        return participantRepository.findById(participantId)
                .switchIfEmpty(Mono.error(new ParticipantNotFoundException(participantId)))
                .flatMap(participant -> changePassword(participantId, oldPassword, newPassword, participant));
    }

    @NotNull
    private Mono<Long> changePassword(Long participantId, String oldPassword, String newPassword, Participant participant) {
        if (passwordEncoder.matches(oldPassword, participant.getPassword())) {
            participant.setPassword(passwordEncoder.encode(newPassword));
            return participantRepository.save(participant).map(Participant::getId);
        } else {
            return Mono.error(new BadCredentialsException(participantId.toString()));
        }
    }


    private Mono<Void> updateImagesStatus(List<Long> imageIds, Long id) {
        if (isNull(imageIds) || imageIds.isEmpty()) {
            return Mono.empty();
        }
        return imageService.updateImagesStatus(imageIds, id, ImageStatus.ACTIVE, ImageTag.PARTICIPANT);
    }
}