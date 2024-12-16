package com.model_store.model.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class FindParticipantsDto {
    private Long id;
    private String login;
    private String mail;
    private String fullName;
    private Long imageId;
}
