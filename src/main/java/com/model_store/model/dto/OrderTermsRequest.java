package com.model_store.model.dto;

import java.math.BigDecimal;

/** Amounts are decimal strings in major currency units, scale 2. Null delivery is unknown. */
public record OrderTermsRequest(Long expectedVersion, String carrier, String address,
                               BigDecimal deliveryAmount, String reason) { }
