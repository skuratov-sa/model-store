package com.model_store.exception;

import com.model_store.exception.constant.EntityException;

public class EntityAlreadyExistException extends RuntimeException {
    private static final String MSG = "%s уже существует";

    public EntityAlreadyExistException(EntityException entity) {
        super(String.format(MSG, entity.getMessage()));
    }
}