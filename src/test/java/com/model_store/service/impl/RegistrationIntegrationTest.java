package com.model_store.service.impl;

import com.model_store.service.IntegrationTest;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@AutoConfigureWebTestClient
class RegistrationIntegrationTest extends IntegrationTest {
    @Autowired WebTestClient client;
    @Autowired JavaMailSender mail;

    @Test
    void concurrentRegistrationSendsOneMessageAndDuplicatesSendNone() {
        clearInvocations(mail);
        when(mail.createMimeMessage()).thenAnswer(call -> new MimeMessage(Session.getInstance(new Properties())));
        String email = UUID.randomUUID() + "@example.test";
        var first = CompletableFuture.supplyAsync(() -> register(email));
        var second = CompletableFuture.supplyAsync(() -> register(" " + email.toUpperCase(java.util.Locale.ROOT) + " "));
        assertThat(java.util.List.of(first.join(), second.join())).containsExactlyInAnyOrder(200, 404);
        assertThat(register(email)).isEqualTo(404);

        var existing = participantRepository.findByMail(email).block();
        assertThat(existing.getMail()).isEqualTo(email);
        existing.setStatus(com.model_store.model.constant.ParticipantStatus.ACTIVE);
        participantRepository.save(existing).block();
        assertThat(register(email)).isEqualTo(404);
        verify(mail, times(1)).send(any(MimeMessage.class));
    }

    private int register(String email) {
        var response = client.post().uri("/participant")
                .bodyValue(Map.of("mail", email, "password", "Registration-test-123", "age", 25))
                .exchange().expectBody().returnResult();
        int status = response.getStatus().value();
        if (status == 404) {
            assertThat(new String(response.getResponseBody(), java.nio.charset.StandardCharsets.UTF_8))
                    .contains("PARTICIPANT_ALREADY_EXISTS");
        }
        return status;
    }
}
