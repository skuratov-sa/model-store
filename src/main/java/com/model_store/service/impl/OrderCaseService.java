package com.model_store.service.impl;

import com.model_store.model.base.Order;
import com.model_store.model.base.OrderCase;
import com.model_store.model.constant.ImageTag;
import com.model_store.model.constant.OrderStatus;
import com.model_store.model.constant.ProductAvailabilityType;
import com.model_store.model.constant.ParticipantRole;
import com.model_store.model.dto.OrderCaseDetail;
import com.model_store.repository.ImageRepository;
import com.model_store.repository.OrderCaseRepository;
import com.model_store.repository.OrderRepository;
import com.model_store.repository.ParticipantRepository;
import com.model_store.service.ImageService;
import com.model_store.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class OrderCaseService {
    private static final Set<OrderStatus> PAID = Set.of(OrderStatus.AWAITING_PREPAYMENT_APPROVAL,
            OrderStatus.AWAITING_PAYMENT, OrderStatus.ASSEMBLING, OrderStatus.ON_THE_WAY);
    private static final Set<OrderStatus> UNPAID = Set.of(OrderStatus.AWAITING_PREPAYMENT,
            OrderStatus.AWAITING_PAYMENT);

    private final OrderRepository orders;
    private final OrderCaseRepository cases;
    private final ImageRepository images;
    private final ImageService imageService;
    private final ProductService productService;
    private final ParticipantRepository participants;
    private final DatabaseClient db;

    @Transactional
    public Mono<Long> openDispute(Long orderId, Long participantId, String comment, List<Long> imageIds) {
        requireComment(comment);
        return requireParty(orderId, participantId)
                .filter(order -> PAID.contains(order.getStatus()) && order.getImagePaymentProofId() != null)
                .switchIfEmpty(invalid())
                .flatMap(order -> transition(order, OrderStatus.DISPUTED, comment)
                        .thenReturn(order)
                        .onErrorResume(IllegalArgumentException.class, error -> requireParty(orderId, participantId)
                                .filter(current -> PAID.contains(current.getStatus())
                                        && current.getImagePaymentProofId() != null)
                                .switchIfEmpty(Mono.error(error))
                                .flatMap(current -> transition(current, OrderStatus.DISPUTED, comment)
                                        .thenReturn(current)))
                        .flatMap(previous -> createCase(previous, "DISPUTE", participantId, comment, imageIds)));
    }

    @Transactional
    public Mono<Long> requestCancellation(Long orderId, Long participantId, String comment) {
        requireComment(comment);
        return orders.findByIdForUpdate(orderId)
                .filter(order -> order.getSellerId().equals(participantId) || order.getCustomerId().equals(participantId))
                .filter(order -> order.getImagePaymentProofId() == null && UNPAID.contains(order.getStatus())
                        && (order.getPrepaymentAmount() == null || order.getPrepaymentAmount() == 0
                            || order.getStatus() == OrderStatus.AWAITING_PREPAYMENT))
                .switchIfEmpty(invalid())
                .flatMap(order -> cases.findOpenByOrderId(orderId)
                        .flatMap(existing -> OrderCaseService.<Long>conflict("По заказу уже открыто обращение"))
                        .switchIfEmpty(createCase(order, "CANCELLATION_REQUEST", participantId, comment, List.of())));
    }

    @Transactional
    public Mono<Long> reportPaymentAfterCancellation(Long orderId, Long buyerId,
                                                     String comment, List<Long> imageIds) {
        requireComment(comment);
        return orders.findByIdForUpdate(orderId)
                .filter(order -> order.getCustomerId().equals(buyerId) && order.getStatus() == OrderStatus.CANCELLED)
                .switchIfEmpty(invalid())
                .flatMap(order -> cases.findByOrderId(orderId)
                        .filter(c -> "PAYMENT_APPEAL".equals(c.getKind()))
                        .next()
                        .flatMap(existing -> OrderCaseService.<Long>conflict("Обращение по оплате уже создано"))
                        .switchIfEmpty(cases.findOpenByOrderId(orderId)
                                .flatMap(existing -> OrderCaseService.<Long>conflict("По заказу уже открыто обращение"))
                                .switchIfEmpty(createCase(order, "PAYMENT_APPEAL", buyerId, comment, imageIds))));
    }

    public Flux<OrderCaseDetail> list(String state, int limit, long offset) {
        if (!Set.of("OPEN", "RESOLVED").contains(state) || limit < 1 || limit > 100 || offset < 0)
            return Flux.error(new IllegalArgumentException("Некорректные параметры списка обращений"));
        return cases.findQueue(state, limit, offset).concatMap(this::detail);
    }

    public Mono<OrderCaseDetail> adminDetail(Long caseId) {
        return cases.findById(caseId).switchIfEmpty(invalid()).flatMap(this::detail);
    }

    public Flux<OrderCaseDetail> orderCases(Long orderId, Long participantId) {
        return requireParty(orderId, participantId)
                .flatMapMany(order -> cases.findByOrderId(orderId).concatMap(this::detail));
    }

    @Transactional
    public Mono<Long> changeTelegramUrl(Long caseId, Long adminId, String url) {
        validateTelegramUrl(url);
        return requireAdmin(adminId).then(cases.findById(caseId))
                .filter(c -> "OPEN".equals(c.getState()))
                .switchIfEmpty(invalid())
                .flatMap(c -> cases.updateTelegramUrl(caseId, c.getVersion(), url)
                        .flatMap(updated -> updated == 1 ? Mono.just(caseId) : conflict("Обращение изменилось. Повторите операцию")));
    }

    @Transactional
    public Mono<Long> resolve(Long caseId, Long adminId, String outcome, String comment) {
        requireComment(comment);
        return requireAdmin(adminId).then(cases.findById(caseId))
                .filter(c -> "OPEN".equals(c.getState()))
                .switchIfEmpty(invalid())
                .flatMap(c -> orders.findByIdForUpdate(c.getOrderId()).switchIfEmpty(invalid())
                        .flatMap(order -> resolutionTransition(c, order, outcome, comment)
                                .then(cases.resolve(caseId, c.getVersion(), adminId, outcome, comment))
                                .flatMap(updated -> updated == 1 ? Mono.just(caseId)
                                        : conflict("Обращение изменилось. Повторите операцию"))));
    }

    private Mono<Void> resolutionTransition(OrderCase c, Order order, String outcome, String comment) {
        if ("DISPUTE".equals(c.getKind())) {
            if ("SELLER".equals(outcome) && c.getPreviousOrderStatus() != OrderStatus.ON_THE_WAY)
                return invalid();
            OrderStatus target = switch (outcome) {
                case "BUYER" -> OrderStatus.FAILED;
                case "SELLER" -> OrderStatus.COMPLETED;
                default -> throw new IllegalArgumentException("Неверный исход спора");
            };
            return transition(order, OrderStatus.DISPUTED, target, comment);
        }
        if ("PAYMENT_APPEAL".equals(c.getKind())) {
            if (!Set.of("BUYER", "SELLER").contains(outcome) || order.getStatus() != OrderStatus.CANCELLED)
                return invalid();
            return Mono.empty();
        }
        if ("CANCELLATION_REQUEST".equals(c.getKind())) {
            if ("REJECTED".equals(outcome)) return Mono.empty();
            if (!"CANCELLED".equals(outcome) || order.getStatus() != c.getPreviousOrderStatus())
                return invalid();
            return transition(order, OrderStatus.CANCELLED, comment)
                    .then(productService.findByIdForUpdate(order.getProductId())
                            .filter(product -> product.getAvailability() == ProductAvailabilityType.PURCHASABLE
                                    && product.getCount() != null)
                            .flatMap(product -> productService.incrementCountIfLimited(product.getId(), order.getCount()))
                            .then());
        }
        return invalid();
    }

    private Mono<Void> transition(Order order, OrderStatus target, String comment) {
        return transition(order, order.getStatus(), target, comment);
    }

    private Mono<Void> transition(Order order, OrderStatus expected, OrderStatus target, String comment) {
        return orders.transition(order.getId(), expected.name(), target.name(), comment)
                .flatMap(updated -> updated == 1 ? Mono.<Void>empty() : invalid());
    }

    private Mono<Long> createCase(Order order, String kind, Long participantId,
                                  String comment, List<Long> imageIds) {
        OrderCase c = new OrderCase();
        c.setOrderId(order.getId());
        c.setKind(kind);
        c.setState("OPEN");
        c.setOpenedBy(participantId);
        c.setOpeningComment(comment.trim());
        c.setPreviousOrderStatus(order.getStatus());
        c.setCreatedAt(Instant.now());
        return cases.save(c).flatMap(saved -> attachEvidence(saved.getId(), order.getId(), participantId, imageIds)
                .thenReturn(saved.getId()));
    }

    private Mono<Void> attachEvidence(Long caseId, Long orderId, Long participantId, List<Long> imageIds) {
        if (imageIds == null || imageIds.isEmpty()) return Mono.empty();
        List<Long> unique = imageIds.stream().distinct().toList();
        if (unique.size() != imageIds.size()) return invalid();
        return images.activateCaseEvidence(unique.toArray(Long[]::new), orderId, participantId)
                .flatMap(updated -> updated == unique.size() ? Flux.fromIterable(unique)
                        .concatMap(id -> db.sql("INSERT INTO order_case_image(case_id, image_id, uploaded_by) VALUES (:caseId, :imageId, :participantId)")
                                .bind("caseId", caseId).bind("imageId", id).bind("participantId", participantId)
                                .fetch().rowsUpdated())
                        .then() : invalid());
    }

    private Mono<OrderCaseDetail> detail(OrderCase c) {
        return orders.findById(c.getOrderId()).switchIfEmpty(invalid())
                .flatMap(order -> Mono.zip(
                        imageService.findActualImages(order.getId(), ImageTag.ORDER).collectList(),
                        db.sql("SELECT image_id FROM order_case_image WHERE case_id = :caseId ORDER BY image_id")
                                .bind("caseId", c.getId()).map((row, metadata) -> row.get("image_id", Long.class))
                                .all().collectList())
                        .map(t -> new OrderCaseDetail(c, order.getStatus(), order.getCustomerId(), order.getSellerId(),
                                order.getProductId(), order.getCount(), order.getTotalPrice(), order.getPrepaymentAmount(),
                                order.getImagePaymentProofId(), t.getT1(), t.getT2())));
    }

    private Mono<Order> requireParty(Long orderId, Long participantId) {
        return orders.findById(orderId)
                .filter(order -> order.getSellerId().equals(participantId) || order.getCustomerId().equals(participantId))
                .switchIfEmpty(invalid());
    }

    private Mono<Void> requireAdmin(Long participantId) {
        return participants.findById(participantId)
                .filter(p -> p.getRole() == ParticipantRole.ADMIN)
                .switchIfEmpty(invalid())
                .then();
    }

    private static void requireComment(String comment) {
        if (comment == null || comment.isBlank()) throw new IllegalArgumentException("Укажите причину обращения");
    }

    private static void validateTelegramUrl(String url) {
        try {
            URI uri = URI.create(url);
            if (url.length() > 2048 || !"https".equalsIgnoreCase(uri.getScheme())
                    || !Set.of("t.me", "telegram.me").contains(uri.getHost()) || uri.getUserInfo() != null
                    || uri.getPath() == null || uri.getPath().isBlank() || "/".equals(uri.getPath()))
                throw new IllegalArgumentException("Укажите ссылку https://t.me/...");
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Укажите ссылку https://t.me/...");
        }
    }

    private static <T> Mono<T> invalid() {
        return Mono.error(new IllegalArgumentException("Операция недоступна для текущего состояния заказа или обращения"));
    }

    private static <T> Mono<T> conflict(String message) {
        return Mono.error(new ResponseStatusException(HttpStatus.CONFLICT, message));
    }
}
