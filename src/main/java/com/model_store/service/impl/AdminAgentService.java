package com.model_store.service.impl;

import com.model_store.exception.ApiErrors;
import com.model_store.exception.constant.ErrorCode;
import com.model_store.model.CreateOrUpdateProductRequest;
import com.model_store.model.base.AdminAgentOrderAction;
import com.model_store.model.base.Participant;
import com.model_store.model.base.Product;
import com.model_store.model.constant.ImageTag;
import com.model_store.model.constant.OrderStatus;
import com.model_store.model.dto.AgentSummaryDto;
import com.model_store.model.dto.AgentProfileDto;
import com.model_store.model.dto.CloseOrderRequest;
import com.model_store.model.dto.FindOrderResponse;
import com.model_store.model.dto.UpdateAgentProfileRequest;
import com.model_store.repository.AdminAgentOrderActionRepository;
import com.model_store.repository.OrderRepository;
import com.model_store.repository.ParticipantRepository;
import com.model_store.repository.ProductRepository;
import com.model_store.service.ImageService;
import com.model_store.service.OrderService;
import com.model_store.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class AdminAgentService {
    private final ParticipantRepository participants;
    private final ProductRepository products;
    private final OrderRepository orders;
    private final AdminAgentOrderActionRepository audit;
    private final ProductService productService;
    private final OrderService orderService;
    private final ImageService imageService;

    public Mono<Participant> requireAgent(Long agentId) {
        return participants.findByIdAndIsAgentTrue(agentId)
                .switchIfEmpty(Mono.error(ApiErrors.notFound(ErrorCode.PARTICIPANT_NOT_FOUND, "Бот не найден")));
    }

    public Flux<AgentSummaryDto> agents() {
        return participants.findByIsAgentTrueOrderByIdAsc()
                .map(p -> new AgentSummaryDto(p.getId(), p.getLogin(), p.getStatus()));
    }

    public Flux<FindOrderResponse> orders(Long agentId, OrderStatus status, int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            return Flux.error(ApiErrors.badRequest(ErrorCode.INVALID_REQUEST, "Некорректная страница или размер страницы"));
        }
        Flux<FindOrderResponse> result = orderService.getAgentOrders(agentId, status, size, (long) page * size);
        return agentId == null ? result : requireAgent(agentId).thenMany(result);
    }

    public Flux<Product> products(Long agentId) {
        return requireAgent(agentId).thenMany(products.findByParticipantId(agentId));
    }

    public Mono<Product> product(Long productId) {
        return products.findById(productId)
                .switchIfEmpty(Mono.error(ApiErrors.notFound(ErrorCode.PRODUCT_NOT_FOUND, "Товар не найден")));
    }

    public Mono<Void> updateProduct(Long agentId, Long productId, CreateOrUpdateProductRequest request) {
        return requireAgent(agentId)
                .then(Mono.defer(() -> productService.updateAgentProduct(productId, request, agentId)));
    }

    public Mono<AgentProfileDto> profile(Long agentId) {
        return requireAgent(agentId).map(AgentProfileDto::from);
    }

    @Transactional
    public Mono<AgentProfileDto> updateProfile(Long agentId, UpdateAgentProfileRequest request) {
        return requireAgent(agentId).flatMap(p -> {
            if (request.fullName() != null) p.setFullName(request.fullName());
            if (request.phoneNumber() != null) p.setPhoneNumber(request.phoneNumber());
            if (request.deadlineSending() != null) p.setDeadlineSending(request.deadlineSending());
            if (request.deadlinePayment() != null) p.setDeadlinePayment(request.deadlinePayment());
            Mono<Void> image = request.imageId() == null
                    ? Mono.empty()
                    : imageService.replaceForParticipant(request.imageId(), agentId, ImageTag.PARTICIPANT);
            return image.then(participants.save(p)).map(AgentProfileDto::from);
        });
    }

    @Transactional
    public Mono<Long> actOnOrder(Long adminId, Long agentId, Long orderId, String action,
                                 String comment, String deliveryUrl) {
        return requireAgent(agentId)
                .then(orders.findById(orderId))
                .filter(order -> Objects.equals(order.getSellerId(), agentId))
                .switchIfEmpty(Mono.error(ApiErrors.notFound(ErrorCode.INVALID_REQUEST, "Заказ бота не найден")))
                .flatMap(order -> {
                    Mono<Long> transition = switch (action) {
                        case "CONFIRM" -> orderService.agreementOrder(orderId, comment, agentId);
                        case "CONFIRM_PREPAYMENT" -> orderService.sellerConfirmsPreorder(orderId, comment, agentId);
                        case "SHIP" -> orderService.transferOrder(orderId, deliveryUrl, comment, agentId);
                        case "CANCEL" -> {
                            CloseOrderRequest close = new CloseOrderRequest();
                            close.setOrderId(orderId);
                            close.setComment(comment);
                            yield orderService.closureOrder(close, agentId);
                        }
                        default -> Mono.error(ApiErrors.badRequest(ErrorCode.INVALID_REQUEST, "Неизвестное действие с заказом"));
                    };
                    return transition.flatMap(id -> {
                        AdminAgentOrderAction record = new AdminAgentOrderAction();
                        record.setAdminId(adminId);
                        record.setAgentId(agentId);
                        record.setOrderId(orderId);
                        record.setAction(action);
                        record.setCreatedAt(Instant.now());
                        return audit.save(record).thenReturn(id);
                    });
                });
    }
}
