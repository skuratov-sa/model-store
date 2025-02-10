package com.model_store.service.util;

import com.model_store.model.constant.ShippingMethodsType;
import lombok.experimental.UtilityClass;

import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneId;

@UtilityClass
public class UtilService {

    public static Long getImageId(ShippingMethodsType type) {
        return switch (type) {
            case RUSSIAN_POST -> 1L;
            case PRODUCT_PICKUP -> 2L;
            case TRANSPORT_COMPANY -> 3L;
            case FREE_POST -> 4L;
        };
    }

    public static String getExpensive(Instant createdAt) {
        if (createdAt != null) {
            LocalDate createdDate = createdAt.atZone(ZoneId.systemDefault()).toLocalDate();
            LocalDate currentDate = LocalDate.now();
            Period period = Period.between(createdDate, currentDate);

            int years = period.getYears();
            int months = period.getMonths();

            if (years == 0 && months == 0) {
                return "Меньше месяца";
            } else if (years == 0) {
                return months + " " + getMonthsLabel(months);
            }

            // Универсальная строка стажа
            return String.format("%d %s и %d %s",
                    years, getYearsLabel(years),
                    months, getMonthsLabel(months));

        } else {
            return "Нет опыта";
        }
    }

    // Метод для определения правильного склонения слова "год"
    private String getYearsLabel(int years) {
        if (years % 10 == 1 && years % 100 != 11) {
            return "год";
        } else if (years % 10 >= 2 && years % 10 <= 4 && (years % 100 < 10 || years % 100 >= 20)) {
            return "года";
        } else {
            return "лет";
        }
    }

    // Метод для определения правильного склонения слова "месяц"
    private String getMonthsLabel(int months) {
        if (months == 1) {
            return "месяц";
        } else if (months >= 2 && months <= 4) {
            return "месяца";
        } else {
            return "месяцев";
        }
    }
}
