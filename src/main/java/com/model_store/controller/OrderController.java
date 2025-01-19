package com.model_store.controller;

import com.model_store.model.dto.CreateOrderRequest;
import com.model_store.model.dto.FindOrderResponse;
import com.model_store.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

    @Operation(summary = "Создать новый заказ")
    @PostMapping()
    public Mono<Long> createOrder(@RequestBody CreateOrderRequest request) {
        return orderService.createOrder(request);
    }

    @Operation(summary = "Продавец подтверждает заказ")
    @PostMapping("/{orderId}/confirm")
    public Mono<Long> confirmOrder(@PathVariable Long orderId, @RequestParam Long accountId, @RequestParam(required = false) String comment) {
        return orderService.agreementOrder(orderId, accountId, comment);
    }

    @Operation(summary = "Покупатель подтверждает оплату")
    @PostMapping("/{orderId}/payment")
    public Mono<Long> confirmPayment(@PathVariable Long orderId, @RequestParam(required = false) Long imageId, @RequestParam(required = false) String comment) {
        return orderService.paymentOrder(orderId, imageId, comment);
    }

    @Operation(summary = "Продавец отправляет товар")
    @PostMapping("/{orderId}/ship")
    public Mono<Long> shipOrder(@PathVariable Long orderId, @RequestParam String deliveryUrl, @RequestParam(required = false) String comment) {
        return orderService.transferOrder(orderId, deliveryUrl, comment);
    }

    @Operation(summary = "Покупатель подтверждает получение")
    @PostMapping("/{orderId}/deliver")
    public Mono<Long> deliverOrder(@PathVariable Long orderId, @RequestParam(required = false) String comment) {
        return orderService.deliveredOrder(orderId, comment);
    }

    @Operation(summary = "Создание спора по заказу")
    @PostMapping("/orders/{orderId}/dispute")
    public Mono<Long> openDisputeForOrder(@PathVariable Long orderId, @RequestParam List<Long> imageIds, @RequestParam(required = false) String comment) {
        return orderService.openDisputeForOrder(orderId, imageIds, comment);
    }

    @Operation(summary = "Закрытие спора по заказу")
    @PostMapping("/orders/{orderId}/dispute/close")
    public Mono<Long> closeDisputeForOrder(@PathVariable Long orderId, @RequestParam(required = false) List<Long> imageIds, @RequestParam(required = false) String comment) {
        return orderService.closeDisputeForOrder(orderId, imageIds, comment);
    }

    @Operation(summary = "Получить список заказов для продавца")
    @GetMapping("/seller/{sellerId}")
    public Flux<FindOrderResponse> getOrdersBySeller(@PathVariable Long sellerId) {
        return orderService.getOrdersBySeller(sellerId);
    }

    @Operation(summary = "Получить список заказов для покупателя")
    @GetMapping("/customer/{customerId}")
    public Flux<FindOrderResponse> getOrdersByCustomer(@PathVariable Long customerId) {
        return orderService.getOrdersByCustomer(customerId);
    }

}
