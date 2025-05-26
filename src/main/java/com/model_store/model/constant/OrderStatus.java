package com.model_store.model.constant;

public enum OrderStatus {
    /**
     * Забронирован
     */
    BOOKED,
    /**
     * Предоплачен
     */
    AWAITING_PREPAYMENT,
    /**
     * Ожидается согласие продавца на предоплату
     */
    AWAITING_PREPAYMENT_APPROVAL,

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