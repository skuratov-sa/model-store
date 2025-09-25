package com.model_store.model.base;


import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.model_store.model.constant.Currency;
import com.model_store.model.constant.ProductAvailabilityType;
import com.model_store.model.constant.ProductStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.time.LocalDateTime;

@Data
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "product")
public class Product {

    @Id
    private Long id;
    private String name;
    private String description;
    private Float price;
    private Float prepaymentAmount;
    private Integer count;
    private Currency currency;
    private String originality;
    private Long participantId;
    private ProductStatus status;
    private Instant expirationDate;
    private ProductAvailabilityType availability;
    private String externalUrl;

    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private Instant createdAt;
}