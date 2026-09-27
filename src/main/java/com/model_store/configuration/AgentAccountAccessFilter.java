package com.model_store.configuration;

import com.model_store.repository.ParticipantRepository;
import com.model_store.service.JwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Component
@RequiredArgsConstructor
public class AgentAccountAccessFilter implements WebFilter {
    private final JwtService jwtService;
    private final ParticipantRepository participants;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String token = exchange.getRequest().getHeaders().getFirst("Authorization");
        if (token == null || !token.startsWith("Bearer ")) {
            return chain.filter(exchange);
        }
        Long participantId;
        try {
            participantId = jwtService.getIdByAccessToken(token);
        } catch (RuntimeException invalidToken) {
            return chain.filter(exchange);
        }
        return participants.findByIdAndIsAgentTrue(participantId)
                .hasElement()
                .flatMap(isAgent -> {
                    boolean createAgentProduct = HttpMethod.POST.equals(exchange.getRequest().getMethod())
                            && "/agent/products".equals(exchange.getRequest().getPath().value());
                    if (isAgent && !createAgentProduct) {
                        exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
                        return exchange.getResponse().setComplete();
                    }
                    return chain.filter(exchange);
                });
    }
}
