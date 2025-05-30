package com.model_store.model.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CreateOrderRequest {
    /**
     * Идентификатор товара
     */
    private Long productId;
    /**
     * Количество товара
     */
    private Integer count;
    /**
     * Идентификатор адреса получения
     */
    private Long addressId;
    /**
     * Идентификатор cпособа отправки товара
     */
    private Long transferId;
    /**
     * Комментарий к заказу
     */
    private String comment;
}
