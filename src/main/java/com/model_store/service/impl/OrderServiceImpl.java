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
import com.model_store.repository.ReviewRepository;
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
    private final com.model_store.repository.ImageRepository imageRepository;
    private final OrderTermsService terms;
    private final OrderStockRestoration stockRestoration;
    private final OrderCaseRepository orderCaseRepository;
    private final ReviewRepository reviewRepository;
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
        if (requests == null || requests.isEmpty() || requests.stream().anyMatch(r -> r == null
                || r.getProductId() == null || r.getCount() == null || r.getCount() <= 0))
            return Mono.error(ApiErrors.badRequest(COUNT_INVALID,"Укажите товары и положительное количество"));
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
                        (r1, r2) -> {
                            if (!Objects.equals(r1.getAddressId(),r2.getAddressId())
                                    || !Objects.equals(r1.getTransferId(),r2.getTransferId())
                                    || !Objects.equals(r1.getDeliveryCarrier(),r2.getDeliveryCarrier())
                                    || !Objects.equals(r1.getDeliveryAddress(),r2.getDeliveryAddress()))
                                throw ApiErrors.badRequest(INVALID_TRANSFER,"Разные условия доставки одного товара");
                            return CreateOrderRequest.builder()
                                .productId(r1.getProductId())
                                .addressId(r1.getAddressId())
                                .transferId(r1.getTransferId())
                                .comment(r1.getComment())
                                .deliveryCarrier(r1.getDeliveryCarrier())
                                .deliveryAddress(r1.getDeliveryAddress())
                                .count(mergeCount(r1.getCount(), r2.getCount()))
                                .build();
                        }
                ));
    }

    private static int mergeCount(int first, int second) {
        long count = (long) first + second;
        if (count > Integer.MAX_VALUE) {
            throw ApiErrors.badRequest(COUNT_INVALID, "Суммарное количество одного товара слишком велико");
        }
        return (int) count;
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
        if (product.getCurrency() == null || product.getCurrency() == com.model_store.model.constant.Currency.CREATE)
            return Mono.error(ApiErrors.badRequest(com.model_store.exception.constant.ErrorCode.ORDER_PAYMENT_INVALID,"Укажите валюту товара"));
        var prepayment = PURCHASABLE.equals(product.getAvailability())
                ? 0F : Optional.ofNullable(product.getPrepaymentAmount()).orElse(0F);

        Order order = orderMapper.toOrder(request);
        order.setStatus(BOOKED);
        order.setPrepaymentAmount(prepayment * request.getCount());
        order.setTermsVersion(0L);
        order.setPricingKnown(true);
        order.setStockReserved(PURCHASABLE.equals(product.getAvailability()) && nonNull(product.getCount()));
        order.setGoodsAmount(OrderTermsService.catalogAmount(product.getPrice(), request.getCount()));
        order.setConfirmedPaidAmount(java.math.BigDecimal.ZERO.setScale(2));
        order.setPlannedPrepaymentAmount(OrderTermsService.catalogAmount(prepayment, request.getCount()));
        order.setDeliveryCarrier(request.getDeliveryCarrier());
        order.setDeliveryAddress(request.getDeliveryAddress());
        order.setSellerId(product.getParticipantId());
        order.setCustomerId(participantId);
        order.setTotalPrice((product.getPrice() - prepayment) * request.getCount());
        order.setProductName(product.getName());
        order.setProductUnitPrice(product.getPrice());
        order.setProductCurrency(product.getCurrency());
        order.setProductAvailability(product.getAvailability());
        Mono<Order> saveOrder = Mono.defer(() ->
                basketService.removeFromBasket(participantId, product.getId())
                        .then(snapshotLegacyDelivery(order, request))
                        .then(imageRepository.lockMainProductImage(product.getId()).doOnNext(order::setProductImageId))
                        .then(Mono.defer(() -> orderRepository.save(order))));

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

    private Mono<Void> snapshotLegacyDelivery(Order order, CreateOrderRequest request) {
        if (request.getDeliveryCarrier() != null) return Mono.empty();
        return Mono.zip(transferService.findById(request.getTransferId()), addressService.findById(request.getAddressId()))
            .switchIfEmpty(Mono.error(ApiErrors.badRequest(INVALID_TRANSFER,"Доставка недоступна")))
            .doOnNext(pair -> {
                order.setDeliveryCarrier(pair.getT1().getSending().name());
                order.setDeliveryAddress(pair.getT2().getFullAddress());
                if (Objects.equals(pair.getT1().getCurrency(),order.getProductCurrency()) && pair.getT1().getPrice() != null)
                    order.setDeliveryAmount(OrderTermsService.money(java.math.BigDecimal.valueOf(pair.getT1().getPrice())));
            }).then();
    }
    @Override
    @Transactional
    public Mono<Long> agreementOrder(Long orderId, String comment, Long participantId) {
        log.info("Seller agreement: orderId={}, sellerId={}", orderId, participantId);
        return orderRepository.findByIdForUpdate(orderId)
                .filter(order -> order.getStatus().equals(BOOKED) && order.getSellerId().equals(participantId))
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Нельзя выполнить операцию с данными условиями")))
                .flatMap(order -> persistTransition(order,
                        OrderTermsService.expectsPrepayment(order) ? AWAITING_PREPAYMENT : AWAITING_PAYMENT, comment))
                .doOnSuccess(id -> log.debug("Order agreed: orderId={}", id));
    }

    @Override
    @Transactional
    public Mono<Long> prepaymentOrder(Long orderId, Long imageId, String comment, Long participantId) {
        return orderRepository.findByIdForUpdate(orderId).flatMap(o -> terms.submit(orderId, participantId, new com.model_store.model.dto.OrderPaymentRequest(o.getTermsVersion(), imageId, comment), true));
    }

    @Override
    @Transactional
    public Mono<Long> sellerConfirmsPreorder(Long orderId, String comment, Long participantId) {
        return orderRepository.findByIdForUpdate(orderId)
                .flatMap(order -> terms.review(orderId, participantId, participantId,
                        new com.model_store.model.dto.OrderPaymentRequest(order.getTermsVersion(), null, comment),
                        true, AWAITING_PREPAYMENT_APPROVAL));
    }

    @Override
    @Transactional
    public Mono<Long> paymentOrder(Long orderId, Long imageId, String comment, Long participantId) {
        return orderRepository.findByIdForUpdate(orderId).flatMap(o -> o.getStatus() == OrderStatus.DISPUTED ? recordPaymentInDispute(o,imageId,participantId) : terms.submit(orderId, participantId, new com.model_store.model.dto.OrderPaymentRequest(o.getTermsVersion(), imageId, comment), false));
    }

    private Mono<Long> recordPaymentInDispute(Order order, Long imageId, Long participantId) {
        if (!Objects.equals(order.getCustomerId(),participantId))
            return Mono.error(ApiErrors.forbidden(com.model_store.exception.constant.ErrorCode.ACCESS_DENIED,"Нет доступа к заказу"));
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
                .map(this::sellerResponse))
                .concatMap(response -> participantService.findLoginById(response.getSellerId())
                        .doOnNext(response::setSellerLogin)
                        .thenReturn(response));
    }

    @Override
    public Flux<FindOrderResponse> getOrdersByCustomer(Long customerId) {
        return enrichOrders(updateFindOrderByOrderCustomer(customerId))
                .doOnNext(response -> response.setCanLeaveReview(response.getReviewId() == null
                        && (response.getActualStatus() == OrderStatus.COMPLETED
                        || response.getActualStatus() == OrderStatus.FAILED)));
    }

    private Flux<FindOrderResponse> enrichOrders(Flux<FindOrderResponse> source) {
        return source
                .concatMap(this::findImages)
                .concatMap(this::findOrderByHistory)
                .concatMap(this::findOrderByUser)
                .concatMap(this::findOrderByProduct)
                .concatMap(this::findOrderByTransfer)
                .concatMap(this::findOrderReview);
    }

    private Mono<FindOrderResponse> findOrderReview(FindOrderResponse response) {
        return reviewRepository.findFirstByOrderIdOrderByIdAsc(response.getOrderId())
                .doOnNext(review -> response.setReviewId(review.getId()))
                .thenReturn(response);
    }

    @Override
    @Transactional
    public Mono<Long> transferOrder(Long orderId, String deliveryUrl, String comment, Long participantId) {
        log.info("Order shipped: orderId={}, sellerId={}", orderId, participantId);
        return orderRepository.findByIdForUpdate(orderId)
                .filter(order -> order.getStatus().equals(OrderStatus.ASSEMBLING) && order.getSellerId().equals(participantId))
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Нельзя выполнить операцию с данными условиями")))
                .flatMap(order -> ensureNoOpenCase(order.getId()).then(orderRepository.transitionWithDelivery(order.getId(), order.getStatus().name(),
                                OrderStatus.ON_THE_WAY.name(), comment, deliveryUrl))
                        .flatMap(updated -> updated == 1 ? Mono.just(order.getId())
                                : Mono.error(new IllegalArgumentException("Статус заказа изменился. Повторите операцию"))))
                .doOnSuccess(id -> log.debug("Order status -> ON_THE_WAY: orderId={}", id));
    }

    @Override
    @Transactional
    public Mono<Long> deliveredOrder(Long orderId, String comment, Long participantId) {
        log.info("Order delivered: orderId={}, customerId={}", orderId, participantId);
        return orderRepository.findByIdForUpdate(orderId)
                .filter(order -> order.getStatus().equals(OrderStatus.ON_THE_WAY) && order.getCustomerId().equals(participantId))
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Нельзя выполнить операцию с данными условиями")))
                .flatMap(order -> persistTransition(order, OrderStatus.COMPLETED, comment))
                .doOnSuccess(id -> log.info("Order completed: orderId={}", id));
    }

    @Override
    @Transactional
    public Mono<Long> closureOrder(Long orderId, CloseOrderRequest request, Long participantId) {
        log.info("Order closure requested: orderId={}, participantId={}", orderId, participantId);
        return orderRepository.findByIdForUpdate(orderId)
                .filter(order -> List.of(order.getSellerId(), order.getCustomerId()).contains(participantId))
                .filter(order -> OrderTermsService.cancellable(order) && (order.getStatus() == BOOKED || participantId.equals(order.getCustomerId())))
                .flatMap(order -> orderCaseRepository.findOpenByOrderId(orderId).hasElement().flatMap(open -> open ? Mono.error(ApiErrors.alreadyExist(com.model_store.exception.constant.ErrorCode.ORDER_CASE_ALREADY_OPEN, "По заказу открыто обращение")) : Mono.just(order)))
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Нельзя выполнить операцию с данными условиями")))
                .flatMap(order -> guardedTransition(order, OrderStatus.CANCELLED, request.getComment())
                        .then(stockRestoration.restore(order))
                        .thenReturn(order.getId()))
                .doOnSuccess(id -> log.info("Order cancelled: orderId={}", id));
    }


    @Override
    public Mono<Integer> findCompletedCountBySellerId(Long sellerId) {
        return orderRepository.findCompletedCountBySellerId(sellerId);
    }

    @Override
    public Mono<Order> findById(Long orderId) {
        return orderRepository.findByIdForUpdate(orderId);
    }

    @Override
    public Mono<Integer> findCompletedCountByCustomerId(Long customerId) {
        return orderRepository.findCompletedCountByCustomerId(customerId);
    }


    private Flux<FindOrderResponse> findOrderByOrderSeller(Long sellerId) {
        return orderRepository.findBySellerId(sellerId)
                .map(this::sellerResponse);
    }

    private FindOrderResponse sellerResponse(Order order) {
        return OrderTermsService.enrich(order, orderMapper.toFindOrderResponseBySeller(order), null);
    }

    private Flux<FindOrderResponse> updateFindOrderByOrderCustomer(Long customerId) {
        return orderRepository.findByCustomerId(customerId)
                .map(order -> OrderTermsService.enrich(order, orderMapper.toFindOrderResponseByCustomer(order), customerId));
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
                    if (orderedProduct.getImageId() != null) product.setImageId(orderedProduct.getImageId());
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
        if (response.getCalculation().known() || response.getTransfer() == null) {
            return Mono.just(response);
        }
        Long transferId = response.getTransfer().getTransferId();
        Long addressId = response.getTransfer().getAddressId();
        if (isNull(transferId) || isNull(addressId)) {
            return Mono.just(response);
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
        if (request.getDeliveryCarrier() != null || request.getDeliveryAddress() != null) {
            OrderTermsService.validateDelivery(request.getDeliveryCarrier(), request.getDeliveryAddress());
            if (request.getAddressId() != null || request.getTransferId() != null)
                return Mono.error(ApiErrors.badRequest(INVALID_TRANSFER,"Передайте либо снимок доставки, либо addressId/transferId"));
            if (product.getParticipantId().equals(participantId)) return Mono.error(ApiErrors.badRequest(OWN_PRODUCT_ORDER_FORBIDDEN, "Нельзя заказать свой товар"));
            return Mono.empty();
        }
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
    private Mono<Void> guardedTransition(Order order, OrderStatus target, String comment) {
        return ensureNoOpenCase(order.getId()).then(orderRepository.transition(order.getId(), order.getStatus().name(), target.name(), comment))
                .flatMap(updated -> updated == 1 ? Mono.<Void>empty()
                        : Mono.error(new IllegalArgumentException("Статус заказа изменился. Повторите операцию")));
    }

    private Mono<Void> ensureNoOpenCase(Long id) {
        return orderCaseRepository.findOpenByOrderId(id).hasElement().flatMap(open -> open
            ? Mono.error(ApiErrors.alreadyExist(com.model_store.exception.constant.ErrorCode.ORDER_CASE_ALREADY_OPEN,"По заказу открыто обращение"))
            : Mono.empty());
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
