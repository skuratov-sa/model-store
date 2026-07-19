package com.model_store.service.impl;

import com.model_store.configuration.property.ApplicationProperties;
import com.model_store.model.dto.FindParticipantByLoginDto;
import jakarta.mail.BodyPart;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.javamail.JavaMailSender;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmailServiceImplTest {

    @Mock
    private JavaMailSender mailSender;

    @Mock
    private VerificationCodeServiceImpl verificationCodeService;

    @Mock
    private ParticipantServiceImpl participantService;

    private EmailServiceImpl emailService;

    @BeforeEach
    void setUp() throws Exception {
        ApplicationProperties properties = new ApplicationProperties();
        properties.setEmailFrom("misterstes8@gmail.com");
        properties.setEmailReplyTo("figurzilla@mail.ru");

        emailService = new EmailServiceImpl(mailSender, properties, verificationCodeService, participantService);
        emailService.init();
    }

    @Test
    void sendVerificationWithoutLimitCode_setsDomainFromAndMailRuReplyTo() throws Exception {
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        FindParticipantByLoginDto participant = FindParticipantByLoginDto.builder()
                .id(42L)
                .mail("user@example.com")
                .build();

        when(mailSender.createMimeMessage()).thenReturn(message);
        when(participantService.findByMail("user@example.com")).thenReturn(Mono.just(participant));
        when(verificationCodeService.addCode(eq(participant.getId()), anyString())).thenReturn(Mono.empty());

        StepVerifier.create(emailService.sendVerificationWithoutLimitCode("user@example.com"))
                .expectNext(participant.getId())
                .verifyComplete();

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());

        MimeMessage sentMessage = captor.getValue();
        assertThat(((InternetAddress) sentMessage.getFrom()[0]).getAddress()).isEqualTo("misterstes8@gmail.com");
        assertThat(((InternetAddress) sentMessage.getReplyTo()[0]).getAddress()).isEqualTo("figurzilla@mail.ru");
        assertThat(((InternetAddress) sentMessage.getRecipients(Message.RecipientType.TO)[0]).getAddress()).isEqualTo("user@example.com");
        assertThat(sentMessage.getSubject()).isEqualTo("Подтверждение почты");
        assertThat(containsHtmlPart(sentMessage.getContent())).isTrue();
    }

    private boolean containsHtmlPart(Object content) throws Exception {
        if (content instanceof String) {
            return ((String) content).contains("html");
        }
        if (!(content instanceof Multipart multipart)) {
            return false;
        }

        for (int i = 0; i < multipart.getCount(); i++) {
            BodyPart bodyPart = multipart.getBodyPart(i);
            if (bodyPart.isMimeType("text/html") || containsHtmlPart(bodyPart.getContent())) {
                return true;
            }
        }
        return false;
    }
}
