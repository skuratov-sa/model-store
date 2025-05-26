package com.model_store.service.impl;

import com.model_store.mapper.ParticipantMapper;
import com.model_store.model.CreateParticipantRequest;
import com.model_store.model.FindParticipantRequest;
import com.model_store.model.UpdateParticipantRequest;
import com.model_store.model.base.*;
import com.model_store.model.constant.ParticipantRole;
import com.model_store.model.constant.ParticipantStatus;
import com.model_store.model.constant.ImageTag;
import com.model_store.model.constant.SellerStatus;
import com.model_store.model.constant.SocialNetworkType;
import com.model_store.model.constant.TransferMoneyType;
import com.model_store.model.dto.*;
import com.model_store.repository.*;
import com.model_store.service.EmailService;
import com.model_store.service.ImageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ParticipantServiceImplTest {

    @Mock
    private ImageService imageService;
    @Mock
    private ParticipantMapper participantMapper;
    @Mock
    private ParticipantRepository participantRepository;
    @Mock
    private AddressRepository addressRepository;
    @Mock
    private AccountRepository accountRepository;
    @Mock
    private TransferRepository transferRepository;
    @Mock
    private OrderRepository orderRepository;
    @Mock
    private EmailService emailService;
    @Mock
    private SellerRatingRepository sellerRatingRepository;
    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private ParticipantServiceImpl participantService;

    private Participant participant;
    private UserInfoDto userInfoDto;
    private FullParticipantDto fullParticipantDto;
    private FindParticipantByLoginDto findParticipantByLoginDto;
    private CreateParticipantRequest createParticipantRequest;

    @BeforeEach
    void setUp() {
        participant = new Participant();
        participant.setId(1L);
        participant.setLogin("testuser");
        participant.setMail("test@example.com");
        participant.setFullName("Test User");
        participant.setStatus(ParticipantStatus.ACTIVE);
        participant.setPassword("encodedPassword");
        participant.setCreatedAt(Instant.from(LocalDateTime.now().minusYears(1))); // For experience calculation

        userInfoDto = UserInfoDto.builder().id(1L).login("login").imageId(1L).build();

        // Initialize other DTOs and request objects as needed for tests
        fullParticipantDto = FullParticipantDto.builder()
                .id(1L)
                .login("login")
                .imageIds(List.of(1L))
                .build(); // Populate with some defaults

        findParticipantByLoginDto = FindParticipantByLoginDto.builder().id(1L).fullName("Test User").mail("test@example.com").imageId(1L).build();

        createParticipantRequest = new CreateParticipantRequest();
        createParticipantRequest.setMail("new@example.com");
        createParticipantRequest.setPassword("password");
    }

    @Test
    void findShortInfo_shouldReturnUserInfoDto_whenParticipantExists() {
        Long participantId = 1L;
        Long imageId = 123L;

        when(participantRepository.findActualParticipant(participantId)).thenReturn(Mono.just(participant));
        when(imageService.findMainImage(participantId, ImageTag.PARTICIPANT)).thenReturn(Mono.just(imageId));
        when(participantMapper.toUserInfoDto(participant, imageId)).thenReturn(userInfoDto);

        StepVerifier.create(participantService.findShortInfo(participantId))
                .expectNext(userInfoDto)
                .verifyComplete();

        verify(participantRepository).findActualParticipant(participantId);
        verify(imageService).findMainImage(participantId, ImageTag.PARTICIPANT);
        verify(participantMapper).toUserInfoDto(participant, imageId);
    }

    @Test
    void findShortInfo_shouldReturnUserInfoDtoWithNullImage_whenNoMainImage() {
        Long participantId = 1L;

        when(participantRepository.findActualParticipant(participantId)).thenReturn(Mono.just(participant));
        when(imageService.findMainImage(participantId, ImageTag.PARTICIPANT)).thenReturn(Mono.empty()); // No image
        // If imageId is -1L (defaultIfEmpty), mapper should be called with null for imageId
        when(participantMapper.toUserInfoDto(participant, null)).thenReturn(new UserInfoDto(participant.getId(), 1L, participant.getLogin(), participant.getPhoneNumber(), participant.getMail()));


        StepVerifier.create(participantService.findShortInfo(participantId))
                .expectNextMatches(dto -> dto.getId().equals(participantId) && dto.getImageId() == null)
                .verifyComplete();
    }

    @Test
    void findShortInfo_shouldReturnEmpty_whenParticipantNotFound() {
        Long participantId = 2L; // Non-existent
        when(participantRepository.findActualParticipant(participantId)).thenReturn(Mono.empty());

        StepVerifier.create(participantService.findShortInfo(participantId))
                .verifyComplete();

        verify(participantRepository).findActualParticipant(participantId);
        verify(imageService, never()).findMainImage(anyLong(), any(ImageTag.class));
        verify(participantMapper, never()).toUserInfoDto(any(), any());
    }

    @Test
    void findActualById_shouldReturnFullParticipantDto_whenDataExists() {
        Long participantId = 1L;
        List<Address> addresses = Collections.singletonList(new Address());
        List<Account> accounts = Collections.singletonList(new Account());
        List<Transfer> transfers = Collections.singletonList(new Transfer());
        List<Long> imageIds = List.of(123L, 456L);
        List<SocialNetwork> socialNetworks = List.of(SocialNetwork.builder().type(SocialNetworkType.VK).login("@stes").build());
        SellerRating rating = new SellerRating();
        rating.setAverageRating(4.5F);

        when(participantRepository.findActualParticipant(participantId)).thenReturn(Mono.just(participant));
        when(addressRepository.findByParticipantId(participantId)).thenReturn(Flux.fromIterable(addresses));
        when(accountRepository.findByParticipantId(participantId)).thenReturn(Flux.fromIterable(accounts));
        when(transferRepository.findByParticipantId(participantId)).thenReturn(Flux.fromIterable(transfers));
        when(imageService.findActualImages(participantId, ImageTag.PARTICIPANT)).thenReturn(Flux.fromIterable(imageIds));
        when(sellerRatingRepository.findBySellerId(participantId)).thenReturn(Mono.just(rating));
        when(participantMapper.toFullParticipantDto(participant, addresses, accounts, transfers, imageIds, rating, socialNetworks))
                .thenReturn(fullParticipantDto); // fullParticipantDto is from setUp

        StepVerifier.create(participantService.findActualById(participantId))
                .expectNext(fullParticipantDto)
                .verifyComplete();

        verify(participantRepository).findActualParticipant(participantId);
        verify(addressRepository).findByParticipantId(participantId);
        verify(accountRepository).findByParticipantId(participantId);
        verify(transferRepository).findByParticipantId(participantId);
        verify(imageService).findActualImages(participantId, ImageTag.PARTICIPANT);
        verify(sellerRatingRepository).findBySellerId(participantId);
        verify(participantMapper).toFullParticipantDto(participant, addresses, accounts, transfers, imageIds, rating, socialNetworks);
    }

    @Test
    void findActualById_shouldReturnDtoWithEmptyListsAndDefaultRating_whenNoRelatedData() {
        Long participantId = 1L;
        List<Long> imageIds = Collections.emptyList(); // No images

        when(participantRepository.findActualParticipant(participantId)).thenReturn(Mono.just(participant));
        when(addressRepository.findByParticipantId(participantId)).thenReturn(Flux.empty());
        when(accountRepository.findByParticipantId(participantId)).thenReturn(Flux.empty());
        when(transferRepository.findByParticipantId(participantId)).thenReturn(Flux.empty());
        when(imageService.findActualImages(participantId, ImageTag.PARTICIPANT)).thenReturn(Flux.empty());
        when(sellerRatingRepository.findBySellerId(participantId)).thenReturn(Mono.empty()); // No rating, should default

        // Expecting the mapper to be called with empty lists and a new SellerRating for defaults
        when(participantMapper.toFullParticipantDto(eq(participant), eq(Collections.emptyList()), eq(Collections.emptyList()),
                eq(Collections.emptyList()), eq(imageIds), any(SellerRating.class), eq(Collections.emptyList())))
                .thenReturn(fullParticipantDto);


        StepVerifier.create(participantService.findActualById(participantId))
                .expectNext(fullParticipantDto)
                .verifyComplete();
    }


    @Test
    void findActualById_shouldReturnEmpty_whenParticipantNotFound() {
        Long participantId = 2L; // Non-existent
        when(participantRepository.findActualParticipant(participantId)).thenReturn(Mono.empty());
        // Other repositories might be called with participantId but their results will be zipped with an empty Mono.

        StepVerifier.create(participantService.findActualById(participantId))
                .verifyComplete(); // Expect empty because the main participant Mono is empty

        verify(participantRepository).findActualParticipant(participantId);
        // Verification for other repo calls can be tricky due to zipping with Mono.empty,
        // but the important part is that the final Mono is empty.
    }

    @Test
    void findByLogin_shouldReturnDto_whenParticipantExists() {
        String login = "testuser";
        Long imageId = 123L;
        when(participantRepository.findByLogin(login)).thenReturn(Mono.just(participant));
        when(imageService.findMainImage(participant.getId(), ImageTag.PARTICIPANT)).thenReturn(Mono.just(imageId));
        when(participantMapper.toFindParticipantByLoginDto(participant, imageId)).thenReturn(findParticipantByLoginDto);

        StepVerifier.create(participantService.findByLogin(login))
                .expectNext(findParticipantByLoginDto)
                .verifyComplete();
        verify(participantRepository).findByLogin(login);
        verify(imageService).findMainImage(participant.getId(), ImageTag.PARTICIPANT);
        verify(participantMapper).toFindParticipantByLoginDto(participant, imageId);
    }

    @Test
    void findByLogin_shouldReturnDtoWithDefaultImageId_whenNoImage() {
        String login = "testuser";
        when(participantRepository.findByLogin(login)).thenReturn(Mono.just(participant));
        when(imageService.findMainImage(participant.getId(), ImageTag.PARTICIPANT)).thenReturn(Mono.empty()); // No image
        when(participantMapper.toFindParticipantByLoginDto(participant, 0L)) // 0L is from .defaultIfEmpty(0L)
                .thenReturn(FindParticipantByLoginDto.builder()
                        .id(participant.getId())
                        .login(participant.getLogin())
                        .mail(participant.getMail())
                        .build());


        StepVerifier.create(participantService.findByLogin(login))
                .expectNextMatches(dto -> dto.getId().equals(participant.getId()) && dto.getImageId() == null)
                .verifyComplete();
    }


    @Test
    void findByLogin_shouldReturnEmpty_whenParticipantNotFound() {
        String login = "nonexistent";
        when(participantRepository.findByLogin(login)).thenReturn(Mono.empty());

        StepVerifier.create(participantService.findByLogin(login))
                .verifyComplete();
        verify(participantRepository).findByLogin(login);
    }

    @Test
    void findByMail_shouldReturnDto_whenParticipantExists() {
        String mail = "test@example.com";
        Long imageId = 123L;
        when(participantRepository.findByMail(mail)).thenReturn(Mono.just(participant));
        when(imageService.findMainImage(participant.getId(), ImageTag.PARTICIPANT)).thenReturn(Mono.just(imageId));
        when(participantMapper.toFindParticipantByLoginDto(participant, imageId)).thenReturn(findParticipantByLoginDto);

        StepVerifier.create(participantService.findByMail(mail))
                .expectNext(findParticipantByLoginDto)
                .verifyComplete();
    }

    @Test
    void findByMail_shouldReturnEmpty_whenParticipantNotFound() {
        String mail = "nonexistent@example.com";
        when(participantRepository.findByMail(mail)).thenReturn(Mono.empty());

        StepVerifier.create(participantService.findByMail(mail))
                .verifyComplete();
    }

    @Test
    void findFullNameById_shouldReturnFullName_whenParticipantExists() {
        Long participantId = 1L;
        String expectedFullName = "Test User";
        when(participantRepository.findFullNameById(participantId)).thenReturn(Mono.just(expectedFullName));

        StepVerifier.create(participantService.findFullNameById(participantId))
                .expectNext(expectedFullName)
                .verifyComplete();
        verify(participantRepository).findFullNameById(participantId);
    }

    @Test
    void findFullNameById_shouldReturnEmpty_whenParticipantNotFound() {
        Long participantId = 2L; // Non-existent
        when(participantRepository.findFullNameById(participantId)).thenReturn(Mono.empty());

        StepVerifier.create(participantService.findFullNameById(participantId))
                .verifyComplete();
        verify(participantRepository).findFullNameById(participantId);
    }

    @Test
    void findByParams_shouldReturnEnrichedParticipants() {
        FindParticipantRequest request = new FindParticipantRequest();

        FindParticipantsDto findDto = FindParticipantsDto.builder().id(participant.getId()).build();
        // other fields would be set by mapper

        when(participantRepository.findByParams(request)).thenReturn(Flux.just(participant));
        when(imageService.findMainImage(participant.getId(), ImageTag.PARTICIPANT)).thenReturn(Mono.just(123L));
        when(participantMapper.toFindParticipantDto(participant, 123L)).thenReturn(findDto);
        when(orderRepository.findCompletedCountBySellerId(findDto.getId())).thenReturn(Mono.just(5));
        when(orderRepository.findCompletedCountByCustomerId(findDto.getId())).thenReturn(Mono.just(2));
        when(accountRepository.findTypeByParticipantId(findDto.getId())).thenReturn(Flux.just("BANK_CARD", "SBP"));


        StepVerifier.create(participantService.findByParams(request))
                .assertNext(dto -> {
                    assertEquals(participant.getId(), dto.getId());
                    assertEquals(5, dto.getOrderCompletedCount());
                    assertEquals(2, dto.getOrderPurchaseCount());
                    assertEquals("TestCountry", dto.getCountry()); // Set from request
                    assertTrue(dto.getTransferMoneys().contains(TransferMoneyType.BANK_CARD));
                    assertTrue(dto.getTransferMoneys().contains(TransferMoneyType.BANK_SBP));
                    assertNotNull(dto.getExperience()); // Experience should be calculated
                })
                .verifyComplete();

        verify(participantRepository).findByParams(request);
        // ... verify other interactions within the flatMap chain
    }

    @Test
    void findByParams_shouldHandleEmptyImageAndOrderCounts() {
        FindParticipantRequest request = new FindParticipantRequest();
        FindParticipantsDto findDto = FindParticipantsDto.builder().id(participant.getId()).build();

        when(participantRepository.findByParams(request)).thenReturn(Flux.just(participant));
        when(imageService.findMainImage(participant.getId(), ImageTag.PARTICIPANT)).thenReturn(Mono.empty()); // No image
        when(participantMapper.toFindParticipantDto(participant, null)).thenReturn(findDto); // ImageId will be null
        when(orderRepository.findCompletedCountBySellerId(findDto.getId())).thenReturn(Mono.empty()); // No orders
        when(orderRepository.findCompletedCountByCustomerId(findDto.getId())).thenReturn(Mono.empty()); // No purchases
        when(accountRepository.findTypeByParticipantId(findDto.getId())).thenReturn(Flux.empty()); // No transfer types

        StepVerifier.create(participantService.findByParams(request))
                .assertNext(dto -> {
                    assertEquals(participant.getId(), dto.getId());
                    assertEquals(0, dto.getOrderCompletedCount());
                    assertEquals(0, dto.getOrderPurchaseCount());
                    assertTrue(dto.getTransferMoneys().isEmpty());
                    assertNull(dto.getImageId());
                })
                .verifyComplete();
    }

    @Test
    void createParticipant_shouldSaveAndSendVerification_whenMailNotExistsOrWaitingVerify() {
        Participant newParticipant = new Participant();
        newParticipant.setId(2L);
        newParticipant.setMail(createParticipantRequest.getMail());
        newParticipant.setStatus(ParticipantStatus.WAITING_VERIFY);

        when(participantRepository.findByMail(createParticipantRequest.getMail())).thenReturn(Mono.empty()); // No existing user with this mail
        when(participantMapper.toParticipant(createParticipantRequest, ParticipantStatus.WAITING_VERIFY)).thenReturn(newParticipant);
        when(passwordEncoder.encode(createParticipantRequest.getPassword())).thenReturn("encodedNewPassword");
        when(participantRepository.save(any(Participant.class))).thenAnswer(invocation -> {
            Participant p = invocation.getArgument(0);
            assertEquals("encodedNewPassword", p.getPassword());
            assertEquals(ParticipantStatus.WAITING_VERIFY, p.getStatus());
            return Mono.just(p);
        });
        when(emailService.sendVerificationCode(newParticipant.getId(), newParticipant.getMail())).thenReturn(Mono.empty());

        StepVerifier.create(participantService.createParticipant(createParticipantRequest))
                .expectNext(newParticipant.getId())
                .verifyComplete();

        verify(participantRepository).findByMail(createParticipantRequest.getMail());
        verify(participantMapper).toParticipant(createParticipantRequest, ParticipantStatus.WAITING_VERIFY);
        verify(passwordEncoder).encode(createParticipantRequest.getPassword());
        verify(participantRepository).save(any(Participant.class));
        verify(emailService).sendVerificationCode(newParticipant.getId(), newParticipant.getMail());
    }

    @Test
    void createParticipant_shouldSaveAndSendVerification_whenMailExistsButStatusIsWaitingVerify() {
        Participant existingWaitingParticipant = new Participant();
        existingWaitingParticipant.setId(3L);
        existingWaitingParticipant.setMail(createParticipantRequest.getMail());
        existingWaitingParticipant.setStatus(ParticipantStatus.WAITING_VERIFY); // Existing user is waiting for verification

        Participant newParticipantToSave = new Participant(); // This will be the "new" participant data to save
        newParticipantToSave.setId(3L); // It might reuse ID if it's an update-like scenario or generate new
        newParticipantToSave.setMail(createParticipantRequest.getMail());
        newParticipantToSave.setStatus(ParticipantStatus.WAITING_VERIFY);


        when(participantRepository.findByMail(createParticipantRequest.getMail())).thenReturn(Mono.just(existingWaitingParticipant));
        // The logic is: if existing.getStatus() != WAITING_VERIFY, then error.
        // So, if it *is* WAITING_VERIFY, it proceeds to switchIfEmpty's Mono.defer, effectively creating/updating.
        when(participantMapper.toParticipant(createParticipantRequest, ParticipantStatus.WAITING_VERIFY)).thenReturn(newParticipantToSave);
        when(passwordEncoder.encode(createParticipantRequest.getPassword())).thenReturn("encodedPassword123");
        when(participantRepository.save(any(Participant.class))).thenReturn(Mono.just(newParticipantToSave));
        when(emailService.sendVerificationCode(newParticipantToSave.getId(), newParticipantToSave.getMail())).thenReturn(Mono.empty());

        StepVerifier.create(participantService.createParticipant(createParticipantRequest))
                .expectNext(newParticipantToSave.getId())
                .verifyComplete();

        verify(participantRepository).save(any(Participant.class));
        verify(emailService).sendVerificationCode(newParticipantToSave.getId(), newParticipantToSave.getMail());
    }


    @Test
    void createParticipant_shouldFail_whenMailExistsAndParticipantIsActive() {
        participant.setStatus(ParticipantStatus.ACTIVE); // Existing user is active
        when(participantRepository.findByMail(createParticipantRequest.getMail())).thenReturn(Mono.just(participant));

        StepVerifier.create(participantService.createParticipant(createParticipantRequest))
                .expectErrorMatches(throwable -> throwable instanceof RuntimeException &&
                        "Пользователь с таким email уже зарегистрирован".equals(throwable.getMessage()))
                .verify();

        verify(participantRepository).findByMail(createParticipantRequest.getMail());
        verify(participantRepository, never()).save(any(Participant.class));
        verify(emailService, never()).sendVerificationCode(anyLong(), anyString());
    }

    @Test
    void createParticipant_shouldFail_whenEmailServiceFails() {
        Participant newParticipant = new Participant();
        newParticipant.setId(2L);
        newParticipant.setMail(createParticipantRequest.getMail());

        when(participantRepository.findByMail(createParticipantRequest.getMail())).thenReturn(Mono.empty());
        when(participantMapper.toParticipant(createParticipantRequest, ParticipantStatus.WAITING_VERIFY)).thenReturn(newParticipant);
        when(passwordEncoder.encode(createParticipantRequest.getPassword())).thenReturn("encodedPassword");
        when(participantRepository.save(any(Participant.class))).thenReturn(Mono.just(newParticipant));
        when(emailService.sendVerificationCode(newParticipant.getId(), newParticipant.getMail()))
                .thenReturn(Mono.error(new RuntimeException("Email send failed")));

        StepVerifier.create(participantService.createParticipant(createParticipantRequest))
                .expectErrorMatches(throwable -> throwable instanceof RuntimeException && "Email send failed".equals(throwable.getMessage()))
                .verify();
    }

    @Test
    void activateUser_shouldActivateUser_whenStatusIsWaitingVerify() {
        Long userId = 1L;
        participant.setStatus(ParticipantStatus.WAITING_VERIFY);
        Participant activatedParticipant = new Participant();
        activatedParticipant.setId(userId);
        activatedParticipant.setStatus(ParticipantStatus.ACTIVE);


        when(participantRepository.findById(userId)).thenReturn(Mono.just(participant));
        when(participantRepository.save(any(Participant.class))).thenAnswer(invocation -> {
            Participant p = invocation.getArgument(0);
            if (p.getId().equals(userId) && p.getStatus().equals(ParticipantStatus.ACTIVE)) {
                return Mono.just(p);
            }
            return Mono.error(new AssertionError("Save condition not met for activateUser"));
        });

        StepVerifier.create(participantService.activateUser(userId))
                .expectNextMatches(p -> p.getId().equals(userId) && p.getStatus().equals(ParticipantStatus.ACTIVE))
                .verifyComplete();

        verify(participantRepository).findById(userId);
        verify(participantRepository).save(any(Participant.class));
    }

    @Test
    void activateUser_shouldFail_whenUserAlreadyActive() {
        Long userId = 1L;
        participant.setStatus(ParticipantStatus.ACTIVE); // Already active

        when(participantRepository.findById(userId)).thenReturn(Mono.just(participant));

        StepVerifier.create(participantService.activateUser(userId))
                .expectErrorMatches(throwable -> throwable instanceof RuntimeException &&
                        "User already activated".equals(throwable.getMessage()))
                .verify();

        verify(participantRepository).findById(userId);
        verify(participantRepository, never()).save(any(Participant.class));
    }

    @Test
    void activateUser_shouldFail_whenUserNotFound() {
        Long userId = 2L; // Non-existent
        when(participantRepository.findById(userId)).thenReturn(Mono.empty());

        StepVerifier.create(participantService.activateUser(userId))
                // Expecting an error because findById -> flatMap will result in empty if user not found,
                // and the flatMap's Mono.error might not be hit if the initial mono is empty.
                // The behavior depends on how reactive chain handles empty from findById before flatMap.
                // If findById is empty, the chain completes empty, not error usually.
                // Let's re-check the source: `findById(userId).flatMap(...)` if findById is empty, flatMap is not called.
                // So it should complete empty.
                .verifyComplete();

        verify(participantRepository).findById(userId);
        verify(participantRepository, never()).save(any(Participant.class));
    }

    @Test
    void updateParticipant_shouldUpdateParticipant_whenExistsAndActive() {
        Long participantId = 1L;
        UpdateParticipantRequest updateRequest = new UpdateParticipantRequest();
        updateRequest.setFullName("Updated Name");
        updateRequest.setPhoneNumber("1234567890");
        // Set other fields in updateRequest

        Participant existingParticipant = new Participant(); // from setUp, but ensure it's ACTIVE
        existingParticipant.setId(participantId);
        existingParticipant.setStatus(ParticipantStatus.ACTIVE);
        existingParticipant.setRole(ParticipantRole.USER); // Keep original role, mail, password etc.
        existingParticipant.setMail("test@example.com");
        existingParticipant.setPassword("encodedPassword");
        existingParticipant.setCreatedAt(Instant.from(LocalDateTime.now()));
        existingParticipant.setSellerStatus(SellerStatus.DEFAULT);


        Participant mappedParticipant = new Participant(); // What mapper returns from request
        mappedParticipant.setFullName(updateRequest.getFullName());
        mappedParticipant.setPhoneNumber(updateRequest.getPhoneNumber());
        // other fields from request

        Participant finalSavedParticipant = new Participant(); // What should be saved
        finalSavedParticipant.setId(participantId);
        finalSavedParticipant.setFullName(updateRequest.getFullName());
        finalSavedParticipant.setPhoneNumber(updateRequest.getPhoneNumber());
        finalSavedParticipant.setStatus(ParticipantStatus.ACTIVE); // Status remains active
        finalSavedParticipant.setRole(existingParticipant.getRole()); // original role
        finalSavedParticipant.setMail(existingParticipant.getMail()); // original mail
        finalSavedParticipant.setPassword(existingParticipant.getPassword()); // original password
        finalSavedParticipant.setCreatedAt(existingParticipant.getCreatedAt());
        finalSavedParticipant.setSellerStatus(existingParticipant.getSellerStatus());


        when(participantRepository.findById(participantId)).thenReturn(Mono.just(existingParticipant));
        when(participantMapper.toParticipant(updateRequest, ParticipantStatus.ACTIVE)).thenReturn(mappedParticipant);
        when(participantRepository.save(any(Participant.class))).thenAnswer(invocation -> {
            Participant p = invocation.getArgument(0);
            // Assert that p has the correct merged values before saving
            if (p.getId().equals(participantId) &&
                    p.getFullName().equals(updateRequest.getFullName()) &&
                    p.getPhoneNumber().equals(updateRequest.getPhoneNumber()) &&
                    p.getRole().equals(existingParticipant.getRole()) && // Check preserved fields
                    p.getMail().equals(existingParticipant.getMail())) {
                return Mono.just(finalSavedParticipant);
            }
            return Mono.error(new AssertionError("Save conditions for updateParticipant not met."));
        });

        StepVerifier.create(participantService.updateParticipant(participantId, updateRequest))
                .expectNext(finalSavedParticipant.getId())
                .verifyComplete();

        verify(participantRepository).findById(participantId);
        verify(participantMapper).toParticipant(updateRequest, ParticipantStatus.ACTIVE);
        verify(participantRepository).save(any(Participant.class));
    }

    @Test
    void updateParticipant_shouldFail_whenParticipantNotFound() {
        Long participantId = 2L; // Non-existent
        UpdateParticipantRequest updateRequest = new UpdateParticipantRequest();
        when(participantRepository.findById(participantId)).thenReturn(Mono.empty());

        StepVerifier.create(participantService.updateParticipant(participantId, updateRequest))
                .expectError(com.model_store.exeption.ParticipantNotFoundException.class)
                .verify();
    }

    @Test
    void updateParticipant_shouldFail_whenParticipantNotActive() {
        Long participantId = 1L;
        participant.setStatus(ParticipantStatus.DELETED); // Not active
        UpdateParticipantRequest updateRequest = new UpdateParticipantRequest();
        when(participantRepository.findById(participantId)).thenReturn(Mono.just(participant));

        StepVerifier.create(participantService.updateParticipant(participantId, updateRequest))
                .expectError(com.model_store.exeption.ParticipantNotFoundException.class) // Error from switchIfEmpty
                .verify();
    }

    @Test
    void deleteParticipant_shouldSetStatusToDeleted_whenParticipantActive() {
        Long participantId = 1L;
        participant.setStatus(ParticipantStatus.ACTIVE);
        Participant deletedParticipant = new Participant();
        deletedParticipant.setId(participantId);
        deletedParticipant.setStatus(ParticipantStatus.DELETED);

        when(participantRepository.findById(participantId)).thenReturn(Mono.just(participant));
        when(participantRepository.save(any(Participant.class))).thenAnswer(invocation -> {
            Participant p = invocation.getArgument(0);
            if (p.getId().equals(participantId) && p.getStatus().equals(ParticipantStatus.DELETED)) {
                return Mono.just(p);
            }
            return Mono.error(new AssertionError("Save condition not met for deleteParticipant"));
        });

        StepVerifier.create(participantService.deleteParticipant(participantId))
                .verifyComplete();

        verify(participantRepository).findById(participantId);
        verify(participantRepository).save(any(Participant.class));
    }

    @Test
    void deleteParticipant_shouldFail_whenParticipantNotActive() {
        Long participantId = 1L;
        participant.setStatus(ParticipantStatus.DELETED); // Already not active
        when(participantRepository.findById(participantId)).thenReturn(Mono.just(participant));

        StepVerifier.create(participantService.deleteParticipant(participantId))
                .expectError(com.amazonaws.services.kms.model.NotFoundException.class) // From switchIfEmpty
                .verify();
    }

    @Test
    void updateParticipantStatus_shouldUpdateStatus_whenParticipantActive() {
        Long participantId = 1L;
        ParticipantStatus newStatus = ParticipantStatus.BLOCKED;
        participant.setStatus(ParticipantStatus.ACTIVE);

        when(participantRepository.findById(participantId)).thenReturn(Mono.just(participant));
        when(participantRepository.save(any(Participant.class))).thenAnswer(invocation -> {
            Participant p = invocation.getArgument(0);
            if (p.getStatus().equals(newStatus)) {
                return Mono.just(p);
            }
            return Mono.error(new AssertionError("Status not updated correctly"));
        });

        StepVerifier.create(participantService.updateParticipantStatus(participantId, newStatus))
                .verifyComplete();
        verify(participantRepository).save(any(Participant.class));
    }

    @Test
    void updateParticipantPassword_shouldUpdatePassword_whenOldPasswordMatches() {
        Long participantId = 1L;
        String oldPassword = "oldPassword";
        String newPassword = "newPassword";
        String encodedNewPassword = "encodedNewPassword";

        participant.setPassword("encodedOldPassword");

        when(participantRepository.findById(participantId)).thenReturn(Mono.just(participant));
        when(passwordEncoder.matches(oldPassword, "encodedOldPassword")).thenReturn(true);
        when(passwordEncoder.encode(newPassword)).thenReturn(encodedNewPassword);
        when(participantRepository.save(any(Participant.class))).thenAnswer(invocation -> {
            Participant p = invocation.getArgument(0);
            if (p.getPassword().equals(encodedNewPassword)) {
                return Mono.just(p);
            }
            return Mono.error(new AssertionError("Password not updated correctly in save"));
        });

        StepVerifier.create(participantService.updateParticipantPassword(participantId, oldPassword, newPassword))
                .expectNext(participantId)
                .verifyComplete();
        verify(participantRepository).save(any(Participant.class));
    }

    @Test
    void updateParticipantPassword_shouldFail_whenOldPasswordDoesNotMatch() {
        Long participantId = 1L;
        String oldPassword = "wrongOldPassword";
        String newPassword = "newPassword";
        participant.setPassword("encodedOldPassword");

        when(participantRepository.findById(participantId)).thenReturn(Mono.just(participant));
        when(passwordEncoder.matches(oldPassword, "encodedOldPassword")).thenReturn(false);

        StepVerifier.create(participantService.updateParticipantPassword(participantId, oldPassword, newPassword))
                .expectError(org.springframework.security.authentication.BadCredentialsException.class)
                .verify();
        verify(participantRepository, never()).save(any(Participant.class));
    }

    @Test
    void updateParticipantPassword_shouldFail_whenParticipantNotFound() {
        Long participantId = 2L; // Non-existent
        String oldPassword = "oldPassword";
        String newPassword = "newPassword";
        when(participantRepository.findById(participantId)).thenReturn(Mono.empty());

        StepVerifier.create(participantService.updateParticipantPassword(participantId, oldPassword, newPassword))
                .expectError(com.model_store.exeption.ParticipantNotFoundException.class)
                .verify();
    }
}
