package com.model_store.service;

import com.model_store.model.base.Order;
import com.model_store.model.dto.CreateOrderRequest;
import com.model_store.model.dto.FindOrderResponse;
import com.model_store.model.dto.GetRequiredODataOrderDto;
import com.model_store.model.dto.UpdateOrderRequest;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

public interface OrderService {
    Mono<Long> createOrder(CreateOrderRequest request, Long participantId);

    Mono<Long> updateStatusOrder(UpdateOrderRequest request);

    Flux<FindOrderResponse> getOrdersBySeller(Long sellerId);

    Flux<FindOrderResponse> getOrdersByCustomer(Long customerId);

    Mono<Long> agreementOrder(Long orderId, Long accountId, String comment);

    Mono<Long> paymentOrder(Long orderId, Long imageId, String comment);

    Mono<Long> transferOrder(Long orderId, String urlTransfer, String comment);

    Mono<Long> deliveredOrder(Long orderId, String comment);

    Mono<Long> openDisputeForOrder(Long orderId, List<Long> imageIds, String comment);

    Mono<Long> closeDisputeForOrder(Long orderId, List<Long> imageIds, String comment);


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
