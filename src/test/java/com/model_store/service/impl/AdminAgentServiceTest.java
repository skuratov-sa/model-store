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
    @Mock OrderTermsService terms;

    private AdminAgentService service;

    @BeforeEach
    void setUp() {
        Participant admin = new Participant();
        admin.setId(42L);
        admin.setRole(com.model_store.model.constant.ParticipantRole.ADMIN);
        admin.setStatus(com.model_store.model.constant.ParticipantStatus.ACTIVE);
        org.mockito.Mockito.lenient().when(participants.findById(42L)).thenReturn(Mono.just(admin));
        service = new AdminAgentService(terms, participants, products, orders, audit,
                productService, orderService, imageService);
    }

    @Test
    void directAgentPaymentReviewRecordsExistingAudit() {
        var request = new com.model_store.model.dto.OrderPaymentRequest(2L, null, "Нужен новый чек");
        when(terms.review(11L, 7L, 42L, request, false)).thenReturn(Mono.just(11L));
        when(audit.save(any(AdminAgentOrderAction.class))).thenAnswer(call -> {
            AdminAgentOrderAction entry = call.getArgument(0);
            assertThat(entry.getOrderId()).isEqualTo(11L);
            assertThat(entry.getAdminId()).isEqualTo(42L);
            assertThat(entry.getAgentId()).isEqualTo(7L);
            assertThat(entry.getAction()).isEqualTo("RETURN_PAYMENT");
            return Mono.just(entry);
        });
        StepVerifier.create(service.reviewPayment(42L, 7L, 11L, request, false))
                .expectNext(11L).verifyComplete();
        verify(audit).save(any(AdminAgentOrderAction.class));
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
