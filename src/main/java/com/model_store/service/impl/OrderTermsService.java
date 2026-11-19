package com.model_store.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.model_store.exception.ApiErrors;
import com.model_store.exception.constant.ErrorCode;
import com.model_store.model.base.Order;
import com.model_store.model.constant.OrderStatus;
import com.model_store.model.dto.*;
import com.model_store.repository.OrderRepository;
import com.model_store.repository.OrderCaseRepository;
import com.model_store.service.ImageService;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

@Service
@RequiredArgsConstructor
public class OrderTermsService {
    private final OrderRepository orders;
    private final OrderCaseRepository cases;
    private final ImageService images;
    private final DatabaseClient db;
    private final ObjectMapper json;

    public static void validateDelivery(String carrier, String address) {
        if (!Set.of("OZON", "YANDEX", "RUSSIAN_POST").contains(carrier == null ? "" : carrier)
                || address == null || address.isBlank() || address.length() > 2000)
            throw ApiErrors.badRequest(ErrorCode.INVALID_TRANSFER, "Укажите службу и адрес доставки");
    }

    public static BigDecimal money(BigDecimal value) {
        if (value == null || value.signum() < 0 || value.precision() > 19)
            throw ApiErrors.badRequest(ErrorCode.ORDER_PAYMENT_INVALID, "Некорректная сумма");
        try {
            BigDecimal scaled = value.setScale(2, RoundingMode.UNNECESSARY);
            if (scaled.precision() > 19) throw new ArithmeticException();
            return scaled;
        }
        catch (ArithmeticException e) { throw ApiErrors.badRequest(ErrorCode.ORDER_PAYMENT_INVALID, "Точность суммы — 2 знака"); }
    }

    static BigDecimal catalogAmount(Float unitPrice, int count) {
        if (unitPrice == null || !Float.isFinite(unitPrice) || unitPrice < 0) {
            throw ApiErrors.badRequest(ErrorCode.ORDER_PAYMENT_INVALID, "Некорректная цена в каталоге");
        }
        return money(new BigDecimal(unitPrice.toString()).setScale(2, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(count)));
    }

    public static OrderCalculation calculation(Order o) {
        boolean known = Boolean.TRUE.equals(o.getPricingKnown());
        BigDecimal total = known && o.getGoodsAmount() != null && o.getDeliveryAmount() != null
                ? o.getGoodsAmount().add(o.getDeliveryAmount()) : null;
        return new OrderCalculation(known, o.getProductCurrency(), 2, o.getGoodsAmount(), o.getDeliveryAmount(),
                total, o.getConfirmedPaidAmount(), total == null || o.getConfirmedPaidAmount() == null
                ? null : total.subtract(o.getConfirmedPaidAmount()));
    }

    static boolean expectsPrepayment(Order order) {
        if (order.getPlannedPrepaymentAmount() != null) {
            return order.getPlannedPrepaymentAmount().signum() > 0;
        }
        return order.getPrepaymentAmount() != null && order.getPrepaymentAmount() > 0;
    }

    static FindOrderResponse enrich(Order order, FindOrderResponse response, Long buyerId) {
        response.setCalculation(calculation(order));
        response.setDeliveryCarrier(order.getDeliveryCarrier());
        response.setDeliveryAddress(order.getDeliveryAddress());
        response.setTermsVersion(order.getTermsVersion());
        if (Boolean.TRUE.equals(order.getPricingKnown())) {
            Long icon = Arrays.stream(com.model_store.model.constant.ShippingMethodsType.values())
                    .filter(method -> method.name().equals(order.getDeliveryCarrier()))
                    .map(com.model_store.service.util.UtilService::getImageId)
                    .findFirst().orElse(null);
            response.setTransfer(OrderTransferDto.builder().transferId(order.getTransferId()).addressId(order.getAddressId())
                    .address(order.getDeliveryAddress()).currency(order.getProductCurrency()).imageId(icon)
                    .price(order.getDeliveryAmount() == null ? null : order.getDeliveryAmount().floatValue()).build());
        }
        response.setHasConfirmedPayment(order.getConfirmedPaidAmount() == null
                ? null : order.getConfirmedPaidAmount().signum() > 0);
        response.setCanCancel(Objects.equals(buyerId, order.getCustomerId())
                && Boolean.FALSE.equals(order.getOpenCase()) && cancellable(order));
        return response;
    }

    public static boolean cancellable(Order o) {
        return (o.getStatus() == OrderStatus.BOOKED && (!Boolean.TRUE.equals(o.getPricingKnown())
                || (o.getConfirmedPaidAmount() != null && o.getConfirmedPaidAmount().signum() == 0))) || (Boolean.TRUE.equals(o.getPricingKnown())
                && o.getConfirmedPaidAmount() != null && o.getConfirmedPaidAmount().signum() == 0
                && Set.of(OrderStatus.AWAITING_PREPAYMENT, OrderStatus.AWAITING_PREPAYMENT_APPROVAL,
                    OrderStatus.AWAITING_PAYMENT, OrderStatus.AWAITING_PAYMENT_APPROVAL).contains(o.getStatus()));
    }

    private Mono<Order> locked(Long id, Long actor, Long version, boolean seller) {
        return orders.findByIdForUpdate(id).switchIfEmpty(Mono.error(ApiErrors.notFound(ErrorCode.ORDER_NOT_FOUND, "Заказ не найден")))
            .flatMap(o -> {
                if (!Objects.equals(actor, seller ? o.getSellerId() : o.getCustomerId()))
                    return Mono.error(ApiErrors.forbidden(ErrorCode.ACCESS_DENIED, "Нет доступа к заказу"));
                if (!Boolean.TRUE.equals(o.getPricingKnown()))
                    return Mono.error(ApiErrors.alreadyExist(ErrorCode.ORDER_LEGACY_RECONCILIATION_REQUIRED, "Исторические суммы требуют сверки"));
                if (version == null)
                    return Mono.error(ApiErrors.alreadyExist(ErrorCode.ORDER_TERMS_STALE,
                            "Получите актуальный заказ и передайте его termsVersion в expectedVersion"));
                if (!Objects.equals(version, o.getTermsVersion()))
                    return Mono.error(ApiErrors.alreadyExist(ErrorCode.ORDER_TERMS_STALE, "Обновите условия заказа"));
                return cases.findOpenByOrderId(id).hasElement().flatMap(open -> open
                    ? Mono.error(ApiErrors.alreadyExist(ErrorCode.ORDER_CASE_ALREADY_OPEN, "По заказу открыто обращение")) : Mono.just(o));
            });
    }

    @Transactional
    public Mono<Long> delivery(Long id, Long seller, Long actor, OrderTermsRequest request) {
        return locked(id, seller, request.expectedVersion(), true).flatMap(o -> {
            validateDelivery(request.carrier(), request.address());
            if (!Set.of(OrderStatus.BOOKED, OrderStatus.AWAITING_PREPAYMENT, OrderStatus.AWAITING_PAYMENT).contains(o.getStatus()))
                return invalid();
            BigDecimal next = request.deliveryAmount() == null ? null : money(request.deliveryAmount());
            if (next != null) money(o.getGoodsAmount().add(next));
            if (o.getConfirmedPaidAmount().signum() > 0 && (next == null
                    || o.getGoodsAmount().add(next).compareTo(o.getConfirmedPaidAmount()) < 0)) return invalid();
            Map<String,Object> before = new LinkedHashMap<>();
            before.put("carrier", o.getDeliveryCarrier()); before.put("address", o.getDeliveryAddress());
            before.put("deliveryAmount", o.getDeliveryAmount()); before.put("version", o.getTermsVersion());
            o.setDeliveryCarrier(request.carrier()); o.setDeliveryAddress(request.address()); o.setDeliveryAmount(next);
            o.setTermsVersion(o.getTermsVersion() + 1);
            return save(o, actor, "DELIVERY_CHANGED", Map.of("before", before, "after", request));
        });
    }

    @Transactional
    public Mono<Long> submit(Long id, Long buyer, OrderPaymentRequest request, boolean prepayment) {
        return locked(id, buyer, request.expectedVersion(), false).flatMap(o -> {
            if (o.getStatus() != (prepayment ? OrderStatus.AWAITING_PREPAYMENT : OrderStatus.AWAITING_PAYMENT)
                    || request.imageId() == null || (!prepayment && calculation(o).totalAmount() == null)
                    || (prepayment && (o.getPlannedPrepaymentAmount() == null || o.getPlannedPrepaymentAmount().signum() <= 0))) return invalid();
            o.setImagePaymentProofId(request.imageId());
            o.setStatus(prepayment ? OrderStatus.AWAITING_PREPAYMENT_APPROVAL : OrderStatus.AWAITING_PAYMENT_APPROVAL);
            o.setTermsVersion(o.getTermsVersion() + 1);
            return images.activateOrderProof(request.imageId(), id, buyer)
                .then(save(o, buyer, "PAYMENT_SUBMITTED", request));
        });
    }

    @Transactional
    public Mono<Long> review(Long id, Long seller, Long actor, OrderPaymentRequest request, boolean approve) {
        return review(id, seller, actor, request, approve, null);
    }

    @Transactional
    public Mono<Long> review(Long id, Long seller, Long actor, OrderPaymentRequest request, boolean approve,
                             OrderStatus requiredStage) {
        return locked(id, seller, request.expectedVersion(), true).flatMap(o -> {
            boolean prepayment = o.getStatus() == OrderStatus.AWAITING_PREPAYMENT_APPROVAL;
            if (!prepayment && o.getStatus() != OrderStatus.AWAITING_PAYMENT_APPROVAL) return invalid();
            if (requiredStage != null && o.getStatus() != requiredStage) return invalid();
            if ((request.reason() != null && request.reason().length() > 500)
                    || (!approve && (request.reason() == null || request.reason().isBlank())))
                return Mono.error(ApiErrors.badRequest(ErrorCode.ORDER_PAYMENT_INVALID, "Укажите причину возврата на оплату"));
            if (approve) {
                BigDecimal paid = prepayment ? o.getPlannedPrepaymentAmount() : calculation(o).totalAmount();
                if (paid == null || paid.compareTo(o.getConfirmedPaidAmount()) < 0) return invalid();
                o.setConfirmedPaidAmount(paid);
            }
            o.setStatus(approve ? (prepayment ? OrderStatus.AWAITING_PAYMENT : OrderStatus.ASSEMBLING)
                    : (prepayment ? OrderStatus.AWAITING_PREPAYMENT : OrderStatus.AWAITING_PAYMENT));
            o.setComment(request.reason()); o.setTermsVersion(o.getTermsVersion() + 1);
            Map<String,Object> event = new LinkedHashMap<>();
            event.put("request", request); event.put("imageId", o.getImagePaymentProofId());
            event.put("confirmedPaidAmount", o.getConfirmedPaidAmount());
            return save(o, actor, approve ? "PAYMENT_APPROVED" : "PAYMENT_RETURNED", event);
        });
    }

    private Mono<Long> save(Order order, Long actor, String kind, Object payload) {
        return orders.save(order).flatMap(saved -> {
            final String value;
            try { value = json.writeValueAsString(payload); }
            catch (Exception e) { return Mono.error(e); }
            return db.sql("INSERT INTO order_terms_event(order_id,actor_id,kind,payload) VALUES (:id,:actor,:kind,:payload)")
                .bind("id", order.getId()).bind("actor", actor).bind("kind", kind).bind("payload", value)
                .fetch().rowsUpdated().thenReturn(saved.getId());
        });
    }

    @Transactional
    public Mono<Long> disputeEvidence(Long id, Long buyer, OrderPaymentRequest request) {
        return orders.findByIdForUpdate(id)
            .filter(o -> Objects.equals(buyer,o.getCustomerId()) && o.getStatus() == OrderStatus.DISPUTED)
            .switchIfEmpty(Mono.error(ApiErrors.badRequest(ErrorCode.ORDER_PAYMENT_INVALID,"Недоступно для текущего заказа")))
            .flatMap(o -> {
                if (!Objects.equals(request.expectedVersion(),o.getTermsVersion()) || request.expectedVersion() == null)
                    return Mono.error(ApiErrors.alreadyExist(ErrorCode.ORDER_TERMS_STALE,"Обновите заказ"));
                if (request.imageId() == null) return invalid();
                return cases.findOpenByOrderId(id).filter(c -> "DISPUTE".equals(c.getKind()))
                    .switchIfEmpty(Mono.error(ApiErrors.badRequest(ErrorCode.ORDER_PAYMENT_INVALID,"Нет открытого спора")))
                    .flatMap(c -> images.activateOrderProof(request.imageId(),id,buyer)
                        .then(cases.addEvidence(c.getId(),request.imageId(),buyer))
                        .flatMap(count -> {
                            if (count != 1) return invalid();
                            o.setImagePaymentProofId(request.imageId()); o.setTermsVersion(o.getTermsVersion()+1);
                            return save(o,buyer,"DISPUTE_PAYMENT_EVIDENCE",request);
                        }));
            });
    }

    /** Explicit human reconciliation: never infer receipt of money from a planned prepayment or a receipt image. */
    @Transactional
    public Mono<Long> reconcile(Long id, Long actor, OrderReconciliationRequest request) {
        return orders.findByIdForUpdate(id).switchIfEmpty(Mono.error(ApiErrors.notFound(ErrorCode.ORDER_NOT_FOUND,"Заказ не найден")))
            .flatMap(o -> {
                if (Boolean.TRUE.equals(o.getPricingKnown()) || request.expectedVersion() == null
                        || !Objects.equals(request.expectedVersion(),o.getTermsVersion()))
                    return Mono.error(ApiErrors.alreadyExist(ErrorCode.ORDER_TERMS_STALE,"Обновите заказ"));
                if (request.reason() == null || request.reason().isBlank()) return invalid();
                validateDelivery(request.carrier(),request.address());
                BigDecimal goods = money(request.goodsAmount());
                BigDecimal paid = money(request.confirmedPaidAmount());
                BigDecimal planned = money(request.plannedPrepaymentAmount());
                BigDecimal delivery = request.deliveryAmount() == null ? null : money(request.deliveryAmount());
                if (delivery != null) money(goods.add(delivery));
                if (o.getProductCurrency() == null || o.getProductCurrency() == com.model_store.model.constant.Currency.CREATE || planned.compareTo(goods) > 0
                        || (delivery != null && paid.compareTo(goods.add(delivery)) > 0)) return invalid();
                o.setGoodsAmount(goods); o.setConfirmedPaidAmount(paid); o.setPlannedPrepaymentAmount(planned);
                o.setDeliveryCarrier(request.carrier()); o.setDeliveryAddress(request.address()); o.setDeliveryAmount(delivery);
                o.setPricingKnown(true); o.setTermsVersion(o.getTermsVersion()+1);
                return cases.findOpenByOrderId(id).hasElement().flatMap(open -> open
                    ? Mono.error(ApiErrors.alreadyExist(ErrorCode.ORDER_CASE_ALREADY_OPEN,"По заказу открыто обращение"))
                    : save(o,actor,"LEGACY_RECONCILED",request));
            });
    }

    public Flux<Map<String,Object>> history(Long id, Long party) {
        return orders.findById(id).filter(o -> Objects.equals(party,o.getSellerId()) || Objects.equals(party,o.getCustomerId()))
            .switchIfEmpty(Mono.error(ApiErrors.forbidden(ErrorCode.ACCESS_DENIED, "Нет доступа к заказу")))
            .flatMapMany(o -> db.sql("SELECT id, actor_id, kind, payload, created_at FROM order_terms_event WHERE order_id=:id ORDER BY id")
                .bind("id",id).fetch().all());
    }

    private static <T> Mono<T> invalid() {
        return Mono.error(ApiErrors.badRequest(ErrorCode.ORDER_PAYMENT_INVALID, "Действие недоступно для текущего этапа или суммы"));
    }
}
