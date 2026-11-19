package com.model_store.service.impl;

import com.model_store.service.IntegrationTest;
import com.model_store.service.OrderService;
import com.model_store.repository.OrderRepository;
import com.model_store.model.base.Order;
import com.model_store.model.base.Participant;
import com.model_store.model.constant.*;
import com.model_store.model.dto.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.math.BigDecimal;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class OrderTermsIntegrationTest extends IntegrationTest {
    @Autowired OrderTermsService terms;
    @Autowired OrderService ordersService;
    @Autowired OrderRepository orders;

    private Participant party() {
        String unique = UUID.randomUUID().toString();
        return participantRepository.save(Participant.builder().mail(unique+"@test.invalid").login(unique)
            .password("test").age(25).role(ParticipantRole.USER).status(ParticipantStatus.ACTIVE)
            .deadlineSending(3).deadlinePayment(7).sellerStatus(SellerStatus.DEFAULT).build()).block();
    }

    @Test void migrationCreatesReviewStatusAndNewCheckoutHasImmutableSnapshot() {
        var buyer = party(); var seller = party();
        var product = createPurchasableProduct(ProductStatus.ACTIVE,seller.getId()).block();
        Long id = ordersService.createOrders(java.util.List.of(CreateOrderRequest.builder().productId(product.getId())
            .count(2).deliveryCarrier("OZON").deliveryAddress("Москва, адрес при оформлении").build()),buyer.getId()).block().getFirst();
        var saved = orders.findById(id).block();
        assertEquals("Москва, адрес при оформлении",saved.getDeliveryAddress());
        assertNull(saved.getAddressId()); assertNull(saved.getTransferId());
        assertEquals(new BigDecimal("3000.00"),saved.getGoodsAmount());
        assertEquals(new BigDecimal("0.00"),saved.getConfirmedPaidAmount());
        terms.delivery(id,seller.getId(),seller.getId(),new OrderTermsRequest(0L,"YANDEX","Москва, новый адрес",new BigDecimal("500.00"),null)).block();
        assertEquals(new BigDecimal("3500.00"),OrderTermsService.calculation(orders.findById(id).block()).totalAmount());
        assertEquals(1,terms.history(id,buyer.getId()).collectList().block().size());
    }

    @Test void cancellationAndApprovalSerializeAndNeverBothSucceed() {
        var buyer = party(); var seller = party();
        var product = createPurchasableProduct(ProductStatus.ACTIVE,seller.getId()).block();
        Long id = ordersService.createOrders(java.util.List.of(CreateOrderRequest.builder().productId(product.getId())
            .count(2).deliveryCarrier("OZON").deliveryAddress("Москва").build()),buyer.getId()).block().getFirst();
        Order o = orders.findById(id).block();
        o.setStatus(OrderStatus.AWAITING_PAYMENT_APPROVAL); o.setDeliveryAmount(BigDecimal.ZERO); o.setImagePaymentProofId(123L);
        orders.save(o).block();
        var close = new CloseOrderRequest(); close.setComment("Передумал");
        var results = Mono.zip(
            terms.review(id,seller.getId(),seller.getId(),new OrderPaymentRequest(0L,null,null),true)
                .materialize().subscribeOn(Schedulers.parallel()),
            ordersService.closureOrder(id,close,buyer.getId()).materialize().subscribeOn(Schedulers.parallel())).block();
        assertTrue(results.getT1().isOnNext() ^ results.getT2().isOnNext());
        Order finalOrder = orders.findById(id).block();
        if (results.getT1().isOnNext()) {
            assertEquals(OrderStatus.ASSEMBLING,finalOrder.getStatus());
            assertEquals(new BigDecimal("3000.00"),finalOrder.getConfirmedPaidAmount());
            assertEquals(8,productRepository.findById(product.getId()).block().getCount());
        } else {
            assertEquals(OrderStatus.CANCELLED,finalOrder.getStatus());
            assertEquals(10,productRepository.findById(product.getId()).block().getCount());
            assertThrows(Exception.class,() -> ordersService.closureOrder(id,close,buyer.getId()).block());
            assertEquals(10,productRepository.findById(product.getId()).block().getCount());
        }
    }
}
