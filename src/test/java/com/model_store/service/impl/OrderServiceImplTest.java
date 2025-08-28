package com.model_store.service.impl;

import com.model_store.mapper.OrderMapper;
import com.model_store.model.base.Address;
import com.model_store.model.base.Order;
import com.model_store.model.base.Product;
import com.model_store.model.base.Transfer;
import com.model_store.model.constant.ImageStatus;
import com.model_store.model.constant.ImageTag;
import com.model_store.model.constant.OrderStatus;
import com.model_store.model.dto.*;
import com.model_store.repository.OrderRepository;
import com.model_store.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;
@Deprecated
@ExtendWith(MockitoExtension.class)
class OrderServiceImplTest {

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private ProductService productService;
    @Mock
    private ParticipantService participantService;
    @Mock
    private OrderStatusHistoryService orderHistoryService;
    @Mock
    private TransferService transferService;
    @Mock
    private AddressService addressService;
    @Mock
    private OrderMapper orderMapper;
    @Mock
    private ImageService imageService;

    @InjectMocks
    private OrderServiceImpl orderService;

    private Product product;
    private Order order;
    private CreateOrderRequest createOrderRequest;
    private Address address;
    private Transfer transfer;

    @BeforeEach
    void setUp() {
        product = new Product();
        product.setId(1L);
        product.setParticipantId(10L); // Seller ID
        product.setCount(10);
        product.setPrice(100F);
        product.setName("Test Product");

        order = new Order();
        order.setId(1L);
        order.setProductId(1L);
        order.setSellerId(10L);
        order.setCustomerId(20L); // Customer ID
        order.setCount(2);
        order.setTotalPrice(100F);
        order.setStatus(OrderStatus.BOOKED);
        order.setAddressId(1L);
        order.setTransferId(1L);

        createOrderRequest = CreateOrderRequest.builder().build();
        createOrderRequest.setProductId(1L);
        createOrderRequest.setCount(2);
        createOrderRequest.setAddressId(1L);
        createOrderRequest.setTransferId(1L);

        address = new Address();
        address.setId(1L);

        transfer = new Transfer();
        transfer.setId(1L);
    }

    @Test
    void getRequiredDataForCreateOrder_shouldReturnData_whenProductExists() {
        Long participantId = 20L; // Customer
        Long productId = 1L;

        when(productService.findById(productId)).thenReturn(Mono.just(product)); // Product seller is 10L
        when(addressService.findByParticipantId(product.getParticipantId())).thenReturn(Flux.just(address));
        when(transferService.findByParticipantId(product.getParticipantId())).thenReturn(Flux.just(transfer));

        GetRequiredODataOrderDto expectedDto = GetRequiredODataOrderDto.builder()
                .addresses(List.of(address))
                .sellerTransfers(List.of(transfer))
                .build();

        StepVerifier.create(orderService.getRequiredDataForCreateOrder(participantId, productId))
                .expectNextMatches(dto -> dto.getAddresses().equals(expectedDto.getAddresses()) &&
                                          dto.getSellerTransfers().equals(expectedDto.getSellerTransfers()))
                .verifyComplete();

        verify(productService).findById(productId);
        verify(addressService).findByParticipantId(product.getParticipantId());
        verify(transferService).findByParticipantId(product.getParticipantId());
    }

    @Test
    void getRequiredDataForCreateOrder_shouldReturnEmptyLists_whenNoAddressesOrTransfers() {
        Long participantId = 20L;
        Long productId = 1L;

        when(productService.findById(productId)).thenReturn(Mono.just(product));
        when(addressService.findByParticipantId(product.getParticipantId())).thenReturn(Flux.empty()); // No addresses
        when(transferService.findByParticipantId(product.getParticipantId())).thenReturn(Flux.empty()); // No transfers

        GetRequiredODataOrderDto expectedDto = GetRequiredODataOrderDto.builder()
                .addresses(Collections.emptyList())
                .sellerTransfers(Collections.emptyList())
                .build();

        StepVerifier.create(orderService.getRequiredDataForCreateOrder(participantId, productId))
                .expectNext(expectedDto)
                .verifyComplete();
    }


    @Test
    void getRequiredDataForCreateOrder_shouldReturnEmpty_whenProductNotFound() {
        Long participantId = 20L;
        Long productId = 2L; // Non-existent product

        when(productService.findById(productId)).thenReturn(Mono.empty());
        // Because productMono is empty, flatMapMany for address and transfer won't be called with a product.
        // The defaultIfEmpty for collectList will trigger.

        GetRequiredODataOrderDto expectedDto = GetRequiredODataOrderDto.builder()
                .addresses(Collections.emptyList())
                .sellerTransfers(Collections.emptyList())
                .build();

        StepVerifier.create(orderService.getRequiredDataForCreateOrder(participantId, productId))
                .expectNext(expectedDto)
                .verifyComplete();

        verify(productService).findById(productId);
        verify(addressService, never()).findByParticipantId(anyLong());
        verify(transferService, never()).findByParticipantId(anyLong());
    }

    @Test
    void createOrder_shouldCreateOrderAndDecreaseProductCount_whenProductHasEnoughStock() {
        Long customerParticipantId = 20L;
        Product originalProduct = new Product();
        originalProduct.setId(1L);
        originalProduct.setParticipantId(10L); // Seller ID
        originalProduct.setCount(10);
        originalProduct.setPrice(100F);

        Order expectedOrder = new Order();
        expectedOrder.setId(5L); // Assume this ID is generated
        expectedOrder.setProductId(createOrderRequest.getProductId());
        expectedOrder.setCount(createOrderRequest.getCount());
        expectedOrder.setAddressId(createOrderRequest.getAddressId());
        expectedOrder.setTransferId(createOrderRequest.getTransferId());
        expectedOrder.setStatus(OrderStatus.BOOKED);
        expectedOrder.setSellerId(customerParticipantId); // This should be the customer in the request
        expectedOrder.setCustomerId(originalProduct.getParticipantId()); // This should be the seller of the product
        expectedOrder.setTotalPrice(originalProduct.getPrice() * createOrderRequest.getCount());


        when(productService.findById(createOrderRequest.getProductId())).thenReturn(Mono.just(originalProduct));
        when(orderMapper.toOrder(createOrderRequest)).thenReturn(expectedOrder); // Mapper maps request to a new order object
        when(productService.save(any(Product.class))).thenAnswer(invocation -> {
            Product savedProduct = invocation.getArgument(0);
            assertEquals(originalProduct.getCount() - createOrderRequest.getCount(), savedProduct.getCount());
            return Mono.just(savedProduct);
        });
        when(orderRepository.save(any(Order.class))).thenReturn(Mono.just(expectedOrder));


        StepVerifier.create(orderService.createOrder(createOrderRequest, customerParticipantId))
                .expectNext(expectedOrder.getId())
                .verifyComplete();

        verify(productService).findById(createOrderRequest.getProductId());
        verify(orderMapper).toOrder(createOrderRequest);
        verify(productService).save(any(Product.class));
        verify(orderRepository).save(any(Order.class));
    }

    @Test
    void createOrder_shouldFail_whenProductHasNotEnoughStock() {
        Long customerParticipantId = 20L;
        product.setCount(1); // Only 1 in stock
        createOrderRequest.setCount(2); // Requesting 2

        when(productService.findById(createOrderRequest.getProductId())).thenReturn(Mono.just(product));

        StepVerifier.create(orderService.createOrder(createOrderRequest, customerParticipantId))
                .expectErrorMatches(throwable -> throwable instanceof RuntimeException &&
                                                 "Данный товар закончился".equals(throwable.getMessage()))
                .verify();

        verify(productService).findById(createOrderRequest.getProductId());
        verify(orderRepository, never()).save(any(Order.class));
        verify(productService, never()).save(any(Product.class));
    }

    @Test
    void createOrder_shouldFail_whenProductNotFound() {
        Long customerParticipantId = 20L;
        when(productService.findById(createOrderRequest.getProductId())).thenReturn(Mono.empty());

        StepVerifier.create(orderService.createOrder(createOrderRequest, customerParticipantId))
                .expectErrorMatches(throwable -> throwable instanceof RuntimeException &&
                                                 "Данный товар закончился".equals(throwable.getMessage())) // Error from switchIfEmpty
                .verify();

        verify(productService).findById(createOrderRequest.getProductId());
        verify(orderRepository, never()).save(any(Order.class));
    }

    @Test
    void updateStatusOrder_shouldUpdateOrderStatus_whenOrderExists() {
        UpdateOrderRequest updateRequest = UpdateOrderRequest.builder().build();
        updateRequest.setOrderId(1L);
        updateRequest.setOrderStatus(OrderStatus.ASSEMBLING);

        Order foundOrder = new Order(); // Simulate the order found in DB
        foundOrder.setId(1L);
        foundOrder.setStatus(OrderStatus.BOOKED); // Initial status

        Order savedOrder = new Order(); // Simulate the order after save
        savedOrder.setId(1L);
        savedOrder.setStatus(OrderStatus.ASSEMBLING); // New status

        when(orderRepository.findById(updateRequest.getOrderId())).thenReturn(Mono.just(foundOrder));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> {
            Order orderToSave = invocation.getArgument(0);
            if (orderToSave.getId().equals(updateRequest.getOrderId()) && orderToSave.getStatus().equals(updateRequest.getOrderStatus())) {
                return Mono.just(savedOrder);
            }
            return Mono.error(new AssertionError("Save condition not met"));
        });

        StepVerifier.create(orderService.updateStatusOrder(updateRequest))
                .expectNext(savedOrder.getId())
                .verifyComplete();

        verify(orderRepository).findById(updateRequest.getOrderId());
        verify(orderRepository).save(any(Order.class));
    }

    @Test
    void updateStatusOrder_shouldFail_whenOrderDoesNotExist() {
        UpdateOrderRequest updateRequest = UpdateOrderRequest.builder().build();
        updateRequest.setOrderId(2L); // Non-existent order
        updateRequest.setOrderStatus(OrderStatus.ASSEMBLING);

        when(orderRepository.findById(updateRequest.getOrderId())).thenReturn(Mono.empty());

        StepVerifier.create(orderService.updateStatusOrder(updateRequest))
                .verifyComplete(); // Completes without error as per current implementation (doOnNext, flatMap)

        verify(orderRepository).findById(updateRequest.getOrderId());
        verify(orderRepository, never()).save(any(Order.class));
    }

//    @Test
//    void getOrdersBySeller_shouldReturnEnrichedOrders() {
//        Long sellerId = 10L;
//        FindOrderResponse findOrderResponse = FindOrderResponse.builder().build();
//        findOrderResponse.setOrderId(order.getId());
//        findOrderResponse.setUserInfo(new UserInfoDto(order.getCustomerId(), 1, "login","89999999","mail")); // Customer info
//        findOrderResponse.setProduct(new ProductDto(order.getProductId(), "name",  11, 100F, Currency.RUB, null, 1L, 1L));
//        findOrderResponse.setTransfer(new OrderTransferDto(order.getTransferId(), order.getAddressId(), null, null, null, Currency.RUB));
//
//
//        UserInfoDto enrichedUser = new UserInfoDto(order.getCustomerId(), 1L,"login", "899999999", "mail", "Customer Name", "customer.jpg");
//        ProductDto enrichedProduct = new ProductDto(order.getProductId(), "Product Name", "product.jpg", order.getCount(), BigDecimal.ONE, null, null);
//        OrderTransferDto enrichedTransfer = new OrderTransferDto(order.getTransferId(), order.getAddressId(), "Transfer Name", "Full Address", "transfer.jpg");
//        OrderStatusHistoryDto historyDto = new OrderStatusHistoryDto(OrderStatus.BOOKED, null, null);
//
//        when(orderRepository.findBySellerId(sellerId)).thenReturn(Flux.just(order));
//        when(orderMapper.toFindOrderResponseBySeller(order)).thenReturn(findOrderResponse);
//
//        // Mocking the enrichment chain
//        when(imageService.findActualImages(order.getId(), ImageTag.ORDER)).thenReturn(Flux.just(1L, 2L));
//        when(orderHistoryService.findByOrderId(order.getId())).thenReturn(Flux.just(new OrderStatusHistory()));
//        when(orderMapper.toOrderStatusHistoryDto(any(OrderStatusHistory.class))).thenReturn(historyDto);
//        when(participantService.findShortInfo(order.getCustomerId())).thenReturn(Mono.just(enrichedUser));
//        when(productService.shortInfoById(order.getProductId())).thenReturn(Mono.just(enrichedProduct));
//        when(transferService.findById(order.getTransferId())).thenReturn(Mono.just(transfer)); // 'transfer' is from setUp
//        when(addressService.findById(order.getAddressId())).thenReturn(Mono.just(address));   // 'address' is from setUp
//        when(orderMapper.toOrderTransferDto(any(), any(), any(), any())).thenReturn(enrichedTransfer);
//
//
//        StepVerifier.create(orderService.getOrdersBySeller(sellerId))
//                .assertNext(response -> {
//                    assertEquals(order.getId(), response.getOrderId());
//                    assertEquals(enrichedUser, response.getUserInfo());
//                    assertEquals(enrichedProduct, response.getProduct());
//                    assertEquals(enrichedTransfer, response.getTransfer());
//                    assertEquals(List.of(1L, 2L), response.getImages());
//                    assertEquals(1, response.getHistories().size());
//                    assertEquals(historyDto, response.getHistories().get(0));
//                })
//                .verifyComplete();
//
//        verify(orderRepository).findBySellerId(sellerId);
//        verify(imageService).findActualImages(order.getId(), ImageTag.ORDER);
//        // ... other verify calls for chained methods
//    }
//
//    @Test
//    void getOrdersByCustomer_shouldReturnEnrichedOrders() {
//        Long customerId = 20L;
//        FindOrderResponse findOrderResponse = new FindOrderResponse();
//        findOrderResponse.setOrderId(order.getId());
//        findOrderResponse.setUserInfo(new UserShortDto(order.getSellerId(), null, null)); // Seller info
//        findOrderResponse.setProduct(new ProductShortDto(order.getProductId(), null, null, order.getCount(), null, null, null));
//        findOrderResponse.setTransfer(new OrderTransferDto(order.getTransferId(), order.getAddressId(), null, null, null));
//
//        UserShortDto enrichedUser = new UserShortDto(order.getSellerId(), "Seller Name", "seller.jpg");
//        ProductShortDto enrichedProduct = new ProductShortDto(order.getProductId(), "Product Name", "product.jpg", order.getCount(), BigDecimal.ONE, null, null);
//        OrderTransferDto enrichedTransfer = new OrderTransferDto(order.getTransferId(), order.getAddressId(), "Transfer Name", "Full Address", "transfer.jpg");
//        OrderStatusHistoryDto historyDto = new OrderStatusHistoryDto(OrderStatus.BOOKED, null, null);
//
//
//        when(orderRepository.findByCustomerId(customerId)).thenReturn(Flux.just(order));
//        when(orderMapper.toFindOrderResponseByCustomer(order)).thenReturn(findOrderResponse);
//
//        // Mocking the enrichment chain
//        when(imageService.findActualImages(order.getId(), ImageTag.ORDER)).thenReturn(Flux.just(3L));
//        when(orderHistoryService.findByOrderId(order.getId())).thenReturn(Flux.just(new OrderStatusHistory()));
//        when(orderMapper.toOrderStatusHistoryDto(any(OrderStatusHistory.class))).thenReturn(historyDto);
//        when(participantService.findShortInfo(order.getSellerId())).thenReturn(Mono.just(enrichedUser));
//        when(productService.shortInfoById(order.getProductId())).thenReturn(Mono.just(enrichedProduct));
//        when(transferService.findById(order.getTransferId())).thenReturn(Mono.just(transfer));
//        when(addressService.findById(order.getAddressId())).thenReturn(Mono.just(address));
//        when(orderMapper.toOrderTransferDto(any(), any(), any(), any())).thenReturn(enrichedTransfer);
//
//        StepVerifier.create(orderService.getOrdersByCustomer(customerId))
//                .assertNext(response -> {
//                    assertEquals(order.getId(), response.getOrderId());
//                    assertEquals(enrichedUser, response.getUserInfo());
//                    // Add more assertions for other enriched fields
//                })
//                .verifyComplete();
//        verify(orderRepository).findByCustomerId(customerId);
//    }

    @Test
    void agreementOrder_shouldUpdateStatusAndDetails_whenOrderIsBooked() {
        Long orderId = 1L;
        Long accountId = 100L;
        String comment = "Agreement comment";
        order.setStatus(OrderStatus.BOOKED); // Ensure initial status is BOOKED

        Order savedOrder = new Order();
        savedOrder.setId(orderId);
        savedOrder.setAccountId(accountId);
        savedOrder.setComment(comment);
        savedOrder.setStatus(OrderStatus.AWAITING_PAYMENT);


        when(orderRepository.findById(orderId)).thenReturn(Mono.just(order));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> {
            Order o = invocation.getArgument(0);
            if (o.getId().equals(orderId) &&
                o.getAccountId().equals(accountId) &&
                o.getComment().equals(comment) &&
                o.getStatus().equals(OrderStatus.AWAITING_PAYMENT)) {
                return Mono.just(savedOrder);
            }
            return Mono.error(new AssertionError("Save conditions not met for agreementOrder"));
        });

        StepVerifier.create(orderService.agreementOrder(orderId, comment, 1L))
                .expectNext(savedOrder.getId())
                .verifyComplete();

        verify(orderRepository).findById(orderId);
        verify(orderRepository).save(any(Order.class));
    }

    @Test
    void agreementOrder_shouldFail_whenOrderIsNotBooked() {
        Long orderId = 1L;
        Long accountId = 100L;
        String comment = "Agreement comment";
        order.setStatus(OrderStatus.ASSEMBLING); // Not BOOKED

        when(orderRepository.findById(orderId)).thenReturn(Mono.just(order));

        StepVerifier.create(orderService.agreementOrder(orderId, comment, 1L))
                .expectError(com.amazonaws.services.kms.model.NotFoundException.class)
                .verify();

        verify(orderRepository).findById(orderId);
        verify(orderRepository, never()).save(any(Order.class));
    }

    @Test
    void paymentOrder_shouldUpdateStatusAndDetails_whenOrderIsAwaitingPayment() {
        Long orderId = 1L;
        Long imageId = 123L;
        String comment = "Payment comment";
        order.setStatus(OrderStatus.AWAITING_PAYMENT);

        Order savedOrder = new Order();
        savedOrder.setId(orderId);
        savedOrder.setImagePaymentProofId(imageId);
        savedOrder.setComment(comment);
        savedOrder.setStatus(OrderStatus.ASSEMBLING);

        when(orderRepository.findById(orderId)).thenReturn(Mono.just(order));
        when(imageService.updateImagesStatus(List.of(imageId), orderId, ImageStatus.ACTIVE, ImageTag.ORDER))
                .thenReturn(Mono.empty());
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> {
             Order o = invocation.getArgument(0);
            if (o.getId().equals(orderId) &&
                o.getImagePaymentProofId().equals(imageId) &&
                o.getComment().equals(comment) &&
                o.getStatus().equals(OrderStatus.ASSEMBLING)) {
                return Mono.just(savedOrder);
            }
            return Mono.error(new AssertionError("Save conditions not met for paymentOrder"));
        });

        StepVerifier.create(orderService.paymentOrder(orderId, imageId, comment, 1L))
                .expectNext(savedOrder.getId())
                .verifyComplete();

        verify(orderRepository).findById(orderId);
        verify(imageService).updateImagesStatus(List.of(imageId), orderId, ImageStatus.ACTIVE, ImageTag.ORDER);
        verify(orderRepository).save(any(Order.class));
    }

    @Test
    void transferOrder_shouldUpdateStatusAndDetails_whenOrderIsAssembling() {
        Long orderId = 1L;
        String deliveryUrl = "http://example.com/track";
        String comment = "Transfer comment";
        order.setStatus(OrderStatus.ASSEMBLING);

        Order savedOrder = new Order();
        // Populate savedOrder similar to other tests

        when(orderRepository.findById(orderId)).thenReturn(Mono.just(order));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> {
            Order o = invocation.getArgument(0);
            if (o.getDeliveryUrl().equals(deliveryUrl) && o.getComment().equals(comment) && o.getStatus().equals(OrderStatus.ON_THE_WAY)){
                 return Mono.just(o); // Return the modified order itself for simplicity
            }
            return Mono.error(new AssertionError("Save conditions not met for transferOrder"));
        });

        StepVerifier.create(orderService.transferOrder(orderId, deliveryUrl, comment, 1L))
                .expectNext(order.getId())
                .verifyComplete();
        verify(orderRepository).save(any(Order.class));
    }

    @Test
    void deliveredOrder_shouldUpdateStatusAndComment_whenOrderIsOnTheWay() {
        Long orderId = 1L;
        String comment = "Delivered comment";
        order.setStatus(OrderStatus.ON_THE_WAY);

        when(orderRepository.findById(orderId)).thenReturn(Mono.just(order));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> {
            Order o = invocation.getArgument(0);
            if (o.getComment().equals(comment) && o.getStatus().equals(OrderStatus.COMPLETED)){
                 return Mono.just(o);
            }
            return Mono.error(new AssertionError("Save conditions not met for deliveredOrder"));
        });

        StepVerifier.create(orderService.deliveredOrder(orderId, comment, 1L))
                .expectNext(order.getId())
                .verifyComplete();
        verify(orderRepository).save(any(Order.class));
    }

    @Test
    void openDisputeForOrder_shouldUpdateStatusAndActivateImages_whenOrderIsOnTheWay() {
        Long orderId = 1L;
        List<Long> imageIds = List.of(10L, 11L);
        String comment = "Dispute reason";
        order.setStatus(OrderStatus.ON_THE_WAY);

        when(orderRepository.findById(orderId)).thenReturn(Mono.just(order));
        when(imageService.updateImagesStatus(imageIds, orderId, ImageStatus.ACTIVE, ImageTag.ORDER))
                .thenReturn(Mono.empty());
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> {
            Order o = invocation.getArgument(0);
            if (o.getComment().equals(comment) && o.getStatus().equals(OrderStatus.DISPUTED)){
                 return Mono.just(o);
            }
            return Mono.error(new AssertionError("Save conditions not met for openDisputeForOrder"));
        });

        StepVerifier.create(orderService.openDisputeForOrder(orderId, imageIds, comment, 1L))
                .expectNext(order.getId())
                .verifyComplete();
        verify(imageService).updateImagesStatus(imageIds, orderId, ImageStatus.ACTIVE, ImageTag.ORDER);
        verify(orderRepository).save(any(Order.class));
    }

    @Test
    void closeDisputeForOrder_shouldUpdateStatusAndActivateImages_whenOrderIsDisputed() {
        Long orderId = 1L;
        List<Long> imageIds = List.of(12L);
        String comment = "Dispute resolved";
        order.setStatus(OrderStatus.DISPUTED);

        when(orderRepository.findById(orderId)).thenReturn(Mono.just(order));
        when(imageService.updateImagesStatus(imageIds, orderId, ImageStatus.ACTIVE, ImageTag.ORDER))
                .thenReturn(Mono.empty());
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> {
            Order o = invocation.getArgument(0);
            if (o.getComment().equals(comment) && o.getStatus().equals(OrderStatus.COMPLETED)){
                 return Mono.just(o);
            }
            return Mono.error(new AssertionError("Save conditions not met for closeDisputeForOrder"));
        });

        StepVerifier.create(orderService.closeDisputeForOrder(orderId, imageIds, comment, 1L))
                .expectNext(order.getId())
                .verifyComplete();
        verify(imageService).updateImagesStatus(imageIds, orderId, ImageStatus.ACTIVE, ImageTag.ORDER);
        verify(orderRepository).save(any(Order.class));
    }

    @Test
    void findCompletedCountBySellerId_shouldReturnCount() {
        Long sellerId = 10L;
        Integer expectedCount = 5;
        when(orderRepository.findCompletedCountBySellerId(sellerId)).thenReturn(Mono.just(expectedCount));

        StepVerifier.create(orderService.findCompletedCountBySellerId(sellerId))
                .expectNext(expectedCount)
                .verifyComplete();
        verify(orderRepository).findCompletedCountBySellerId(sellerId);
    }

    @Test
    void findById_shouldReturnOrder_whenOrderExists() {
        Long orderId = 1L;
        when(orderRepository.findById(orderId)).thenReturn(Mono.just(order)); // 'order' from setUp

        StepVerifier.create(orderService.findById(orderId))
                .expectNext(order)
                .verifyComplete();
        verify(orderRepository).findById(orderId);
    }

    @Test
    void findById_shouldReturnEmpty_whenOrderDoesNotExist() {
        Long orderId = 99L; // Non-existent
        when(orderRepository.findById(orderId)).thenReturn(Mono.empty());

        StepVerifier.create(orderService.findById(orderId))
                .verifyComplete();
        verify(orderRepository).findById(orderId);
    }

    @Test
    void findCompletedCountByCustomerId_shouldReturnCount() {
        Long customerId = 20L;
        Integer expectedCount = 3;
        when(orderRepository.findCompletedCountByCustomerId(customerId)).thenReturn(Mono.just(expectedCount));

        StepVerifier.create(orderService.findCompletedCountByCustomerId(customerId))
                .expectNext(expectedCount)
                .verifyComplete();
        verify(orderRepository).findCompletedCountByCustomerId(customerId);
    }
}
