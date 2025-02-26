package com.model_store.exception;

import com.amazonaws.services.kms.model.NotFoundException;
import com.model_store.exception.constant.EntityException;

public class EntityNotFoundException extends NotFoundException {
    private static final String MESSAGE = "%s c id %d не были найдены";
    private static final String MESSAGE_SIMPLE = "%s не были найдены";

    public EntityNotFoundException(EntityException entity, Long id) {
        super(String.format(MESSAGE, entity.getMessage(), id));
    }

    public EntityNotFoundException(EntityException entity) {
        super(String.format(MESSAGE_SIMPLE, entity.getMessage()));
    }
}