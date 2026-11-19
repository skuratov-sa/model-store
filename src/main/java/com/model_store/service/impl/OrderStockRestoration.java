package com.model_store.service.impl;

import com.model_store.model.base.Order;
import com.model_store.model.constant.ProductAvailabilityType;
import com.model_store.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
@RequiredArgsConstructor
class OrderStockRestoration {
    private final ProductService products;

    Mono<Void> restore(Order order) {
        if (Boolean.FALSE.equals(order.getStockReserved())
                || (order.getStockReserved() == null && order.getProductAvailability() != null
                    && order.getProductAvailability() != ProductAvailabilityType.PURCHASABLE)) {
            return Mono.empty();
        }
        return products.findByIdForUpdate(order.getProductId())
                .filter(product -> product.getCount() != null)
                .filter(product -> Boolean.TRUE.equals(order.getStockReserved())
                        || order.getProductAvailability() == ProductAvailabilityType.PURCHASABLE
                        || product.getAvailability() == ProductAvailabilityType.PURCHASABLE)
                .flatMap(product -> products.incrementCountIfLimited(product.getId(), order.getCount()))
                .then();
    }
}
