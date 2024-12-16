package com.model_store.model.dto;

import com.model_store.model.constant.SocialNetworkType;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SocialNetworkDto {
    private SocialNetworkType type;
    private String login;
}
