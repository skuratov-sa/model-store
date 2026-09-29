package com.model_store.model.base;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.model_store.model.constant.OrderStatus;
import com.model_store.model.constant.Currency;
import com.model_store.model.constant.ProductAvailabilityType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "\"order\"")
public class Order {
    /**
     * Идентификатор заказа
     */
    @Id
    private Long id;
    /**
     * Идентификатор продавца
     */
    private Long sellerId;
    /**
     * Идентификатор покупателя
     */
    private Long customerId;
    /**
     * Количество товара
     */
    private Integer count;
    /**
     * Актуальный статус заказа
     */
    private OrderStatus status;
    /**
     * Идентификатор товара
     */
    private Long productId;
    /** Название и условия продажи товара на момент оформления заказа. */
    private String productName;
    private Float productUnitPrice;
    private Currency productCurrency;
    private ProductAvailabilityType productAvailability;
    /**
     * Идентификатор адреса получения
     */
    private Long addressId;
    /**
     * Идентификатор cпособа отправки товара
     */
    private Long transferId;
    /**
     * Id картинки подтверждающей оплату
     */
    private Long imagePaymentProofId;
    /**
     * Цена за товары
     */
    private Float totalPrice;
    /**
     * Сумма предоплаты
     */
    private Float prepaymentAmount;
    /**
     * Ссылка для отслеживания товара
     */
    private String deliveryUrl;
    /**
     * Комментарий к заказу
     */
    private String comment;
    /**
     * Дата создания
     */
    @JsonFormat(pattern = "dd.MM.yyyy HH:mm:ss", timezone = "Europe/Moscow")
    private Instant createdAt;
}
