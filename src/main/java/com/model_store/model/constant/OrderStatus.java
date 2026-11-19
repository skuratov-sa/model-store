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
    AWAITING_PAYMENT_APPROVAL,
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
    /** Отменён до начала оплаты или по решению администратора. */
    CANCELLED,
    /**
     * Заказ отклонён
     */
    FAILED
}
