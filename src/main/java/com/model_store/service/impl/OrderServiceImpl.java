package com.model_store.service.impl;

import com.model_store.exception.ApiErrors;
import com.model_store.mapper.OrderMapper;
import com.model_store.model.base.Address;
import com.model_store.model.base.Order;
import com.model_store.model.base.Product;
import com.model_store.model.base.Transfer;
import com.model_store.model.constant.ImageStatus;
import com.model_store.model.constant.ImageTag;
import com.model_store.model.constant.OrderStatus;
import com.model_store.model.dto.CloseOrderRequest;
import com.model_store.model.dto.CreateOrderRequest;
import com.model_store.model.dto.FindOrderResponse;
import com.model_store.model.dto.GetRequiredODataOrderDto;
import com.model_store.model.dto.UpdateOrderRequest;
import com.model_store.repository.OrderRepository;
import com.model_store.service.AddressService;
import com.model_store.service.BasketService;
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

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import static com.model_store.exception.constant.ErrorCode.PARTICIPANT_NOT_FOUND;
import static com.model_store.exception.constant.ErrorCode.PRODUCT_NOT_FOUND;
import static com.model_store.exception.constant.ErrorCode.TRANSFER_NOT_FOUND;
import static com.model_store.model.constant.OrderStatus.AWAITING_PAYMENT;
import static com.model_store.model.constant.OrderStatus.AWAITING_PREPAYMENT;
import static com.model_store.model.constant.OrderStatus.AWAITING_PREPAYMENT_APPROVAL;
import static com.model_store.model.constant.OrderStatus.BOOKED;
import static com.model_store.model.constant.ProductAvailabilityType.EXTERNAL_ONLY;
import static com.model_store.model.constant.ProductAvailabilityType.PURCHASABLE;
import static com.model_store.service.util.UtilService.getImageId;
import static java.util.Collections.emptyList;
import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

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
    private final BasketService basketService;

    @Override
    public Mono<GetRequiredODataOrderDto> getRequiredDataForCreateOrder(Long participantId, Long productId) {
        Mono<Product> productMono = productService.findById(productId);
        Mono<List<Address>> addressMono = addressService.findByParticipantId(participantId).collectList().defaultIfEmpty(emptyList());
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
    public Mono<Long> updateStatusOrder(UpdateOrderRequest request) {
        return orderRepository.findById(request.getOrderId())
                .doOnNext(order -> order.setStatus(request.getOrderStatus()))
                .flatMap(order -> orderRepository.save(order).map(Order::getId));
    }

    @Override
    @Transactional
    public Mono<List<Long>> createOrders(List<CreateOrderRequest> requests, Long participantId) {
        Map<Long, CreateOrderRequest> merged = mergeRequests(requests);

        return Flux.fromIterable(merged.values())
                .concatMap(req -> createOrder(req, participantId))
                .collectList();
    }

    private Map<Long, CreateOrderRequest> mergeRequests(List<CreateOrderRequest> requests) {
        return requests.stream()
                .collect(Collectors.toMap(
                        CreateOrderRequest::getProductId,
                        r -> r,
                        (r1, r2) -> {
                            r1.setCount(r1.getCount() + r2.getCount());
                            return r1;
                        }
                ));
    }


    protected Mono<Long> createOrder(CreateOrderRequest request, Long participantId) {
        return validateCreateOrderRequest(request, participantId)
                .then(productService.findById(request.getProductId()))
                .switchIfEmpty(Mono.error(new IllegalStateException("Товар не найден")))
                .flatMap(product ->
                        validateCreateOrder(product, request.getCount(), participantId)
                                .thenReturn(product)
                )
                .flatMap(product -> createOrderAndUpdateProduct(product, request, participantId));
    }

    protected Mono<Long> createOrderAndUpdateProduct(Product product, CreateOrderRequest request, Long participantId) {
        var prepayment = Optional.ofNullable(product.getPrepaymentAmount()).orElse(0F);

        Order order = orderMapper.toOrder(request);
        order.setStatus(BOOKED);
        order.setSellerId(product.getParticipantId());
        order.setCustomerId(participantId);
        order.setTotalPrice((product.getPrice() - prepayment) * request.getCount());
        Mono<Order> saveOrder = Mono.defer(() ->
                basketService.removeFromBasket(participantId, product.getId())
                        .then( orderRepository.save(order))
        );

        if (product.getAvailability().equals(PURCHASABLE)) {
            if (nonNull(product.getCount())) product.setCount(product.getCount() - request.getCount());
            return productService.save(product).then(saveOrder.map(Order::getId));
        }

        order.setPrepaymentAmount(prepayment * request.getCount());
        return saveOrder.map(Order::getId);
    }

    @Override
    @Transactional
    public Mono<Long> agreementOrder(Long orderId, String comment, Long participantId) {
        return orderRepository.findById(orderId)
                .filter(order -> order.getStatus().equals(BOOKED) && order.getSellerId().equals(participantId))
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Нельзя выполнить операцию с данными условиями"))) // Если заказ не найден
                .doOnNext(order -> order.setComment(comment))
                .doOnNext(order -> order.setStatus(!isNull(order.getPrepaymentAmount()) && order.getPrepaymentAmount() > 0 ? AWAITING_PREPAYMENT : AWAITING_PAYMENT))
                .flatMap(order -> orderRepository.save(order).map(Order::getId));
    }

    @Override
    public Mono<Long> prepaymentOrder(Long orderId, Long imageId, String comment, Long participantId) {
        return orderRepository.findById(orderId)
                .filter(order -> !isNull(order.getPrepaymentAmount()) && order.getPrepaymentAmount() != 0)
                .filter(order -> order.getStatus().equals(AWAITING_PREPAYMENT) && participantId.equals(order.getCustomerId()))
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Нельзя выполнить операцию с данными условиями"))) // Если заказ не найден
                .flatMap(order ->
                        imageService.updateImagesStatus(List.of(imageId), order.getId(), ImageStatus.ACTIVE, ImageTag.ORDER)
                                .thenReturn(order)
                ).flatMap(order -> {
                    order.setStatus(AWAITING_PREPAYMENT_APPROVAL);
                    order.setComment(comment);
                    order.setImagePaymentProofId(imageId);
                    return orderRepository.save(order).then(Mono.just(order.getId()));
                });
    }

    @Override
    public Mono<Long> sellerConfirmsPreorder(Long orderId, String comment, Long participantId) {
        return orderRepository.findById(orderId)
                .filter(order -> order.getStatus().equals(AWAITING_PREPAYMENT_APPROVAL) && order.getSellerId().equals(participantId))
                .doOnNext(order -> order.setComment(comment))
                .doOnNext(order -> order.setStatus(AWAITING_PAYMENT))
                .flatMap(order -> orderRepository.save(order).map(Order::getId));
    }

    @Override
    public Mono<Long> paymentOrder(Long orderId, Long imageId, String comment, Long participantId) {
        return orderRepository.findById(orderId)
                .filter(order -> order.getStatus().equals(AWAITING_PAYMENT) && order.getCustomerId().equals(participantId))
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Нельзя выполнить операцию с данными условиями"))) // Если заказ не найден
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
    public Flux<FindOrderResponse> getOrdersBySeller(Long sellerId) {
        return findOrderByOrderSeller(sellerId)
                .flatMap(this::findImages)
                .flatMap(this::findOrderByHistory)
                .flatMap(this::findOrderByUser)
                .flatMap(this::findOrderByProduct)
                .flatMap(this::findOrderByTransfer);
    }

    @Override
    public Flux<FindOrderResponse> getOrdersByCustomer(Long customerId) {
        return updateFindOrderByOrderCustomer(customerId)
                .flatMap(this::findImages)
                .flatMap(this::findOrderByHistory)
                .flatMap(this::findOrderByUser)
                .flatMap(this::findOrderByProduct)
                .flatMap(this::findOrderByTransfer);
    }

    @Override
    public Mono<Long> transferOrder(Long orderId, String deliveryUrl, String comment, Long participantId) {
        return orderRepository.findById(orderId)
                .filter(order -> order.getStatus().equals(OrderStatus.ASSEMBLING) && order.getSellerId().equals(participantId))
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Нельзя выполнить операцию с данными условиями"))) // Если заказ не найден
                .doOnNext(order -> order.setDeliveryUrl(deliveryUrl))
                .doOnNext(order -> order.setComment(comment))
                .doOnNext(order -> order.setStatus(OrderStatus.ON_THE_WAY))
                .flatMap(order -> orderRepository.save(order).map(Order::getId));
    }

    @Override
    public Mono<Long> deliveredOrder(Long orderId, String comment, Long participantId) {
        return orderRepository.findById(orderId)
                .filter(order -> order.getStatus().equals(OrderStatus.ON_THE_WAY) && order.getCustomerId().equals(participantId))
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Нельзя выполнить операцию с данными условиями"))) // Если заказ не найден
                .doOnNext(order -> order.setComment(comment))
                .doOnNext(order -> order.setStatus(OrderStatus.COMPLETED))
                .flatMap(order -> orderRepository.save(order).map(Order::getId));
    }

    @Override
    public Mono<Long> openDisputeForOrder(Long orderId, List<Long> imageIds, String comment, Long participantId) {
        return orderRepository.findById(orderId)
                .filter(order -> order.getStatus().equals(OrderStatus.ON_THE_WAY) && order.getCustomerId().equals(participantId))
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Нельзя выполнить операцию с данными условиями"))) // Если заказ не найден
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
    public Mono<Long> closeDisputeForOrder(Long orderId, List<Long> imageIds, String comment, Long participantId) {
        return orderRepository.findById(orderId)
                .filter(order -> order.getStatus().equals(OrderStatus.DISPUTED) && order.getCustomerId().equals(participantId))
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Нельзя выполнить операцию с данными условиями")))
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
    public Mono<Long> closureOrder(CloseOrderRequest request, Long participantId) {
        return orderRepository.findById(request.getOrderId())
                .filter(order -> List.of(order.getSellerId(), order.getCustomerId()).contains(participantId))
                .filter(order -> List.of(BOOKED, AWAITING_PAYMENT).contains(order.getStatus()))
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Нельзя выполнить операцию с данными условиями"))) // Если заказ не найден
                .doOnNext(order -> order.setComment(request.getComment()))
                .doOnNext(order -> order.setStatus(OrderStatus.FAILED))
                .flatMap(order -> orderRepository.save(order).map(Order::getId));
    }


    @Override
    public Mono<Integer> findCompletedCountBySellerId(Long sellerId) {
        return orderRepository.findCompletedCountBySellerId(sellerId);
    }

    @Override
    public Mono<Order> findById(Long orderId) {
        return orderRepository.findById(orderId);
    }

    @Override
    public Mono<Integer> findCompletedCountByCustomerId(Long customerId) {
        return orderRepository.findCompletedCountByCustomerId(customerId);
    }


    private Flux<FindOrderResponse> findOrderByOrderSeller(Long sellerId) {
        return orderRepository.findBySellerId(sellerId)
                .map(orderMapper::toFindOrderResponseBySeller);
    }

    private Flux<FindOrderResponse> updateFindOrderByOrderCustomer(Long customerId) {
        return orderRepository.findByCustomerId(customerId)
                .map(orderMapper::toFindOrderResponseByCustomer);
    }

    private Mono<FindOrderResponse> findOrderByUser(FindOrderResponse response) {
        if (isNull(response.getUserInfo().getId())) {
            return Mono.error(ApiErrors.notFound(PARTICIPANT_NOT_FOUND, "Пользователь не найден"));
        }

        return participantService.findShortInfo(response.getUserInfo().getId())
                .doOnNext(response::setUserInfo)
                .then(Mono.just(response));
    }

    private Mono<FindOrderResponse> findOrderByProduct(FindOrderResponse response) {
        if (isNull(response.getProduct().getId())) {
            return Mono.error(ApiErrors.notFound(PRODUCT_NOT_FOUND, "Товар не найден"));
        }

        return productService.shortInfoById(response.getProduct().getId())
                .doOnNext(e -> e.setCount(response.getProduct().getCount()))
                .doOnNext(response::setProduct)
                .then(Mono.just(response));
    }

    private Mono<FindOrderResponse> findOrderByTransfer(FindOrderResponse response) {
        Long transferId = response.getTransfer().getTransferId();
        Long addressId = response.getTransfer().getAddressId();
        if (isNull(transferId) || isNull(addressId)) {
            return Mono.error(ApiErrors.notFound(TRANSFER_NOT_FOUND, "Способ доставки не найден"));
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

    private Mono<FindOrderResponse> findOrderByHistory(FindOrderResponse response) {
        return orderHistoryService.findByOrderId(response.getOrderId())
                .map(orderMapper::toOrderStatusHistoryDto)
                .collectList()
                .doOnNext(response::setHistories)
                .then(Mono.just(response));
    }

    private Mono<FindOrderResponse> findImages(FindOrderResponse orderResponse) {
        return imageService.findActualImages(orderResponse.getOrderId(), ImageTag.ORDER)
                .collectList()
                .doOnNext(orderResponse::setImages)
                .then(Mono.just(orderResponse));
    }


    private Mono<Void> validateCreateOrder(Product product, Integer count, Long participantId) {
        var availability = product.getAvailability();
        var productCount = product.getCount();

        if (nonNull(productCount) && productCount - count < 0) {
            return Mono.error(new IllegalArgumentException("Товар закончился"));
        }
        if(nonNull(count) && count <= 0) throw new IllegalArgumentException("Количество товаров должно быть больше чем 0");

        if (EXTERNAL_ONLY.equals(availability)) {
            return Mono.error(new IllegalArgumentException("Нельзя заказать товар из смежного магазина"));
        }
        if (Objects.equals(product.getParticipantId(), participantId)) {
            return Mono.error(new IllegalArgumentException("Нельзя заказать свой товар"));
        }
        return Mono.empty();
    }

    private Mono<Void> validateCreateOrderRequest(CreateOrderRequest request, Long participantId) {
        return Mono.when(
                addressService.findByParticipantId(participantId)
                        .filter(a -> a.getId().equals(request.getAddressId()))
                        .switchIfEmpty(Mono.error(new IllegalStateException("Указан неверный адрес доставки")))
                        .then(),

                productService.findById(request.getProductId())
                        .switchIfEmpty(Mono.error(new IllegalStateException("Товар не найден")))
                        .flatMap(p -> {
                            if (p.getParticipantId().equals(participantId)) {
                                return Mono.error(new IllegalStateException("Нельзя оформить заказ на собственный товар"));
                            }
                            return transferService.findByParticipantId(p.getParticipantId())
                                    .filter(t -> t.getId().equals(request.getTransferId()))
                                    .next()
                                    .switchIfEmpty(Mono.error(new IllegalStateException("Указан неверный способ доставки")))
                                    .then();
                        })
        );
    }

}
