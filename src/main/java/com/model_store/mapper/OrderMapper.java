package com.model_store.mapper;

import com.model_store.model.base.Order;
import com.model_store.model.base.OrderStatusHistory;
import com.model_store.model.base.Transfer;
import com.model_store.model.dto.CreateOrderRequest;
import com.model_store.model.dto.FindOrderResponse;
import com.model_store.model.dto.GetProductResponse;
import com.model_store.model.dto.OrderStatusHistoryDto;
import com.model_store.model.dto.OrderTransferDto;
import com.model_store.model.dto.ProductDto;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface OrderMapper {
    Order toOrder(CreateOrderRequest request);

    @Mapping(target = "orderId", source = "id")
    @Mapping(target = "actualStatus", source = "status")
    @Mapping(target = "userInfo.id", source = "customerId")
    @Mapping(target = "product.id", source = "productId")
    @Mapping(target = "product.count", source = "count")
    @Mapping(target = "transfer.transferId", source = "transferId")
    @Mapping(target = "transfer.addressId", source = "addressId")
    FindOrderResponse toFindOrderResponseBySeller(Order order);

    @Mapping(target = "orderId", source = "id")
    @Mapping(target = "actualStatus", source = "status")
    @Mapping(target = "userInfo.id", source = "sellerId")
    @Mapping(target = "product.id", source = "productId")
    @Mapping(target = "product.count", source = "count")
    @Mapping(target = "transfer.transferId", source = "transferId")
    @Mapping(target = "transfer.addressId", source = "addressId")
    FindOrderResponse toFindOrderResponseByCustomer(Order order);

    ProductDto toProductDto(GetProductResponse response);

    @Mapping(target = "transferId", source = "transfer.id")
    OrderTransferDto toOrderTransferDto(Transfer transfer, String address, Long addressId, Long imageId);

    OrderStatusHistoryDto toOrderStatusHistoryDto(OrderStatusHistory orderStatusHistory);
}
