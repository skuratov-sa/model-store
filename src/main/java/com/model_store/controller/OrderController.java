package com.model_store.controller;

import com.model_store.exception.ApiErrors;
import com.model_store.exception.constant.ErrorCode;
import com.model_store.model.dto.CloseOrderRequest;
import com.model_store.model.dto.CreateOrderRequest;
import com.model_store.model.dto.FindOrderResponse;
import com.model_store.model.dto.GetRequiredODataOrderDto;
import com.model_store.model.dto.OrderCaseDetail;
import com.model_store.service.JwtService;
import com.model_store.service.OrderService;
import com.model_store.service.impl.OrderCaseService;
import com.model_store.repository.ParticipantRepository;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/order")
public class OrderController {
    private final OrderService orderService;
    private final JwtService jwtService;
    private final ParticipantRepository participantRepository;
    private final OrderCaseService orderCaseService;

    private Mono<Void> rejectBotSeller(Long participantId) {
        return participantRepository.findByIdAndIsAgentTrue(participantId)
                .hasElement()
                .flatMap(isAgent -> isAgent
                        ? Mono.error(ApiErrors.forbidden(ErrorCode.ACCESS_DENIED, "Заказами бота управляет администратор"))
                        : Mono.empty());
    }

    @Operation(summary = "Получить список заказов для продавца")
    @GetMapping("/seller")
    public Flux<FindOrderResponse> getOrdersBySeller(@RequestHeader("Authorization") String authorizationHeader) {
        Long participantId = jwtService.getIdByAccessToken(authorizationHeader);
        return orderService.getOrdersBySeller(participantId);
    }

    @Operation(summary = "Получить список заказов для покупателя")
    @GetMapping("/customer")
    public Flux<FindOrderResponse> getOrdersByCustomer(@RequestHeader("Authorization") String authorizationHeader) {
        Long participantId = jwtService.getIdByAccessToken(authorizationHeader);
        return orderService.getOrdersByCustomer(participantId);
    }

    @Operation(summary = "Получить данные для создания заказа")
    @GetMapping
    public Mono<GetRequiredODataOrderDto> getRequiredDataForCreateOrder(@RequestHeader("Authorization") String authorizationHeader, Long productId) {
        Long participantId = jwtService.getIdByAccessToken(authorizationHeader);
        return orderService.getRequiredDataForCreateOrder(participantId, productId);
    }

    @Operation(summary = "1.Создать новый заказ")
    @PostMapping("/BOOKED")
    public Mono<List<Long>> createOrder(@RequestHeader("Authorization") String authorizationHeader, @RequestBody List<CreateOrderRequest> requests) {
        Long participantId = jwtService.getIdByAccessToken(authorizationHeader);
        return orderService.createOrders(requests, participantId);
    }

    @Operation(summary = "2.Продавец подтверждает заказ/предзаказ")
    @PostMapping("/{orderId}/AWAITING_PREPAYMENT")
    public Mono<Long> confirmOrder(@RequestHeader("Authorization") String authorizationHeader,
                                   @PathVariable Long orderId, @RequestParam(required = false) String comment) {
        Long participantId = jwtService.getIdByAccessToken(authorizationHeader);
        return rejectBotSeller(participantId).then(orderService.agreementOrder(orderId, comment, participantId));
    }

    @Operation(summary = "3.1.Покупатель подтверждает предоплату")
    @PostMapping("/{orderId}/AWAITING_PREPAYMENT_APPROVAL")
    public Mono<Long> prepaymentOrder(@RequestHeader("Authorization") String authorizationHeader,
                                      @PathVariable Long orderId, @RequestParam(required = false) Long imageId, @RequestParam(required = false) String comment) {
        Long participantId = jwtService.getIdByAccessToken(authorizationHeader);
        return orderService.prepaymentOrder(orderId, imageId, comment, participantId);
    }

    @Operation(summary = "3.2.Продавец подтверждает предзаказ")
    @PostMapping("/{orderId}/AWAITING_PAYMENT")
    public Mono<Long> sellerConfirmsPreorder(@RequestHeader("Authorization") String authorizationHeader,
                                             @PathVariable Long orderId, @RequestParam(required = false) String comment) {
        Long participantId = jwtService.getIdByAccessToken(authorizationHeader);
        return rejectBotSeller(participantId).then(orderService.sellerConfirmsPreorder(orderId, comment, participantId));
    }

    @Operation(summary = "3.3.Покупатель подтверждает оплату")
    @PostMapping("/{orderId}/ASSEMBLING")
    public Mono<Long> confirmPayment(@RequestHeader("Authorization") String authorizationHeader,
                                     @PathVariable Long orderId, @RequestParam(required = false) Long imageId, @RequestParam(required = false) String comment) {
        Long participantId = jwtService.getIdByAccessToken(authorizationHeader);
        return orderService.paymentOrder(orderId, imageId, comment, participantId);
    }

    @Operation(summary = "4.Продавец отправляет товар")
    @PostMapping("/{orderId}/ON_THE_WAY")
    public Mono<Long> shipOrder(@RequestHeader("Authorization") String authorizationHeader,
                                @PathVariable Long orderId, @RequestParam String deliveryUrl, @RequestParam(required = false) String comment) {
        Long participantId = jwtService.getIdByAccessToken(authorizationHeader);
        return rejectBotSeller(participantId).then(orderService.transferOrder(orderId, deliveryUrl, comment, participantId));
    }

    @Operation(summary = "5.Покупатель подтверждает получение")
    @PostMapping("/{orderId}/COMPLETED")
    public Mono<Long> deliverOrder(@RequestHeader("Authorization") String authorizationHeader,
                                   @PathVariable Long orderId, @RequestParam(required = false) String comment) {
        Long participantId = jwtService.getIdByAccessToken(authorizationHeader);
        return orderService.deliveredOrder(orderId, comment, participantId);
    }

    @Operation(summary = "Открыть спор по заказу после заявленной оплаты")
    @PostMapping("/orders/{orderId}/DISPUTED")
    public Mono<Long> openDisputeForOrder(@RequestHeader("Authorization") String authorizationHeader,
                                          @PathVariable Long orderId, @RequestParam(required = false) List<Long> imageIds,
                                          @RequestParam String comment) {
        Long participantId = jwtService.getIdByAccessToken(authorizationHeader);
        return orderCaseService.openDispute(orderId, participantId, comment, imageIds);
    }

    @Operation(summary = "Попросить администратора отменить неоплаченный заказ")
    @PostMapping("/{orderId}/cancellation-request")
    public Mono<Long> requestCancellation(@RequestHeader("Authorization") String authorizationHeader,
                                          @PathVariable Long orderId, @RequestParam String comment) {
        Long participantId = jwtService.getIdByAccessToken(authorizationHeader);
        return orderCaseService.requestCancellation(orderId, participantId, comment);
    }

    @Operation(summary = "Сообщить об оплате после отмены заказа")
    @PostMapping("/{orderId}/payment-appeal")
    public Mono<Long> reportPaymentAfterCancellation(@RequestHeader("Authorization") String authorizationHeader,
                                                      @PathVariable Long orderId, @RequestParam String comment,
                                                      @RequestParam(required = false) List<Long> imageIds) {
        Long participantId = jwtService.getIdByAccessToken(authorizationHeader);
        return orderCaseService.reportPaymentAfterCancellation(orderId, participantId, comment, imageIds);
    }

    @Operation(summary = "Обращения по заказу")
    @GetMapping("/{orderId}/cases")
    public Flux<OrderCaseDetail> getOrderCases(@RequestHeader("Authorization") String authorizationHeader,
                                               @PathVariable Long orderId) {
        Long participantId = jwtService.getIdByAccessToken(authorizationHeader);
        return orderCaseService.orderCases(orderId, participantId);
    }

    @Operation(summary = "Отменить заказ")
    @PostMapping("/{orderId}/CANCELLED")
    public Mono<Long> closeOrder(@RequestHeader("Authorization") String authorizationHeader,
                                 @PathVariable Long orderId, @RequestBody CloseOrderRequest request) {
        Long participantId = jwtService.getIdByAccessToken(authorizationHeader);
        if (request.getOrderId() != null && !orderId.equals(request.getOrderId())) {
            return Mono.error(ApiErrors.badRequest(ErrorCode.INVALID_REQUEST, "ID заказа в пути и теле запроса не совпадают"));
        }
        request.setOrderId(orderId);
        return rejectBotSeller(participantId).then(orderService.closureOrder(request, participantId));
    }
}
