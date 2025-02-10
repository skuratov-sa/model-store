package com.model_store.model.base;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.model_store.model.constant.ParticipantRole;
import com.model_store.model.constant.ParticipantStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "participant")
public class Participant {

    @Id
    private Long id;
    private String login;
    private String mail;
    private String fullName;
    private String phoneNumber;
    private ParticipantStatus status;

    private String password;
    private ParticipantRole role = ParticipantRole.USER; // Роль по умолчанию для участника
    /**
     * Крайний срок ожидания отправки
     */
    private Integer deadlineSending;
    /**
     * Крайний срок ожидания оплаты
     */
    private Integer deadlinePayment;

    @JsonFormat(pattern = "dd.MM.yyyy HH:mm:ss", timezone = "Europe/Moscow")
    private Instant createdAt;
}