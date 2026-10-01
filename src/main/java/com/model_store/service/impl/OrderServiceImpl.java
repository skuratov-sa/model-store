package com.model_store.service.impl;

import com.model_store.exception.ApiErrors;
import com.model_store.mapper.OrderMapper;
import com.model_store.model.base.Address;
import com.model_store.model.base.Order;
import com.model_store.model.base.Product;
import com.model_store.model.base.Transfer;
import com.model_store.model.constant.ImageTag;
import com.model_store.model.constant.OrderStatus;
import com.model_store.model.constant.ProductStatus;
import com.model_store.model.dto.CloseOrderRequest;
import com.model_store.model.dto.CreateOrderRequest;
import com.model_store.model.dto.FindOrderResponse;
import com.model_store.model.dto.GetRequiredODataOrderDto;
import com.model_store.model.dto.ProductDto;
import com.model_store.repository.OrderCaseRepository;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import static com.model_store.exception.constant.ErrorCode.COUNT_INVALID;
import static com.model_store.exception.constant.ErrorCode.INVALID_ADDRESS;
import static com.model_store.exception.constant.ErrorCode.INVALID_TRANSFER;
import static com.model_store.exception.constant.ErrorCode.OUT_OF_STOCK;
import static com.model_store.exception.constant.ErrorCode.OWN_PRODUCT_ORDER_FORBIDDEN;
import static com.model_store.exception.constant.ErrorCode.PARTICIPANT_NOT_FOUND;
import static com.model_store.exception.constant.ErrorCode.PRODUCT_NOT_FOUND;
import static com.model_store.exception.constant.ErrorCode.TRANSFER_NOT_FOUND;
import static com.model_store.model.constant.OrderStatus.AWAITING_PAYMENT;
import static com.model_store.model.constant.OrderStatus.AWAITING_PREPAYMENT;
import static com.model_store.model.constant.OrderStatus.AWAITING_PREPAYMENT_APPROVAL;
import static com.model_store.model.constant.OrderStatus.BOOKED;
import static com.model_store.model.constant.ProductAvailabilityType.PURCHASABLE;
import static com.model_store.service.util.UtilService.getImageId;
import static java.util.Collections.emptyList;
import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {
    private final OrderRepository orderRepository;
    private final OrderCaseRepository orderCaseRepository;
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
        Mono<Product> productMono = productService.findActualProduct(productId)
                .switchIfEmpty(Mono.error(ApiErrors.notFound(PRODUCT_NOT_FOUND, "Товар не найден")));
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
    public Mono<List<Long>> createOrders(List<CreateOrderRequest> requests, Long participantId) {
        log.info("Creating {} order(s) for participantId={}", requests.size(), participantId);
        Map<Long, CreateOrderRequest> merged = mergeRequests(requests);

        return Flux.fromIterable(merged.entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .map(Map.Entry::getValue)
                        .toList())
                .concatMap(req -> createOrder(req, participantId))
                .collectList()
                .doOnSuccess(ids -> log.info("Created orders: ids={}, participantId={}", ids, participantId));
    }

    private Map<Long, CreateOrderRequest> mergeRequests(List<CreateOrderRequest> requests) {
        return requests.stream()
                .collect(Collectors.toMap(
                        CreateOrderRequest::getProductId,
                        r -> r,
                        (r1, r2) -> CreateOrderRequest.builder()
                                .productId(r1.getProductId())
                                .addressId(r1.getAddressId())
                                .transferId(r1.getTransferId())
                                .comment(r1.getComment())
                                .count(r1.getCount() + r2.getCount())
                                .build()
                ));
    }


    protected Mono<Long> createOrder(CreateOrderRequest request, Long participantId) {
        log.debug("Creating order: productId={}, participantId={}, count={}", request.getProductId(), participantId, request.getCount());
        return productService.findActualProductForUpdate(request.getProductId())
                .switchIfEmpty(Mono.error(ApiErrors.notFound(PRODUCT_NOT_FOUND, "Товар не найден")))
                .flatMap(product ->
                        validateCreateOrderRequest(request, participantId, product)
                                .then(validateCreateOrder(product, request.getCount(), participantId))
                                .thenReturn(product)
                )
                .flatMap(product -> createOrderAndUpdateProduct(product, request, participantId));
    }

    protected Mono<Long> createOrderAndUpdateProduct(Product product, CreateOrderRequest request, Long participantId) {
        var prepayment = PURCHASABLE.equals(product.getAvailability())
                ? 0F : Optional.ofNullable(product.getPrepaymentAmount()).orElse(0F);

        Order order = orderMapper.toOrder(request);
        order.setStatus(BOOKED);
        order.setSellerId(product.getParticipantId());
        order.setCustomerId(participantId);
        order.setTotalPrice((product.getPrice() - prepayment) * request.getCount());
        order.setProductName(product.getName());
        order.setProductUnitPrice(product.getPrice());
        order.setProductCurrency(product.getCurrency());
        order.setProductAvailability(product.getAvailability());
        Mono<Order> saveOrder = Mono.defer(() ->
                basketService.removeFromBasket(participantId, product.getId())
                        .then(orderRepository.save(order)));

        if (product.getAvailability().equals(PURCHASABLE)) {
            if (nonNull(product.getCount())) {
                return productService.decrementCountIfSufficient(product.getId(), request.getCount())
                        .then(saveOrder.map(Order::getId));
            }
            return saveOrder.map(Order::getId);
        }

        order.setPrepaymentAmount(prepayment * request.getCount());
        return saveOrder.map(Order::getId);
    }

    @Override
    @Transactional
    public Mono<Long> agreementOrder(Long orderId, String comment, Long participantId) {
        log.info("Seller agreement: orderId={}, sellerId={}", orderId, participantId);
        return orderRepository.findById(orderId)
                .filter(order -> order.getStatus().equals(BOOKED) && order.getSellerId().equals(participantId))
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Нельзя выполнить операцию с данными условиями")))
                .flatMap(order -> persistTransition(order,
                        !isNull(order.getPrepaymentAmount()) && order.getPrepaymentAmount() > 0
                                ? AWAITING_PREPAYMENT : AWAITING_PAYMENT, comment))
                .doOnSuccess(id -> log.debug("Order agreed: orderId={}", id));
    }

    @Override
    @Transactional
    public Mono<Long> prepaymentOrder(Long orderId, Long imageId, String comment, Long participantId) {
        log.info("Prepayment upload: orderId={}, customerId={}, imageId={}", orderId, participantId, imageId);
        return orderRepository.findById(orderId)
                .filter(order -> !isNull(order.getPrepaymentAmount()) && order.getPrepaymentAmount() != 0)
                .filter(order -> order.getStatus().equals(AWAITING_PREPAYMENT) && participantId.equals(order.getCustomerId()))
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Нельзя выполнить операцию с данными условиями"))) // Если заказ не найден
                .flatMap(order -> transitionWithProof(order, AWAITING_PREPAYMENT_APPROVAL, comment, imageId)
                        .then(imageService.activateOrderProof(imageId, order.getId(), participantId))
                        .then(orderCaseRepository.dismissCancellationAfterPayment(order.getId()))
                        .thenReturn(order.getId()));
    }

    @Override
    @Transactional
    public Mono<Long> sellerConfirmsPreorder(Long orderId, String comment, Long participantId) {
        log.info("Seller confirms prepayment: orderId={}, sellerId={}", orderId, participantId);
        return orderRepository.findById(orderId)
                .filter(order -> order.getStatus().equals(AWAITING_PREPAYMENT_APPROVAL) && order.getSellerId().equals(participantId))
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Нельзя выполнить операцию с данными условиями")))
                .flatMap(order -> persistTransition(order, AWAITING_PAYMENT, comment))
                .doOnSuccess(id -> log.debug("Prepayment confirmed: orderId={}", id));
    }

    @Override
    @Transactional
    public Mono<Long> paymentOrder(Long orderId, Long imageId, String comment, Long participantId) {
        log.info("Payment upload: orderId={}, customerId={}, imageId={}", orderId, participantId, imageId);
        return orderRepository.findById(orderId)
                .filter(order -> order.getCustomerId().equals(participantId))
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Нельзя выполнить операцию с данными условиями"))) // Если заказ не найден
                .flatMap(order -> {
                    if (order.getStatus() == OrderStatus.DISPUTED) {
                        return recordPaymentInDispute(order, imageId, participantId);
                    }
                    if (order.getStatus() != AWAITING_PAYMENT) {
                        return Mono.error(new IllegalArgumentException("Нельзя выполнить операцию с данными условиями"));
                    }
                    return transitionWithProof(order, OrderStatus.ASSEMBLING, comment, imageId)
                            .then(imageService.activateOrderProof(imageId, order.getId(), participantId))
                            .then(orderCaseRepository.dismissCancellationAfterPayment(order.getId()))
                            .thenReturn(order.getId())
                            .onErrorResume(IllegalArgumentException.class, error ->
                                    orderRepository.findById(orderId)
                                            .filter(current -> current.getStatus() == OrderStatus.DISPUTED)
                                            .flatMap(current -> recordPaymentInDispute(current, imageId, participantId))
                                            .switchIfEmpty(Mono.error(error)));
                });
    }

    private Mono<Long> recordPaymentInDispute(Order order, Long imageId, Long participantId) {
        return orderCaseRepository.findOpenByOrderId(order.getId())
                .filter(c -> "DISPUTE".equals(c.getKind())
                        && c.getPreviousOrderStatus() == AWAITING_PAYMENT
                        && order.getImagePaymentProofId() != null)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Операция недоступна для текущего состояния заказа")))
                .flatMap(c -> orderRepository.recordDisputedPayment(order.getId(),
                                order.getImagePaymentProofId(), imageId)
                        .flatMap(updated -> updated == 1 ? Mono.<Void>empty()
                                : Mono.error(new IllegalArgumentException("Подтверждение оплаты уже добавлено или спор завершён")))
                        .then(imageService.activateOrderProof(imageId, order.getId(), participantId))
                        .then(orderCaseRepository.addEvidence(c.getId(), imageId, participantId))
                        .flatMap(updated -> updated == 1 ? Mono.just(order.getId())
                                : Mono.error(new IllegalArgumentException("Не удалось добавить подтверждение к спору"))));
    }

    @Override
    public Flux<FindOrderResponse> getOrdersBySeller(Long sellerId) {
        return enrichOrders(findOrderByOrderSeller(sellerId));
    }

    @Override
    public Flux<FindOrderResponse> getAgentOrders(Long agentId, OrderStatus status, int limit, long offset) {
        return enrichOrders(orderRepository.findAgentOrders(agentId, status, limit, offset)
                .map(orderMapper::toFindOrderResponseBySeller))
                .concatMap(response -> participantService.findLoginById(response.getSellerId())
                        .doOnNext(response::setSellerLogin)
                        .thenReturn(response));
    }

    @Override
    public Flux<FindOrderResponse> getOrdersByCustomer(Long customerId) {
        return enrichOrders(updateFindOrderByOrderCustomer(customerId));
    }

    private Flux<FindOrderResponse> enrichOrders(Flux<FindOrderResponse> source) {
        return source
                .concatMap(this::findImages)
                .concatMap(this::findOrderByHistory)
                .concatMap(this::findOrderByUser)
                .concatMap(this::findOrderByProduct)
                .concatMap(this::findOrderByTransfer);
    }

    @Override
    @Transactional
    public Mono<Long> transferOrder(Long orderId, String deliveryUrl, String comment, Long participantId) {
        log.info("Order shipped: orderId={}, sellerId={}", orderId, participantId);
        return orderRepository.findById(orderId)
                .filter(order -> order.getStatus().equals(OrderStatus.ASSEMBLING) && order.getSellerId().equals(participantId))
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Нельзя выполнить операцию с данными условиями")))
                .flatMap(order -> orderRepository.transitionWithDelivery(order.getId(), order.getStatus().name(),
                                OrderStatus.ON_THE_WAY.name(), comment, deliveryUrl)
                        .flatMap(updated -> updated == 1 ? Mono.just(order.getId())
                                : Mono.error(new IllegalArgumentException("Статус заказа изменился. Повторите операцию"))))
                .doOnSuccess(id -> log.debug("Order status -> ON_THE_WAY: orderId={}", id));
    }

    @Override
    @Transactional
    public Mono<Long> deliveredOrder(Long orderId, String comment, Long participantId) {
        log.info("Order delivered: orderId={}, customerId={}", orderId, participantId);
        return orderRepository.findById(orderId)
                .filter(order -> order.getStatus().equals(OrderStatus.ON_THE_WAY) && order.getCustomerId().equals(participantId))
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Нельзя выполнить операцию с данными условиями")))
                .flatMap(order -> persistTransition(order, OrderStatus.COMPLETED, comment))
                .doOnSuccess(id -> log.info("Order completed: orderId={}", id));
    }

    @Override
    @Transactional
    public Mono<Long> closureOrder(CloseOrderRequest request, Long participantId) {
        log.info("Order closure requested: orderId={}, participantId={}", request.getOrderId(), participantId);
        return orderRepository.findById(request.getOrderId())
                .filter(order -> List.of(order.getSellerId(), order.getCustomerId()).contains(participantId))
                .filter(order -> order.getStatus() == BOOKED)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Нельзя выполнить операцию с данными условиями")))
                .flatMap(order -> guardedTransition(order, OrderStatus.CANCELLED, request.getComment())
                        .then(restoreStockIfNeeded(order))
                        .thenReturn(order.getId()))
                .doOnSuccess(id -> log.info("Order cancelled: orderId={}", id));
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
                .switchIfEmpty(Mono.fromSupplier(() -> {
                    ProductDto product = response.getProduct();
                    product.setSellerId(response.getSellerId());
                    product.setStatus(ProductStatus.DELETED);
                    return product;
                }))
                .doOnNext(product -> {
                    ProductDto orderedProduct = response.getProduct();
                    product.setName(orderedProduct.getName());
                    product.setPrice(orderedProduct.getPrice());
                    product.setCurrency(orderedProduct.getCurrency());
                    product.setAvailability(orderedProduct.getAvailability());
                    product.setCount(orderedProduct.getCount());
                })
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
        var productCount = product.getCount();

        if (isNull(count) || count <= 0) {
            return Mono.error(ApiErrors.badRequest(COUNT_INVALID, "Количество товаров должно быть больше чем 0"));
        }
        if (nonNull(productCount) && productCount - count < 0) {
            return Mono.error(ApiErrors.badRequest(OUT_OF_STOCK, "Недостаточно товара на складе"));
        }

        if (Objects.equals(product.getParticipantId(), participantId)) {
            return Mono.error(ApiErrors.badRequest(OWN_PRODUCT_ORDER_FORBIDDEN, "Нельзя заказать свой товар"));
        }
        return Mono.empty();
    }

    private Mono<Void> validateCreateOrderRequest(CreateOrderRequest request, Long participantId, Product product) {
        return Mono.when(
                addressService.findByParticipantId(participantId)
                        .filter(a -> a.getId().equals(request.getAddressId()))
                        .switchIfEmpty(Mono.error(ApiErrors.badRequest(INVALID_ADDRESS, "Указан неверный адрес доставки")))
                        .then(),
                validateProductTransfer(request, participantId, product)
        );
    }

    private Mono<Void> validateProductTransfer(CreateOrderRequest request, Long participantId, Product product) {
        if (product.getParticipantId().equals(participantId)) {
            return Mono.error(ApiErrors.badRequest(OWN_PRODUCT_ORDER_FORBIDDEN, "Нельзя оформить заказ на собственный товар"));
        }

        return transferService.findByParticipantId(product.getParticipantId())
                .filter(t -> t.getId().equals(request.getTransferId()))
                .next()
                .switchIfEmpty(Mono.error(ApiErrors.badRequest(INVALID_TRANSFER, "Указан неверный способ доставки")))
                .then();
    }
    private Mono<Void> restoreStockIfNeeded(Order order) {
        return productService.findByIdForUpdate(order.getProductId())
                .filter(product -> PURCHASABLE.equals(product.getAvailability()))
                .filter(product -> nonNull(product.getCount()))
                .flatMap(product -> productService.incrementCountIfLimited(product.getId(), order.getCount()))
                .then();
    }

    private Mono<Void> guardedTransition(Order order, OrderStatus target, String comment) {
        return orderRepository.transition(order.getId(), order.getStatus().name(), target.name(), comment)
                .flatMap(updated -> updated == 1 ? Mono.<Void>empty()
                        : Mono.error(new IllegalArgumentException("Статус заказа изменился. Повторите операцию")));
    }

    private Mono<Long> persistTransition(Order order, OrderStatus target, String comment) {
        return guardedTransition(order, target, comment).thenReturn(order.getId());
    }

    private Mono<Void> transitionWithProof(Order order, OrderStatus target, String comment, Long imageId) {
        return orderRepository.transitionWithProof(order.getId(), order.getStatus().name(), target.name(), comment, imageId)
                .flatMap(updated -> updated == 1 ? Mono.<Void>empty()
                        : Mono.error(new IllegalArgumentException("Статус заказа изменился. Повторите операцию")));
    }
}
