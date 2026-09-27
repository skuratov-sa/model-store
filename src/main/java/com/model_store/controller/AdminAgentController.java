package com.model_store.controller;

import com.model_store.model.CreateOrUpdateProductRequest;
import com.model_store.model.base.Account;
import com.model_store.model.base.Address;
import com.model_store.model.base.Product;
import com.model_store.model.base.SocialNetwork;
import com.model_store.model.base.Transfer;
import com.model_store.model.constant.OrderStatus;
import com.model_store.model.dto.AccountDto;
import com.model_store.model.dto.AddressDto;
import com.model_store.model.dto.AgentProfileDto;
import com.model_store.model.dto.AgentSummaryDto;
import com.model_store.model.dto.FindOrderResponse;
import com.model_store.model.dto.SocialNetworkDto;
import com.model_store.model.dto.TransferDto;
import com.model_store.model.dto.UpdateAgentProfileRequest;
import com.model_store.service.AccountsService;
import com.model_store.service.AddressService;
import com.model_store.service.JwtService;
import com.model_store.service.SocialNetworksService;
import com.model_store.service.TransferService;
import com.model_store.service.impl.AdminAgentService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/admin/actions")
@RequiredArgsConstructor
public class AdminAgentController {
    private final AdminAgentService agents;
    private final JwtService jwtService;
    private final SocialNetworksService socialNetworks;
    private final TransferService transfers;
    private final AccountsService accounts;
    private final AddressService addresses;

    @GetMapping("/agents")
    public Flux<AgentSummaryDto> listAgents() {
        return agents.agents();
    }

    @GetMapping("/agent-orders")
    public Flux<FindOrderResponse> allAgentOrders(@RequestParam(required = false) OrderStatus status,
                                                   @RequestParam(defaultValue = "0") int page,
                                                   @RequestParam(defaultValue = "50") int size) {
        return agents.orders(null, status, page, size);
    }

    @GetMapping("/agents/{agentId}/orders")
    public Flux<FindOrderResponse> agentOrders(@PathVariable Long agentId,
                                                @RequestParam(required = false) OrderStatus status,
                                                @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "50") int size) {
        return agents.orders(agentId, status, page, size);
    }

    @GetMapping("/agents/{agentId}/products")
    public Flux<Product> agentProducts(@PathVariable Long agentId) {
        return agents.products(agentId);
    }

    @GetMapping("/products/{productId}")
    public Mono<Product> product(@PathVariable Long productId) {
        return agents.product(productId);
    }

    @PutMapping("/agents/{agentId}/products/{productId}")
    public Mono<Void> updateProduct(@PathVariable Long agentId, @PathVariable Long productId,
                                    @RequestBody CreateOrUpdateProductRequest request) {
        return agents.updateProduct(agentId, productId, request);
    }

    @PostMapping("/agents/{agentId}/products/{productId}/extend")
    public Mono<Void> extendProduct(@PathVariable Long agentId, @PathVariable Long productId) {
        return agents.extendProduct(agentId, productId);
    }

    @GetMapping("/agents/{agentId}/profile")
    public Mono<AgentProfileDto> profile(@PathVariable Long agentId) {
        return agents.profile(agentId);
    }

    @PutMapping("/agents/{agentId}/profile")
    public Mono<AgentProfileDto> updateProfile(@PathVariable Long agentId,
                                                @RequestBody UpdateAgentProfileRequest request) {
        return agents.updateProfile(agentId, request);
    }

    @PostMapping("/agents/{agentId}/orders/{orderId}/{action}")
    public Mono<Long> actOnOrder(@RequestHeader("Authorization") String authorizationHeader,
                                  @PathVariable Long agentId, @PathVariable Long orderId,
                                  @PathVariable String action,
                                  @RequestParam(required = false) String comment,
                                  @RequestParam(required = false) String deliveryUrl) {
        return agents.actOnOrder(jwtService.getIdByAccessToken(authorizationHeader),
                agentId, orderId, action, comment, deliveryUrl);
    }

    @GetMapping("/agents/{agentId}/social-networks")
    public Flux<SocialNetwork> socialNetworks(@PathVariable Long agentId) {
        return agents.requireAgent(agentId).thenMany(socialNetworks.findByParticipantId(agentId));
    }

    @PostMapping("/agents/{agentId}/social-networks")
    public Mono<Void> createSocialNetwork(@PathVariable Long agentId, @RequestBody SocialNetworkDto dto) {
        return agents.requireAgent(agentId).then(socialNetworks.create(agentId, dto));
    }

    @PutMapping("/agents/{agentId}/social-networks/{id}")
    public Mono<SocialNetwork> updateSocialNetwork(@PathVariable Long agentId, @PathVariable Long id,
                                                    @RequestBody SocialNetworkDto dto) {
        return agents.requireAgent(agentId).then(socialNetworks.update(agentId, id, dto));
    }

    @DeleteMapping("/agents/{agentId}/social-networks/{id}")
    public Mono<Void> deleteSocialNetwork(@PathVariable Long agentId, @PathVariable Long id) {
        return agents.requireAgent(agentId).then(socialNetworks.delete(agentId, id));
    }

    @GetMapping("/agents/{agentId}/transfers")
    public Flux<Transfer> transfers(@PathVariable Long agentId) {
        return agents.requireAgent(agentId).thenMany(transfers.findByParticipantId(agentId));
    }

    @PostMapping("/agents/{agentId}/transfers")
    public Mono<Void> createTransfer(@PathVariable Long agentId, @RequestBody TransferDto dto) {
        return agents.requireAgent(agentId).then(transfers.create(agentId, dto));
    }

    @PutMapping("/agents/{agentId}/transfers/{id}")
    public Mono<Transfer> updateTransfer(@PathVariable Long agentId, @PathVariable Long id,
                                          @RequestBody TransferDto dto) {
        return agents.requireAgent(agentId).then(transfers.update(agentId, id, dto));
    }

    @DeleteMapping("/agents/{agentId}/transfers/{id}")
    public Mono<Void> deleteTransfer(@PathVariable Long agentId, @PathVariable Long id) {
        return agents.requireAgent(agentId).then(transfers.softDelete(agentId, id));
    }

    @GetMapping("/agents/{agentId}/accounts")
    public Flux<Account> accounts(@PathVariable Long agentId) {
        return agents.requireAgent(agentId).thenMany(accounts.findByParticipantId(agentId));
    }

    @PostMapping("/agents/{agentId}/accounts")
    public Mono<Void> createAccount(@PathVariable Long agentId, @RequestBody AccountDto dto) {
        return agents.requireAgent(agentId).then(accounts.create(agentId, dto));
    }

    @PutMapping("/agents/{agentId}/accounts/{id}")
    public Mono<Account> updateAccount(@PathVariable Long agentId, @PathVariable Long id,
                                        @RequestBody AccountDto dto) {
        return agents.requireAgent(agentId).then(accounts.update(agentId, id, dto));
    }

    @DeleteMapping("/agents/{agentId}/accounts/{id}")
    public Mono<Void> deleteAccount(@PathVariable Long agentId, @PathVariable Long id) {
        return agents.requireAgent(agentId).then(accounts.delete(agentId, id));
    }

    @GetMapping("/agents/{agentId}/addresses")
    public Flux<Address> addresses(@PathVariable Long agentId) {
        return agents.requireAgent(agentId).thenMany(addresses.findByParticipantId(agentId));
    }

    @PostMapping("/agents/{agentId}/addresses")
    public Mono<Long> createAddress(@PathVariable Long agentId, @RequestBody AddressDto dto) {
        return agents.requireAgent(agentId).then(addresses.addAddresses(agentId, dto));
    }

    @PutMapping("/agents/{agentId}/addresses/{id}")
    public Mono<Address> updateAddress(@PathVariable Long agentId, @PathVariable Long id,
                                        @RequestBody AddressDto dto) {
        return agents.requireAgent(agentId).then(addresses.updateAddress(agentId, id, dto));
    }

    @DeleteMapping("/agents/{agentId}/addresses/{id}")
    public Mono<Void> deleteAddress(@PathVariable Long agentId, @PathVariable Long id) {
        return agents.requireAgent(agentId).then(addresses.softDelete(agentId, id));
    }
}
