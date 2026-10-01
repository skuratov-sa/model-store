package com.model_store.model;

import lombok.Data;

import java.time.Instant;

@Data
public class GiveawaySettingsRequest {
    private Boolean enabled;
    private String telegramUrl;
    private Instant startAt;
    private Instant endAt;
    private Integer winnersCount;
    private String rules;
    private String homeText;
}
