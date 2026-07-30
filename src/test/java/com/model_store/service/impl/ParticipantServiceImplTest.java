package com.model_store.service.impl;

import com.model_store.model.base.Participant;
import com.model_store.model.constant.ParticipantStatus;
import com.model_store.repository.AccountRepository;
import com.model_store.repository.AddressRepository;
import com.model_store.repository.OrderRepository;
import com.model_store.repository.ParticipantRepository;
import com.model_store.repository.SellerRatingRepository;
import com.model_store.repository.SocialNetworkRepository;
import com.model_store.repository.TransferRepository;
import com.model_store.mapper.ParticipantMapper;
import com.model_store.service.ImageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

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
    private SellerRatingRepository sellerRatingRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private SocialNetworkRepository socialNetworkRepository;

    @InjectMocks
    private ParticipantServiceImpl participantService;

    @Test
    void resetAndUpdateTemplatePassword_activatesWaitingVerifyParticipant() {
        Participant participant = Participant.builder()
                .id(1L)
                .status(ParticipantStatus.WAITING_VERIFY)
                .password("old-password")
                .build();

        when(participantRepository.findById(participant.getId())).thenReturn(Mono.just(participant));
        when(passwordEncoder.encode(anyString())).thenReturn("encoded-password");
        when(participantRepository.save(any(Participant.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(participantService.resetAndUpdateTemplatePassword(participant.getId()))
                .expectNextMatches(password -> password.length() == 12)
                .verifyComplete();

        assertThat(participant.getStatus()).isEqualTo(ParticipantStatus.ACTIVE);
        assertThat(participant.getPassword()).isEqualTo("encoded-password");
    }
}
