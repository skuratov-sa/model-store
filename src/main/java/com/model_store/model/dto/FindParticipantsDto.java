package com.model_store.model.dto;

import com.model_store.model.constant.TransferMoneyType;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class FindParticipantsDto {
    /**
     * Идентификатор пользователя
     */
    private Long id;
    /**
     * Никнейм
     */
    private String login;
    /**
     * Страна проживания
     */
    private String country;
    /**
     * Город проживания
     */
    private String city;
    /**
     * Рейтинг среди других (0.00 - 5.00)
     */
    private Float rating;
    /**
     * Фото профиля
     */
    private Long imageId;
    /**
     * Стаж
     */
    private String experience;
    /**
     * Завершено покупок
     */
    private Integer orderCompletedCount;
    /**
     * Сделано покупок
     */
    private Integer orderPurchaseCount;
    /**
     * Крайний срок ожидания отправки
     */
    private Integer deadlineSending;
    /**
     * Крайний срок ожидания оплаты
     */
    private Integer deadlinePayment;
    /**
     * Способы оплаты
     */
    private List<TransferMoneyType> transferMoneys;
}
