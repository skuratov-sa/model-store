package com.model_store.model.base;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.model_store.model.constant.ParticipantRole;
import com.model_store.model.constant.ParticipantStatus;
import com.model_store.model.constant.SellerStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.Objects;

@Data
@SuperBuilder
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

    private ParticipantRole role = ParticipantRole.USER;
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

    private SellerStatus sellerStatus = SellerStatus.DEFAULT;


    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Participant that)) return false;
        return Objects.equals(mail, that.mail);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(mail);
    }
}