package com.model_store.service.impl;

import com.model_store.configuration.AgentAccountAccessFilter;
import com.model_store.model.base.Participant;
import com.model_store.repository.ParticipantRepository;
import com.model_store.service.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentAccountAccessFilterTest {
    private final JwtService jwt = mock(JwtService.class);
    private final ParticipantRepository participants = mock(ParticipantRepository.class);
    private final AgentAccountAccessFilter filter = new AgentAccountAccessFilter(jwt, participants);

    @Test
    void botCannotUseOrdinaryOrderEndpoint() {
        when(jwt.getIdByAccessToken("Bearer token")).thenReturn(7L);
        when(participants.findByIdAndIsAgentTrue(7L)).thenReturn(Mono.just(new Participant()));
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/order/11/AWAITING_PAYMENT")
                .header("Authorization", "Bearer token"));
        AtomicBoolean nextCalled = new AtomicBoolean();

        StepVerifier.create(filter.filter(exchange, ignored -> {
            nextCalled.set(true);
            return Mono.empty();
        })).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(nextCalled).isFalse();
    }

    @Test
    void botCanCallProductIngestion() {
        when(jwt.getIdByAccessToken("Bearer token")).thenReturn(7L);
        when(participants.findByIdAndIsAgentTrue(7L)).thenReturn(Mono.just(new Participant()));
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/agent/products")
                .header("Authorization", "Bearer token"));
        AtomicBoolean nextCalled = new AtomicBoolean();

        StepVerifier.create(filter.filter(exchange, ignored -> {
            nextCalled.set(true);
            return Mono.empty();
        })).verifyComplete();

        assertThat(nextCalled).isTrue();
    }

    @Test
    void botCanUploadTemporaryProductImages() {
        when(jwt.getIdByAccessToken("Bearer token")).thenReturn(7L);
        when(participants.findByIdAndIsAgentTrue(7L)).thenReturn(Mono.just(new Participant()));
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/images?tag=PRODUCT")
                .header("Authorization", "Bearer token"));
        AtomicBoolean nextCalled = new AtomicBoolean();

        StepVerifier.create(filter.filter(exchange, ignored -> {
            nextCalled.set(true);
            return Mono.empty();
        })).verifyComplete();

        assertThat(nextCalled).isTrue();
    }

    @Test
    void botCannotUploadImageForAnExistingEntity() {
        when(jwt.getIdByAccessToken("Bearer token")).thenReturn(7L);
        when(participants.findByIdAndIsAgentTrue(7L)).thenReturn(Mono.just(new Participant()));
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/images?tag=PRODUCT&entityId=8")
                .header("Authorization", "Bearer token"));
        AtomicBoolean nextCalled = new AtomicBoolean();

        StepVerifier.create(filter.filter(exchange, ignored -> {
            nextCalled.set(true);
            return Mono.empty();
        })).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(nextCalled).isFalse();
    }
}
