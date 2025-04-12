package com.model_store.service.impl;

import com.amazonaws.services.kms.model.NotFoundException;
import com.model_store.mapper.OrderMapper;
import com.model_store.model.base.Address;
import com.model_store.model.base.Order;
import com.model_store.model.base.Product;
import com.model_store.model.base.Transfer;
import com.model_store.model.constant.ImageStatus;
import com.model_store.model.constant.ImageTag;
import com.model_store.model.constant.OrderStatus;
import com.model_store.model.dto.CreateOrderRequest;
import com.model_store.model.dto.FindOrderResponse;
import com.model_store.model.dto.GetRequiredODataOrderDto;
import com.model_store.model.dto.UpdateOrderRequest;
import com.model_store.repository.OrderRepository;
import com.model_store.service.AddressService;
import com.model_store.service.ImageService;
import com.model_store.service.OrderService;
import com.model_store.service.OrderStatusHistoryService;
import com.model_store.service.ParticipantService;
import com.model_store.service.ProductService;
import com.model_store.service.TransferService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

import static com.model_store.service.util.UtilService.getImageId;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static java.util.Objects.isNull;

@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {
    private final OrderRepository orderRepository;
    private final ProductService productService;
    private final ParticipantService participantService;
    private final OrderStatusHistoryService orderHistoryService;
    private final TransferService transferService;
    private final AddressService addressService;
    private final OrderMapper orderMapper;
    private final ImageService imageService;

    @Override
    public Mono<GetRequiredODataOrderDto> getRequiredDataForCreateOrder(Long participantId, Long productId) {
        Mono<Product> productMono = productService.findById(productId);
        Mono<List<Address>> addressMono = productMono.flatMapMany(e -> addressService.findByParticipantId(e.getParticipantId())).collectList().defaultIfEmpty(emptyList());
        Mono<List<Transfer>> transferMono = productMono.flatMapMany(e -> transferService.findByParticipantId(e.getParticipantId())).collectList().defaultIfEmpty(emptyList());

        return Mono.zip(addressMono, transferMono)
                .map(tuple -> GetRequiredODataOrderDto.builder()
                        .addresses(tuple.getT1())
                        .sellerTransfers(tuple.getT2())
                        .build()
                );
    }

    @Override
    @Transactional
    public Mono<Long> createOrder(CreateOrderRequest request, Long participantId) {
        return productService.findById(request.getProductId())
                .filter(product -> product.getCount() - request.getCount() >= 0)
                .switchIfEmpty(Mono.error(new RuntimeException("Данный товар закончился")))
                .flatMap(product -> createOrderAndUpdateProduct(product, request, participantId));
    }

    @Override
    public Mono<Long> updateStatusOrder(UpdateOrderRequest request) {
        return orderRepository.findById(request.getOrderId())
                .doOnNext(order -> order.setStatus(request.getOrderStatus()))
                .flatMap(order -> orderRepository.save(order).map(Order::getId));
    }

    @Override
    public Flux<FindOrderResponse> getOrdersBySeller(Long sellerId) {
        return updateFindOrderByOrderSeller(sellerId)
                .flatMap(this::updateImages)
                .flatMap(this::updateFindOrderByHistory)
                .flatMap(this::updateFindOrderByUser)
                .flatMap(this::updateFindOrderByProduct)
                .flatMap(this::updateFindOrderByTransfer);
    }

    @Override
    public Flux<FindOrderResponse> getOrdersByCustomer(Long customerId) {
        return updateFindOrderByOrderCustomer(customerId)
                .flatMap(this::updateImages)
                .flatMap(this::updateFindOrderByHistory)
                .flatMap(this::updateFindOrderByUser)
                .flatMap(this::updateFindOrderByProduct)
                .flatMap(this::updateFindOrderByTransfer);
    }


    @Override
    public Mono<Long> agreementOrder(Long orderId, Long accountId, String comment) {
        return orderRepository.findById(orderId)
                .filter(order -> order.getStatus().equals(OrderStatus.BOOKED))
                .switchIfEmpty(Mono.error(new NotFoundException("Order not found: " + orderId))) // Если заказ не найден
                .doOnNext(order -> order.setAccountId(accountId))
                .doOnNext(order -> order.setComment(comment))
                .doOnNext(order -> order.setStatus(OrderStatus.AWAITING_PAYMENT))
                .flatMap(order -> orderRepository.save(order).map(Order::getId));
    }

    @Override
    public Mono<Long> paymentOrder(Long orderId, Long imageId, String comment) {
        return orderRepository.findById(orderId)
                .filter(order -> order.getStatus().equals(OrderStatus.AWAITING_PAYMENT))
                .switchIfEmpty(Mono.error(new NotFoundException("Order not found: " + orderId))) // Если заказ не найден
                .flatMap(order ->
                        imageService.updateImagesStatus(List.of(imageId), order.getId(), ImageStatus.ACTIVE, ImageTag.ORDER)
                                .thenReturn(order)
                ).flatMap(order -> {
                    order.setStatus(OrderStatus.ASSEMBLING);
                    order.setComment(comment);
                    order.setImagePaymentProofId(imageId);
                    return orderRepository.save(order)
                            .then(Mono.just(order.getId()));
                });
    }

    @Override
    public Mono<Long> transferOrder(Long orderId, String deliveryUrl, String comment) {
        return orderRepository.findById(orderId)
                .filter(order -> order.getStatus().equals(OrderStatus.ASSEMBLING))
                .switchIfEmpty(Mono.error(new NotFoundException("Order not found: " + orderId))) // Если заказ не найден
                .doOnNext(order -> order.setDeliveryUrl(deliveryUrl))
                .doOnNext(order -> order.setComment(comment))
                .doOnNext(order -> order.setStatus(OrderStatus.ON_THE_WAY))
                .flatMap(order -> orderRepository.save(order).map(Order::getId));
    }

    @Override
    public Mono<Long> deliveredOrder(Long orderId, String comment) {
        return orderRepository.findById(orderId)
                .filter(order -> order.getStatus().equals(OrderStatus.ON_THE_WAY))
                .switchIfEmpty(Mono.error(new NotFoundException("Order not found: " + orderId))) // Если заказ не найден
                .doOnNext(order -> order.setComment(comment))
                .doOnNext(order -> order.setStatus(OrderStatus.RECEIVED))
                .flatMap(order -> orderRepository.save(order).map(Order::getId));
    }

    @Override
    public Mono<Long> openDisputeForOrder(Long orderId, List<Long> imageIds, String comment) {
        return orderRepository.findById(orderId)
                .filter(order -> order.getStatus().equals(OrderStatus.ON_THE_WAY))
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Order not found: " + orderId))) // Если заказ не найден
                .flatMap(order ->
                        imageService.updateImagesStatus(imageIds, order.getId(), ImageStatus.ACTIVE, ImageTag.ORDER)
                                .thenReturn(order)
                ).flatMap(order -> {
                    order.setComment(comment);
                    order.setStatus(OrderStatus.DISPUTED);
                    return orderRepository.save(order)
                            .then(Mono.just(order.getId()));
                });
    }

    @Override
    public Mono<Long> closeDisputeForOrder(Long orderId, List<Long> imageIds, String comment) {
        return orderRepository.findById(orderId)
                .filter(order -> order.getStatus().equals(OrderStatus.DISPUTED))
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Order not found: " + orderId)))
                .flatMap(order ->
                        imageService.updateImagesStatus(imageIds, order.getId(), ImageStatus.ACTIVE, ImageTag.ORDER)
                                .thenReturn(order)
                )
                .flatMap(order -> {
                    order.setStatus(OrderStatus.COMPLETED);
                    order.setComment(comment);
                    return orderRepository.save(order)
                            .then(Mono.just(order.getId()));
                });
    }

    @Override
    public Mono<Integer> findCompletedCountBySellerId(Long sellerId) {
        return orderRepository.findCompletedCountBySellerId(sellerId);
    }

    @Override
    public Mono<Integer> findCompletedCountByCustomerId(Long customerId) {
        return orderRepository.findCompletedCountByCustomerId(customerId);
    }

    @Transactional
    protected Mono<Long> createOrderAndUpdateProduct(Product product, CreateOrderRequest request, Long participantId) {
        Order order = orderMapper.toOrder(request);
        order.setStatus(OrderStatus.BOOKED);
        order.setSellerId(participantId);
        order.setCustomerId(product.getParticipantId());
        order.setTotalPrice(product.getPrice() * request.getCount());
        product.setCount(product.getCount() - request.getCount());
        return productService.save(product)
                .then(orderRepository.save(order).map(Order::getId));
    }

    private Flux<FindOrderResponse> updateFindOrderByOrderSeller(Long sellerId) {
        return orderRepository.findBySellerId(sellerId)
                .map(orderMapper::toFindOrderResponseBySeller);
    }

    private Flux<FindOrderResponse> updateFindOrderByOrderCustomer(Long customerId) {
        return orderRepository.findByCustomerId(customerId)
                .map(orderMapper::toFindOrderResponseByCustomer);
    }

    private Mono<FindOrderResponse> updateFindOrderByUser(FindOrderResponse response) {
        if (isNull(response.getUserInfo().getId())) {
            return Mono.error(new RuntimeException("Пользователь не найден"));
        }

        return participantService.findShortInfo(response.getUserInfo().getId())
                .doOnNext(response::setUserInfo)
                .then(Mono.just(response));
    }

    private Mono<FindOrderResponse> updateFindOrderByProduct(FindOrderResponse response) {
        if (isNull(response.getProduct().getId())) {
            return Mono.error(new RuntimeException("Товар не найден"));
        }

        return productService.shortInfoById(response.getProduct().getId())
                .doOnNext(e -> e.setCount(response.getProduct().getCount()))
                .doOnNext(response::setProduct)
                .then(Mono.just(response));
    }

    private Mono<FindOrderResponse> updateFindOrderByTransfer(FindOrderResponse response) {
        Long transferId = response.getTransfer().getTransferId();
        Long addressId = response.getTransfer().getAddressId();
        if (isNull(transferId) || isNull(addressId)) {
            return Mono.error(new RuntimeException("Не найден способ доставки"));
        }

        return Mono.zip(transferService.findById(transferId), addressService.findById(addressId))
                .map(tuple -> orderMapper.toOrderTransferDto(
                        tuple.getT1(),
                        tuple.getT2().getFullAddress(),
                        tuple.getT2().getId(),
                        getImageId(tuple.getT1().getSending()))
                )
                .doOnNext(response::setTransfer)
                .then(Mono.just(response));
    }

    private Mono<FindOrderResponse> updateFindOrderByHistory(FindOrderResponse response) {
        return orderHistoryService.findByOrderId(response.getOrderId())
                .map(orderMapper::toOrderStatusHistoryDto)
                .collectList()
                .doOnNext(response::setHistories)
                .then(Mono.just(response));
    }

    private Mono<FindOrderResponse> updateImages(FindOrderResponse orderResponse) {
        return imageService.findActualImages(orderResponse.getOrderId(), ImageTag.ORDER)
                .collectList()
                .doOnNext(orderResponse::setImages)
                .then(Mono.just(orderResponse));
    }
}
