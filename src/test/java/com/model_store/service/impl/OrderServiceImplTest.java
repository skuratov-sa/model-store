package com.model_store.service.impl;

import com.model_store.exception.ApiException;
import com.model_store.exception.constant.ErrorCode;
import com.model_store.model.FindProductRequest;
import com.model_store.model.base.Address;
import com.model_store.model.base.Image;
import com.model_store.model.base.Order;
import com.model_store.model.base.Participant;
import com.model_store.model.base.ParticipantAddress;
import com.model_store.model.base.Product;
import com.model_store.model.base.Transfer;
import com.model_store.model.constant.AddressStatus;
import com.model_store.model.constant.Currency;
import com.model_store.model.constant.ImageStatus;
import com.model_store.model.constant.ImageTag;
import com.model_store.model.constant.OrderStatus;
import com.model_store.model.constant.ParticipantRole;
import com.model_store.model.constant.ParticipantStatus;
import com.model_store.model.constant.ProductAvailabilityType;
import com.model_store.model.constant.ProductStatus;
import com.model_store.model.constant.SellerStatus;
import com.model_store.model.constant.ShippingMethodsType;
import com.model_store.model.constant.SortByType;
import com.model_store.model.constant.TransferStatus;
import com.model_store.model.dto.CreateOrderRequest;
import com.model_store.model.dto.CloseOrderRequest;
import com.model_store.model.page.Pageable;
import com.model_store.repository.AddressRepository;
import com.model_store.repository.ImageRepository;
import com.model_store.repository.OrderRepository;
import com.model_store.repository.ParticipantAddressRepository;
import com.model_store.repository.ProductBasketRepository;
import com.model_store.repository.TransferRepository;
import com.model_store.service.BasketService;
import com.model_store.service.IntegrationTest;
import com.model_store.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;

import static com.model_store.model.constant.OrderStatus.AWAITING_PAYMENT;
import static com.model_store.model.constant.OrderStatus.AWAITING_PREPAYMENT;
import static com.model_store.model.constant.OrderStatus.AWAITING_PREPAYMENT_APPROVAL;
import static org.assertj.core.api.Assertions.assertThat;

class OrderServiceImplTest extends IntegrationTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private BasketService basketService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private ProductBasketRepository productBasketRepository;

    @Autowired
    private TransferRepository transferRepository;

    @Autowired
    private AddressRepository addressRepository;

    @Autowired
    private ParticipantAddressRepository participantAddressRepository;

    @Autowired
    private ImageRepository imageRepository;

    @Autowired
    private DatabaseClient databaseClient;

    private Participant seller;
    private Participant buyer;
    private Transfer sellerTransfer;
    private Address buyerAddress;

    @BeforeEach
    void setUp() {
        databaseClient.sql("""
                TRUNCATE TABLE product_category, product_basket, "order", product,
                    participant_address, transfer, address, participant, image
                RESTART IDENTITY CASCADE
                """).fetch().rowsUpdated().block();

        seller = participantRepository.save(newParticipant("seller")).block();
        buyer = participantRepository.save(newParticipant("buyer")).block();

        sellerTransfer = transferRepository.save(
                Transfer.builder()
                        .sending(ShippingMethodsType.RUSSIAN_POST)
                        .price(200)
                        .currency(Currency.RUB)
                        .participantId(seller.getId())
                        .status(TransferStatus.ACTIVE)
                        .build()
        ).block();

        buyerAddress = addressRepository.save(
                Address.builder()
                        .country("Russia")
                        .city("Moscow")
                        .street("Lenina")
                        .houseNumber("1")
                        .apartmentNumber("10")
                        .index(101000)
                        .status(AddressStatus.ACTIVE)
                        .build()
        ).block();

        participantAddressRepository.save(
                ParticipantAddress.builder()
                        .participantId(buyer.getId())
                        .addressId(buyerAddress.getId())
                        .build()
        ).block();
    }

    @Test
    void createOrders_purchasableProduct_createsOrderAndDecrementsStock() {
        Product product = savePurchasableProduct(10);

        var result = orderService.createOrders(
                List.of(orderRequest(product.getId(), 3)),
                buyer.getId()
        );

        StepVerifier.create(result)
                .assertNext(ids -> assertThat(ids).hasSize(1))
                .verifyComplete();

        Product updated = productRepository.findById(product.getId()).block();
        assertThat(updated.getCount()).isEqualTo(7);
    }

    @Test
    void createOrders_setsInitialStatusToBooked() {
        Product product = savePurchasableProduct(5);

        List<Long> orderIds = orderService.createOrders(
                List.of(orderRequest(product.getId(), 1)),
                buyer.getId()
        ).block();

        assertThat(orderIds).hasSize(1);
        var order = orderRepository.findById(orderIds.get(0)).block();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.BOOKED);
        assertThat(order.getSellerId()).isEqualTo(seller.getId());
        assertThat(order.getCustomerId()).isEqualTo(buyer.getId());
    }

    @Test
    void createOrders_duplicateProductIds_mergesCount() {
        Product product = savePurchasableProduct(10);

        var result = orderService.createOrders(
                List.of(
                        orderRequest(product.getId(), 2),
                        orderRequest(product.getId(), 3)
                ),
                buyer.getId()
        );

        StepVerifier.create(result)
                .assertNext(ids -> assertThat(ids).hasSize(1))
                .verifyComplete();

        Product updated = productRepository.findById(product.getId()).block();
        assertThat(updated.getCount()).isEqualTo(5);
    }

    @Test
    void createOrders_insufficientStock_throwsError() {
        Product product = savePurchasableProduct(2);

        var result = orderService.createOrders(
                List.of(orderRequest(product.getId(), 10)),
                buyer.getId()
        );

        StepVerifier.create(result)
                .expectErrorSatisfies(error -> assertApiException(error, ErrorCode.OUT_OF_STOCK, "Недостаточно товара на складе"))
                .verify();

        Product unchanged = productRepository.findById(product.getId()).block();
        assertThat(unchanged.getCount()).isEqualTo(2);
    }

    @Test
    void createOrders_nonActiveProduct_throwsErrorAndDoesNotCreateOrder() {
        for (ProductStatus status : List.of(ProductStatus.TIME_EXPIRED, ProductStatus.BLOCKED, ProductStatus.DELETED)) {
            Product product = savePurchasableProduct(10, status);

            StepVerifier.create(orderService.createOrders(
                            List.of(orderRequest(product.getId(), 1)),
                            buyer.getId()
                    ))
                    .expectErrorSatisfies(error -> assertApiException(error, ErrorCode.PRODUCT_NOT_FOUND, "Товар не найден"))
                    .verify();
        }

        assertThat(orderRepository.count().block()).isZero();
    }

    @Test
    void createOrders_lastStockItem_decrementsToZeroAndRemovesBasketItem() {
        Product product = savePurchasableProduct(1);
        basketService.addToBasket(buyer.getId(), product.getId(), 1).block();

        StepVerifier.create(orderService.createOrders(
                        List.of(orderRequest(product.getId(), 1)),
                        buyer.getId()
                ))
                .assertNext(ids -> assertThat(ids).hasSize(1))
                .verifyComplete();

        Product updated = productRepository.findById(product.getId()).block();
        assertThat(updated.getCount()).isZero();
        assertThat(productBasketRepository.findByParticipantIdAndProductId(buyer.getId(), product.getId()).hasElement().block())
                .isFalse();
    }

    @Test
    void createOrders_basketCountExceedsCurrentStock_throwsErrorAndKeepsStock() {
        Product product = savePurchasableProduct(5);
        basketService.addToBasket(buyer.getId(), product.getId(), 5).block();
        updateProductCount(product.getId(), 2);

        StepVerifier.create(orderService.createOrders(
                        List.of(orderRequest(product.getId(), 5)),
                        buyer.getId()
                ))
                .expectErrorSatisfies(error -> assertApiException(error, ErrorCode.OUT_OF_STOCK, "Недостаточно товара на складе"))
                .verify();

        Product unchanged = productRepository.findById(product.getId()).block();
        assertThat(unchanged.getCount()).isEqualTo(2);
        assertThat(orderRepository.count().block()).isZero();
    }

    @Test
    void addToBasket_lastStockItem_doesNotDecrementStock() {
        Product product = savePurchasableProduct(1);

        basketService.addToBasket(buyer.getId(), product.getId(), 1).block();

        Product unchanged = productRepository.findById(product.getId()).block();
        assertThat(unchanged.getCount()).isEqualTo(1);
    }

    @Test
    void findBasketProducts_stockLessThanBasketCount_returnsAvailabilityMetadata() {
        Product product = savePurchasableProduct(5);
        basketService.addToBasket(buyer.getId(), product.getId(), 5).block();
        updateProductCount(product.getId(), 2);

        var basketItem = basketService.findBasketProductsByParams(buyer.getId(), basketRequest())
                .single()
                .block();

        assertThat(basketItem.getCount()).isEqualTo(5);
        assertThat(basketItem.getAvailableCount()).isEqualTo(2);
        assertThat(basketItem.getEnoughStock()).isFalse();
    }

    @Test
    void findBasketProducts_unlimitedStock_returnsEnoughStock() {
        Product product = savePurchasableProductWithNullableCount(null);
        basketService.addToBasket(buyer.getId(), product.getId(), 5).block();

        var basketItem = basketService.findBasketProductsByParams(buyer.getId(), basketRequest())
                .single()
                .block();

        assertThat(basketItem.getCount()).isEqualTo(5);
        assertThat(basketItem.getAvailableCount()).isNull();
        assertThat(basketItem.getEnoughStock()).isTrue();
    }

    @Test
    void createOrders_externalOnlyProduct_throwsError() {
        Product product = productRepository.save(
                Product.builder()
                        .name("External Product")
                        .description("desc")
                        .price(500f)
                        .currency(Currency.RUB)
                        .originality("Original")
                        .participantId(seller.getId())
                        .status(ProductStatus.ACTIVE)
                        .availability(ProductAvailabilityType.EXTERNAL_ONLY)
                        .externalUrl("https://example.com")
                        .expirationDate(Instant.now().plusSeconds(86400 * 30))
                        .createdAt(Instant.now())
                        .build()
        ).block();

        var result = orderService.createOrders(
                List.of(orderRequest(product.getId(), 1)),
                buyer.getId()
        );

        StepVerifier.create(result)
                .expectErrorSatisfies(error -> assertApiException(error, ErrorCode.PRODUCT_NOT_PURCHASABLE, "Нельзя заказать товар из смежного магазина"))
                .verify();
    }

    @Test
    void createOrders_buyerCannotOrderOwnProduct_throwsError() {
        // buyer is also the seller for this product
        Product ownProduct = productRepository.save(
                Product.builder()
                        .name("Own Product")
                        .description("desc")
                        .price(100f)
                        .currency(Currency.RUB)
                        .originality("Original")
                        .participantId(buyer.getId())
                        .status(ProductStatus.ACTIVE)
                        .availability(ProductAvailabilityType.PURCHASABLE)
                        .count(10)
                        .expirationDate(Instant.now().plusSeconds(86400 * 30))
                        .createdAt(Instant.now())
                        .build()
        ).block();

        // buyer needs their own transfer (as the seller of this product)
        Transfer buyerTransfer = transferRepository.save(
                Transfer.builder()
                        .sending(ShippingMethodsType.RUSSIAN_POST)
                        .price(100)
                        .currency(Currency.RUB)
                        .participantId(buyer.getId())
                        .status(TransferStatus.ACTIVE)
                        .build()
        ).block();

        var result = orderService.createOrders(
                List.of(CreateOrderRequest.builder()
                        .productId(ownProduct.getId())
                        .count(1)
                        .addressId(buyerAddress.getId())
                        .transferId(buyerTransfer.getId())
                        .build()),
                buyer.getId()
        );

        StepVerifier.create(result)
                .expectErrorSatisfies(error -> assertApiException(error, ErrorCode.OWN_PRODUCT_ORDER_FORBIDDEN, "Нельзя оформить заказ на собственный товар"))
                .verify();
    }

    @Test
    void getOrdersBySeller_returnsEnrichedOrders() {
        Product product = savePurchasableProduct(5);
        orderService.createOrders(List.of(orderRequest(product.getId(), 1)), buyer.getId()).block();

        StepVerifier.create(orderService.getOrdersBySeller(seller.getId()))
                .assertNext(response -> {
                    assertThat(response.getOrderId()).isNotNull();
                    assertThat(response.getProduct()).isNotNull();
                    assertThat(response.getUserInfo()).isNotNull();
                })
                .verifyComplete();
    }

    @Test
    void getOrdersByCustomer_returnsEnrichedOrders() {
        Product product = savePurchasableProduct(5);
        orderService.createOrders(List.of(orderRequest(product.getId(), 1)), buyer.getId()).block();

        StepVerifier.create(orderService.getOrdersByCustomer(buyer.getId()))
                .assertNext(response -> {
                    assertThat(response.getOrderId()).isNotNull();
                    assertThat(response.getProduct()).isNotNull();
                    assertThat(response.getUserInfo()).isNotNull();
                })
                .verifyComplete();
    }

    @Test
    void closureOrder_bookedPurchasableProduct_restoresStock() {
        Product product = savePurchasableProduct(5);
        Long orderId = orderService.createOrders(List.of(orderRequest(product.getId(), 2)), buyer.getId()).block().get(0);
        assertThat(productRepository.findById(product.getId()).block().getCount()).isEqualTo(3);

        orderService.closureOrder(closeRequest(orderId), buyer.getId()).block();

        Product restored = productRepository.findById(product.getId()).block();
        Order order = orderRepository.findById(orderId).block();
        assertThat(restored.getCount()).isEqualTo(5);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.FAILED);
    }

    @Test
    void closureOrder_awaitingPaymentPurchasableProduct_restoresStock() {
        Product product = savePurchasableProduct(5);
        Long orderId = orderService.createOrders(List.of(orderRequest(product.getId(), 2)), buyer.getId()).block().get(0);
        orderService.agreementOrder(orderId, "seller agrees", seller.getId()).block();

        orderService.closureOrder(closeRequest(orderId), buyer.getId()).block();

        Product restored = productRepository.findById(product.getId()).block();
        assertThat(restored.getCount()).isEqualTo(5);
    }

    @Test
    void closureOrder_preorderAwaitingPrepayment_doesNotRestoreUnchangedStock() {
        Product product = savePreorderProduct(500f);
        Long orderId = orderService.createOrders(List.of(orderRequest(product.getId(), 2)), buyer.getId()).block().get(0);
        orderService.agreementOrder(orderId, "seller agrees", seller.getId()).block();

        orderService.closureOrder(closeRequest(orderId), buyer.getId()).block();

        Product unchanged = productRepository.findById(product.getId()).block();
        assertThat(unchanged.getCount()).isNull();
    }

    @Test
    void closureOrder_preorderAwaitingPrepaymentApproval_doesNotRestoreUnchangedStock() {
        Product product = savePreorderProduct(500f);
        Long orderId = orderService.createOrders(List.of(orderRequest(product.getId(), 2)), buyer.getId()).block().get(0);
        orderService.agreementOrder(orderId, "seller agrees", seller.getId()).block();
        orderService.prepaymentOrder(orderId, saveOrderImage().getId(), "prepaid", buyer.getId()).block();

        orderService.closureOrder(closeRequest(orderId), buyer.getId()).block();

        Product unchanged = productRepository.findById(product.getId()).block();
        assertThat(unchanged.getCount()).isNull();
    }

    @Test
    void closureOrder_assemblingOrder_throwsErrorAndDoesNotRestoreStock() {
        Product product = savePurchasableProduct(5);
        Long orderId = orderService.createOrders(List.of(orderRequest(product.getId(), 2)), buyer.getId()).block().get(0);
        orderService.agreementOrder(orderId, "seller agrees", seller.getId()).block();
        orderService.paymentOrder(orderId, saveOrderImage().getId(), "paid", buyer.getId()).block();

        StepVerifier.create(orderService.closureOrder(closeRequest(orderId), buyer.getId()))
                .expectError()
                .verify();

        Product unchanged = productRepository.findById(product.getId()).block();
        Order order = orderRepository.findById(orderId).block();
        assertThat(unchanged.getCount()).isEqualTo(3);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.ASSEMBLING);
    }

    // --- helpers ---

    private void assertApiException(Throwable error, ErrorCode code, String message) {
        assertThat(error).isInstanceOf(ApiException.class);
        ApiException apiException = (ApiException) error;
        assertThat(apiException.getCode()).isEqualTo(code);
        assertThat(apiException.getMessage()).isEqualTo(message);
    }

    private Product savePurchasableProduct(int count) {
        return savePurchasableProduct(count, ProductStatus.ACTIVE);
    }

    private Product savePurchasableProduct(int count, ProductStatus status) {
        return productRepository.save(
                Product.builder()
                        .name("Test Product")
                        .description("desc")
                        .price(500f)
                        .currency(Currency.RUB)
                        .originality("Original")
                        .participantId(seller.getId())
                        .status(status)
                        .availability(ProductAvailabilityType.PURCHASABLE)
                        .count(count)
                        .expirationDate(Instant.now().plusSeconds(86400 * 30))
                        .createdAt(Instant.now())
                        .build()
        ).block();
    }

    private Product savePurchasableProductWithNullableCount(Integer count) {
        return productRepository.save(
                Product.builder()
                        .name("Unlimited Product")
                        .description("desc")
                        .price(500f)
                        .currency(Currency.RUB)
                        .originality("Original")
                        .participantId(seller.getId())
                        .status(ProductStatus.ACTIVE)
                        .availability(ProductAvailabilityType.PURCHASABLE)
                        .count(count)
                        .expirationDate(Instant.now().plusSeconds(86400 * 30))
                        .createdAt(Instant.now())
                        .build()
        ).block();
    }

    // ─── PREORDER tests ───────────────────────────────────────────────────────

    @Test
    void createOrders_preorderProduct_setsBookedStatusAndPrepaymentAmount() {
        Product product = savePreorderProduct(500f);

        List<Long> orderIds = orderService.createOrders(
                List.of(orderRequest(product.getId(), 1)),
                buyer.getId()
        ).block();

        assertThat(orderIds).hasSize(1);
        Order order = orderRepository.findById(orderIds.get(0)).block();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.BOOKED);
        assertThat(order.getPrepaymentAmount()).isEqualTo(500f);
    }

    @Test
    void agreementOrder_preorderWithPrepayment_transitionsToAwaitingPrepayment() {
        Product product = savePreorderProduct(500f);
        Long orderId = orderService.createOrders(
                List.of(orderRequest(product.getId(), 1)),
                buyer.getId()
        ).block().get(0);

        orderService.agreementOrder(orderId, "seller agrees", seller.getId()).block();

        Order order = orderRepository.findById(orderId).block();
        assertThat(order.getStatus()).isEqualTo(AWAITING_PREPAYMENT);
    }

    @Test
    void agreementOrder_purchasableProductNoPrepayment_transitionsToAwaitingPayment() {
        Product product = savePurchasableProduct(5);
        Long orderId = orderService.createOrders(
                List.of(orderRequest(product.getId(), 1)),
                buyer.getId()
        ).block().get(0);

        orderService.agreementOrder(orderId, "seller agrees", seller.getId()).block();

        Order order = orderRepository.findById(orderId).block();
        assertThat(order.getStatus()).isEqualTo(AWAITING_PAYMENT);
    }

    @Test
    void prepaymentOrder_buyerSubmitsProof_transitionsToAwaitingPrepaymentApproval() {
        Product product = savePreorderProduct(500f);
        Long orderId = orderService.createOrders(
                List.of(orderRequest(product.getId(), 1)),
                buyer.getId()
        ).block().get(0);
        orderService.agreementOrder(orderId, "agrees", seller.getId()).block();

        Image proof = saveOrderImage();
        orderService.prepaymentOrder(orderId, proof.getId(), "prepayment sent", buyer.getId()).block();

        Order order = orderRepository.findById(orderId).block();
        assertThat(order.getStatus()).isEqualTo(AWAITING_PREPAYMENT_APPROVAL);
        assertThat(order.getImagePaymentProofId()).isEqualTo(proof.getId());
    }

    @Test
    void sellerConfirmsPreorder_afterBuyerPrepayment_transitionsToAwaitingPayment() {
        Product product = savePreorderProduct(500f);
        Long orderId = orderService.createOrders(
                List.of(orderRequest(product.getId(), 1)),
                buyer.getId()
        ).block().get(0);
        orderService.agreementOrder(orderId, "agrees", seller.getId()).block();
        orderService.prepaymentOrder(orderId, saveOrderImage().getId(), "prepaid", buyer.getId()).block();

        orderService.sellerConfirmsPreorder(orderId, "prepayment confirmed", seller.getId()).block();

        Order order = orderRepository.findById(orderId).block();
        assertThat(order.getStatus()).isEqualTo(AWAITING_PAYMENT);
    }

    @Test
    void fullPreorderFlow_allStages_orderReachesAssembling() {
        Product product = savePreorderProduct(500f);
        Long orderId = orderService.createOrders(
                List.of(orderRequest(product.getId(), 1)),
                buyer.getId()
        ).block().get(0);

        // BOOKED → AWAITING_PREPAYMENT
        orderService.agreementOrder(orderId, "seller agrees", seller.getId()).block();
        assertThat(orderRepository.findById(orderId).block().getStatus()).isEqualTo(AWAITING_PREPAYMENT);

        // AWAITING_PREPAYMENT → AWAITING_PREPAYMENT_APPROVAL
        orderService.prepaymentOrder(orderId, saveOrderImage().getId(), "prepayment sent", buyer.getId()).block();
        assertThat(orderRepository.findById(orderId).block().getStatus()).isEqualTo(AWAITING_PREPAYMENT_APPROVAL);

        // AWAITING_PREPAYMENT_APPROVAL → AWAITING_PAYMENT
        orderService.sellerConfirmsPreorder(orderId, "prepayment confirmed", seller.getId()).block();
        assertThat(orderRepository.findById(orderId).block().getStatus()).isEqualTo(AWAITING_PAYMENT);

        // AWAITING_PAYMENT → ASSEMBLING
        orderService.paymentOrder(orderId, saveOrderImage().getId(), "full payment sent", buyer.getId()).block();
        assertThat(orderRepository.findById(orderId).block().getStatus()).isEqualTo(OrderStatus.ASSEMBLING);
    }

    @Test
    void prepaymentOrder_wrongParticipant_throwsError() {
        Product product = savePreorderProduct(500f);
        Long orderId = orderService.createOrders(
                List.of(orderRequest(product.getId(), 1)),
                buyer.getId()
        ).block().get(0);
        orderService.agreementOrder(orderId, "agrees", seller.getId()).block();

        // seller tries to submit prepayment proof instead of buyer
        StepVerifier.create(orderService.prepaymentOrder(orderId, saveOrderImage().getId(), "wrong", seller.getId()))
                .expectError()
                .verify();
    }

    @Test
    void sellerConfirmsPreorder_wrongParticipant_throwsError() {
        Product product = savePreorderProduct(500f);
        Long orderId = orderService.createOrders(
                List.of(orderRequest(product.getId(), 1)),
                buyer.getId()
        ).block().get(0);
        orderService.agreementOrder(orderId, "agrees", seller.getId()).block();
        orderService.prepaymentOrder(orderId, saveOrderImage().getId(), "prepaid", buyer.getId()).block();

        // buyer tries to confirm preorder instead of seller
        StepVerifier.create(orderService.sellerConfirmsPreorder(orderId, "wrong", buyer.getId()))
                .expectError()
                .verify();
    }

    // ─── helpers ──────────────────────────────────────────────────────────────

    private Product savePreorderProduct(Float prepaymentAmount) {
        return productRepository.save(
                Product.builder()
                        .name("Preorder Product")
                        .description("desc")
                        .price(1500f)
                        .prepaymentAmount(prepaymentAmount)
                        .currency(Currency.RUB)
                        .originality("Original")
                        .participantId(seller.getId())
                        .status(ProductStatus.ACTIVE)
                        .availability(ProductAvailabilityType.PREORDER)
                        .expirationDate(Instant.now().plusSeconds(86400 * 30))
                        .createdAt(Instant.now())
                        .build()
        ).block();
    }

    private Image saveOrderImage() {
        return imageRepository.save(
                Image.builder()
                        .filename("proof_" + System.nanoTime() + ".jpg")
                        .tag(ImageTag.ORDER)
                        .status(ImageStatus.TEMPORARY)
                        .contentType("image/jpeg")
                        .build()
        ).block();
    }

    private CreateOrderRequest orderRequest(Long productId, int count) {
        return CreateOrderRequest.builder()
                .productId(productId)
                .count(count)
                .addressId(buyerAddress.getId())
                .transferId(sellerTransfer.getId())
                .build();
    }

    private FindProductRequest basketRequest() {
        FindProductRequest request = new FindProductRequest();
        request.setPageable(new Pageable(50, null, null, 0L, SortByType.DATE_DESC));
        request.setIncludeAdult(false);
        return request;
    }

    private CloseOrderRequest closeRequest(Long orderId) {
        CloseOrderRequest request = new CloseOrderRequest();
        request.setOrderId(orderId);
        request.setComment("cancelled");
        return request;
    }

    private void updateProductCount(Long productId, int count) {
        databaseClient.sql("UPDATE product SET count = :count WHERE id = :id")
                .bind("count", count)
                .bind("id", productId)
                .fetch()
                .rowsUpdated()
                .block();
    }

    private Participant newParticipant(String prefix) {
        long nano = System.nanoTime();
        return Participant.builder()
                .login(prefix + "_" + nano)
                .mail(prefix + "_" + nano + "@test.com")
                .fullName("Test " + prefix)
                .phoneNumber("+79990001122")
                .status(ParticipantStatus.ACTIVE)
                .password("password123")
                .role(ParticipantRole.USER)
                .deadlineSending(3)
                .deadlinePayment(7)
                .sellerStatus(SellerStatus.DEFAULT)
                .createdAt(Instant.now())
                .build();
    }
}
