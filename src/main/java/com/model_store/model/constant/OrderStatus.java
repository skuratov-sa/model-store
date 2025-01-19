package com.model_store.model.constant;

public enum OrderStatus {
    /**
     * Забронирован
     */
    BOOKED,
    /**
     * Ожидает оплаты
     */
    AWAITING_PAYMENT,
    /**
     * Заказ находится в процессе сборки
     */
    ASSEMBLING,
    /**
     * В пути следования
     */
    ON_THE_WAY,
    /**
     * Полученный
     */
    RECEIVED,
    /**
     * Ведется спор по заказу
     */
    DISPUTED,
    /**
     * Заказ успешно завершен
     */
    COMPLETED,
    /**
     * Заказ отклонён
     */
    FAILED
}