package com.model_store.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.model_store.exception.ApiException;
import com.model_store.exception.constant.ErrorCode;
import com.model_store.model.base.Order;
import com.model_store.model.constant.OrderStatus;
import com.model_store.model.constant.Currency;
import com.model_store.model.dto.*;
import com.model_store.repository.OrderRepository;
import com.model_store.repository.OrderCaseRepository;
import com.model_store.service.ImageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class OrderTermsServiceTest {
    OrderRepository orders;
    OrderCaseRepository cases;
    ImageService images;
    DatabaseClient db;
    OrderTermsService service;
    Order order;

    @BeforeEach void setup() {
        orders = mock(OrderRepository.class); cases = mock(OrderCaseRepository.class); images = mock(ImageService.class);
        db = mock(DatabaseClient.class, RETURNS_DEEP_STUBS);
        when(db.sql(anyString()).bind(anyString(),any()).bind(anyString(),any())
            .bind(anyString(),any()).bind(anyString(),any()).fetch().rowsUpdated()).thenReturn(Mono.just(1L));
        service = new OrderTermsService(orders,cases,images,db,new ObjectMapper());
        order = Order.builder().id(1L).customerId(2L).sellerId(3L).status(OrderStatus.AWAITING_PAYMENT)
            .goodsAmount(new BigDecimal("4000.00")).deliveryAmount(new BigDecimal("500.00"))
            .confirmedPaidAmount(new BigDecimal("1000.00")).plannedPrepaymentAmount(new BigDecimal("1000.00"))
            .termsVersion(7L).pricingKnown(true).productCurrency(Currency.RUB)
            .deliveryCarrier("OZON").deliveryAddress("Москва").build();
        when(orders.findByIdForUpdate(1L)).thenAnswer(i -> Mono.just(order));
        when(orders.save(any(Order.class))).thenAnswer(i -> Mono.just(i.getArgument(0)));
        when(cases.findOpenByOrderId(1L)).thenReturn(Mono.empty());
        when(images.activateOrderProof(anyLong(),anyLong(),anyLong())).thenReturn(Mono.empty());
    }

    @Test void calculatesConfirmedBalanceAndUnknownDelivery() {
        var calculation = OrderTermsService.calculation(order);
        assertEquals(new BigDecimal("4500.00"),calculation.totalAmount());
        assertEquals(new BigDecimal("3500.00"),calculation.remainingAmount());
        order.setDeliveryAmount(null);
        assertNull(OrderTermsService.calculation(order).totalAmount());
        assertNull(OrderTermsService.calculation(order).remainingAmount());
        order.setDeliveryAmount(BigDecimal.ZERO.setScale(2));
        assertEquals(new BigDecimal("4000.00"),OrderTermsService.calculation(order).totalAmount());
    }

    @Test void onlyCatalogAmountsRoundAtUnitBoundary() {
        assertEquals(new BigDecimal("20.02"), OrderTermsService.catalogAmount(10.005f, 2));
        assertThrows(ApiException.class, () -> OrderTermsService.money(new BigDecimal("10.005")));
    }

    @Test void responseKeepsSnapshotDeliveryIconAndUsesOpenCaseForCancellation() {
        order.setDeliveryCarrier("RUSSIAN_POST");
        order.setConfirmedPaidAmount(BigDecimal.ZERO);
        order.setOpenCase(true);
        var response = OrderTermsService.enrich(order, FindOrderResponse.builder().build(), 2L);
        assertEquals(1L, response.getTransfer().getImageId());
        assertFalse(response.isCanCancel());
        order.setOpenCase(false);
        assertTrue(OrderTermsService.enrich(order, response, 2L).isCanCancel());
    }

    @Test void receiptDoesNotAuthorizeAssemblyOrIncreasePaidAmount() {
        service.submit(1L,2L,new OrderPaymentRequest(7L,10L,null),false).block();
        assertEquals(OrderStatus.AWAITING_PAYMENT_APPROVAL,order.getStatus());
        assertEquals(new BigDecimal("1000.00"),order.getConfirmedPaidAmount());
        assertEquals(8L,order.getTermsVersion());
        assertEquals(10L,order.getImagePaymentProofId());
    }

    @Test void approvalRecordsFullPaymentExactlyOnce() {
        order.setStatus(OrderStatus.AWAITING_PAYMENT_APPROVAL);
        service.review(1L,3L,9L,new OrderPaymentRequest(7L,null,null),true).block();
        assertEquals(OrderStatus.ASSEMBLING,order.getStatus());
        assertEquals(new BigDecimal("4500.00"),order.getConfirmedPaidAmount());
        expectCode(service.review(1L,3L,9L,new OrderPaymentRequest(7L,null,null),true),ErrorCode.ORDER_TERMS_STALE);
        verify(orders,times(1)).save(any());
    }

    @Test void returnRequiresReasonAndRetainsConfirmedPrepayment() {
        order.setStatus(OrderStatus.AWAITING_PAYMENT_APPROVAL);
        order.setImagePaymentProofId(10L);
        expectCode(service.review(1L,3L,3L,new OrderPaymentRequest(7L,null," "),false),ErrorCode.ORDER_PAYMENT_INVALID);
        service.review(1L,3L,3L,new OrderPaymentRequest(7L,null,"Чек не читается"),false).block();
        assertEquals(OrderStatus.AWAITING_PAYMENT,order.getStatus());
        assertEquals(new BigDecimal("1000.00"),order.getConfirmedPaidAmount());
        service.submit(1L,2L,new OrderPaymentRequest(8L,11L,null),false).block();
        assertEquals(OrderStatus.AWAITING_PAYMENT_APPROVAL,order.getStatus());
        assertEquals(11L,order.getImagePaymentProofId());
    }

    @Test void refusesStaleTermsAndWrongParty() {
        expectCode(service.submit(1L,2L,new OrderPaymentRequest(6L,10L,null),false),ErrorCode.ORDER_TERMS_STALE);
        expectCode(service.submit(1L,99L,new OrderPaymentRequest(7L,10L,null),false),ErrorCode.ACCESS_DENIED);
        verify(orders,never()).save(any());
    }

    @Test void unknownHistoricalPaymentIsNotZero() {
        order.setPricingKnown(false); order.setConfirmedPaidAmount(null);
        assertFalse(OrderTermsService.calculation(order).known());
        assertFalse(OrderTermsService.cancellable(order));
        expectCode(service.submit(1L,2L,new OrderPaymentRequest(7L,10L,null),false),ErrorCode.ORDER_LEGACY_RECONCILIATION_REQUIRED);
    }

    @Test void unconfirmedReceiptAllowsCancellationButConfirmedMoneyDoesNot() {
        order.setStatus(OrderStatus.AWAITING_PAYMENT_APPROVAL); order.setImagePaymentProofId(10L);
        order.setConfirmedPaidAmount(BigDecimal.ZERO);
        assertTrue(OrderTermsService.cancellable(order));
        order.setConfirmedPaidAmount(BigDecimal.ONE);
        assertFalse(OrderTermsService.cancellable(order));
        order.setStatus(OrderStatus.BOOKED);
        assertFalse(OrderTermsService.cancellable(order));
    }

    @Test void openCaseBlocksNormalPaymentReview() {
        when(cases.findOpenByOrderId(1L)).thenReturn(Mono.just(new com.model_store.model.base.OrderCase()));
        order.setStatus(OrderStatus.AWAITING_PAYMENT_APPROVAL);
        expectCode(service.review(1L,3L,3L,new OrderPaymentRequest(7L,null,null),true),ErrorCode.ORDER_CASE_ALREADY_OPEN);
        verify(orders,never()).save(any());
    }

    @Test void cannotChangeDeliveryDuringPaymentReviewOrAfterFullConfirmation() {
        order.setStatus(OrderStatus.AWAITING_PAYMENT_APPROVAL);
        var request = new OrderTermsRequest(7L,"YANDEX","Москва",BigDecimal.TEN,null);
        expectCode(service.delivery(1L,3L,3L,request),ErrorCode.ORDER_PAYMENT_INVALID);
        order.setStatus(OrderStatus.ASSEMBLING);
        expectCode(service.delivery(1L,3L,3L,request),ErrorCode.ORDER_PAYMENT_INVALID);
    }

    @Test void decimalsAndJsonAreExact() throws Exception {
        assertThrows(ApiException.class,() -> OrderTermsService.money(new BigDecimal("0.001")));
        assertThrows(ApiException.class,() -> OrderTermsService.money(new BigDecimal("-1")));
        assertThrows(ApiException.class,() -> OrderTermsService.money(new BigDecimal("100000000000000000")));
        String wire = new ObjectMapper().writeValueAsString(OrderTermsService.calculation(order));
        assertTrue(wire.contains("\"remainingAmount\":\"3500.00\""));
    }

    static void expectCode(Mono<?> result, ErrorCode code) {
        StepVerifier.create(result).expectErrorSatisfies(e -> {
            assertInstanceOf(ApiException.class,e); assertEquals(code,((ApiException)e).getCode());
        }).verify();
    }

    @Test void prepaymentConfirmationUsesExactPlannedAmountAndReturnAllowsCancellation() {
        order.setStatus(OrderStatus.AWAITING_PREPAYMENT_APPROVAL);
        order.setConfirmedPaidAmount(BigDecimal.ZERO.setScale(2));
        service.review(1L,3L,3L,new OrderPaymentRequest(7L,null,"Нужен другой чек"),false).block();
        assertEquals(OrderStatus.AWAITING_PREPAYMENT,order.getStatus());
        assertTrue(OrderTermsService.cancellable(order));
        service.submit(1L,2L,new OrderPaymentRequest(8L,10L,null),true).block();
        service.review(1L,3L,3L,new OrderPaymentRequest(9L,null,null),true).block();
        assertEquals(new BigDecimal("1000.00"),order.getConfirmedPaidAmount());
        assertEquals(OrderStatus.AWAITING_PAYMENT,order.getStatus());
        assertFalse(OrderTermsService.cancellable(order));
    }

    @Test void legacyReconciliationIsExplicitAndNotRepeatable() {
        order.setPricingKnown(false); order.setConfirmedPaidAmount(null);
        var request = new OrderReconciliationRequest(7L,"OZON","Москва",new BigDecimal("4000.00"),
                new BigDecimal("1000.00"),new BigDecimal("500.00"),new BigDecimal("300.00"),"Сверено по выписке");
        service.reconcile(1L,9L,request).block();
        assertEquals(new BigDecimal("300.00"),order.getConfirmedPaidAmount());
        assertTrue(order.getPricingKnown());
        expectCode(service.reconcile(1L,9L,request),ErrorCode.ORDER_TERMS_STALE);
    }

    @Test void changedDeliveryInvalidatesPriorPaymentCalculation() {
        service.delivery(1L,3L,3L,new OrderTermsRequest(7L,"YANDEX","Другой адрес",new BigDecimal("600.00"),null)).block();
        assertEquals(new BigDecimal("4600.00"),OrderTermsService.calculation(order).totalAmount());
        expectCode(service.submit(1L,2L,new OrderPaymentRequest(7L,10L,null),false),ErrorCode.ORDER_TERMS_STALE);
    }
}
