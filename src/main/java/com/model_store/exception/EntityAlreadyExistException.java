package com.model_store.exception;

public class EntityAlreadyExistException extends RuntimeException {
    private static final String MSG = "Товар в корзине уже существует";

    public EntityAlreadyExistException() {
        super(MSG);
    }
}