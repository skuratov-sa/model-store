package com.model_store.service;

import com.model_store.model.base.Order;
import com.model_store.model.constant.OrderStatus;
import com.model_store.model.dto.CloseOrderRequest;
import com.model_store.model.dto.CreateOrderRequest;
import com.model_store.model.dto.FindOrderResponse;
import com.model_store.model.dto.GetRequiredODataOrderDto;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

public interface OrderService {
    Mono<List<Long>> createOrders(List<CreateOrderRequest> request, Long participantId);

    /**
     * Предоплата товара
     */
    Mono<Long> prepaymentOrder(Long orderId, Long imageId, String comment, Long participantId);

    /**
     * Продавец подтверждает предзаказ
     */
    Mono<Long> sellerConfirmsPreorder(Long orderId, String comment, Long participantId);

    Mono<Long> closureOrder(Long orderId, CloseOrderRequest request, Long participantId);


    Flux<FindOrderResponse> getOrdersBySeller(Long sellerId);

    Flux<FindOrderResponse> getAgentOrders(Long agentId, OrderStatus status, int limit, long offset);

    Flux<FindOrderResponse> getOrdersByCustomer(Long customerId);

    Mono<Long> agreementOrder(Long orderId, String comment, Long participantId);

    Mono<Long> paymentOrder(Long orderId, Long imageId, String comment, Long participantId);

    Mono<Long> transferOrder(Long orderId, String urlTransfer, String comment, Long participantId);

    Mono<Long> deliveredOrder(Long orderId, String comment, Long participantId);

    /**
     * Количество сделаных покупок
     *
     * @param sellerId -  id продавца
     */
    Mono<Integer> findCompletedCountBySellerId(Long sellerId);


    Mono<Order> findById(Long orderId);

    /**
     * Получить количество купленных товаров
     *
     * @param customerId - id покупателя
     */
    Mono<Integer> findCompletedCountByCustomerId(Long customerId);

    Mono<GetRequiredODataOrderDto> getRequiredDataForCreateOrder(Long participantId, Long productId);

}
