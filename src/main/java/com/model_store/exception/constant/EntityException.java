package com.model_store.exception.constant;

import lombok.Getter;

@Getter
public enum EntityException {
    IMAGE("Картинки"),
    BASKET("Корзины");

    private final String message;

    EntityException(String message) {
        this.message = message;
    }
}