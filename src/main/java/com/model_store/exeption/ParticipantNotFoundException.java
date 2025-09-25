package com.model_store.exeption;

import java.text.MessageFormat;

public class ParticipantNotFoundException extends RuntimeException {
    public static final String MSG = "Пользователя с id = {0} не существует.";

    public ParticipantNotFoundException(Long participantId) {
        super(MessageFormat.format(MSG, participantId));
    }
}