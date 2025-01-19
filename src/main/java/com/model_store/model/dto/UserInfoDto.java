package com.model_store.model.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class UserInfoDto {
    private Long id;
    private Long imageId;
    private String login;
    private String phoneNumber;
    private String mail;
}