package com.model_store.service.impl;

import com.model_store.exception.ApiException;
import com.model_store.model.CreateOrUpdateProductRequest;
import com.model_store.model.base.AdminAgentOrderAction;
import com.model_store.model.base.Order;
import com.model_store.model.base.Participant;
import com.model_store.repository.AdminAgentOrderActionRepository;
import com.model_store.repository.OrderRepository;
import com.model_store.repository.ParticipantRepository;
import com.model_store.repository.ProductRepository;
import com.model_store.service.ImageService;
import com.model_store.service.OrderService;
import com.model_store.service.ProductService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminAgentServiceTest {
    @Mock ParticipantRepository participants;
    @Mock ProductRepository products;
    @Mock OrderRepository orders;
    @Mock AdminAgentOrderActionRepository audit;
    @Mock ProductService productService;
    @Mock OrderService orderService;
    @Mock ImageService imageService;

    private AdminAgentService service;

    @BeforeEach
    void setUp() {
        service = new AdminAgentService(participants, products, orders, audit,
                productService, orderService, imageService);
    }

    @Test
    void adminActionKeepsBotSellerAndRecordsAdminIdentity() {
        Participant bot = new Participant();
        bot.setId(7L);
        bot.setIsAgent(true);
        Order order = new Order();
        order.setId(11L);
        order.setSellerId(7L);
        when(participants.findByIdAndIsAgentTrue(7L)).thenReturn(Mono.just(bot));
        when(orders.findById(11L)).thenReturn(Mono.just(order));
        when(orderService.agreementOrder(11L, "ok", 7L)).thenReturn(Mono.just(11L));
        when(audit.save(any(AdminAgentOrderAction.class))).thenAnswer(invocation -> {
            AdminAgentOrderAction record = invocation.getArgument(0);
            assertThat(record.getAdminId()).isEqualTo(42L);
            assertThat(record.getAgentId()).isEqualTo(7L);
            assertThat(record.getOrderId()).isEqualTo(11L);
            assertThat(record.getAction()).isEqualTo("CONFIRM");
            return Mono.just(record);
        });

        StepVerifier.create(service.actOnOrder(42L, 7L, 11L, "CONFIRM", "ok", null))
                .expectNext(11L)
                .verifyComplete();
        assertThat(order.getSellerId()).isEqualTo(7L);
    }

    @Test
    void adminCannotActOnOrdinarySellersOrderThroughAgentRoute() {
        Participant bot = new Participant();
        bot.setId(7L);
        Order order = new Order();
        order.setId(11L);
        order.setSellerId(8L);
        when(participants.findByIdAndIsAgentTrue(7L)).thenReturn(Mono.just(bot));
        when(orders.findById(11L)).thenReturn(Mono.just(order));

        StepVerifier.create(service.actOnOrder(42L, 7L, 11L, "CONFIRM", null, null))
                .expectError(ApiException.class)
                .verify();
        verify(orderService, never()).agreementOrder(any(), any(), any());
        verify(audit, never()).save(any());
    }

    @Test
    void adminCannotEditOrdinarySellersProductThroughAgentRoute() {
        when(participants.findByIdAndIsAgentTrue(8L)).thenReturn(Mono.empty());

        StepVerifier.create(service.updateProduct(8L, 5L, new CreateOrUpdateProductRequest()))
                .expectError(ApiException.class)
                .verify();
        verify(productService, never()).updateAgentProduct(any(), any(), any());
    }

    @Test
    void adminCanExtendBotProductAsItsOwner() {
        Participant bot = new Participant();
        bot.setId(7L);
        bot.setIsAgent(true);
        when(participants.findByIdAndIsAgentTrue(7L)).thenReturn(Mono.just(bot));
        when(productService.extendExpirationDate(5L, 7L)).thenReturn(Mono.empty());

        StepVerifier.create(service.extendProduct(7L, 5L)).verifyComplete();

        verify(productService).extendExpirationDate(5L, 7L);
    }

    @Test
    void adminCannotExtendThroughAnOrdinarySellerId() {
        when(participants.findByIdAndIsAgentTrue(8L)).thenReturn(Mono.empty());

        StepVerifier.create(service.extendProduct(8L, 5L))
                .expectError(ApiException.class)
                .verify();

        verify(productService, never()).extendExpirationDate(any(), any());
    }

    @Test
    void adminProfileIncludesCurrentImageId() {
        Participant bot = new Participant();
        bot.setId(7L);
        when(participants.findByIdAndIsAgentTrue(7L)).thenReturn(Mono.just(bot));
        when(imageService.findMainImage(7L, com.model_store.model.constant.ImageTag.PARTICIPANT))
                .thenReturn(Mono.just(99L));

        StepVerifier.create(service.profile(7L))
                .assertNext(profile -> assertThat(profile.imageId()).isEqualTo(99L))
                .verifyComplete();
    }
}
