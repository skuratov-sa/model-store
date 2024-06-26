package com.model_store.model.base;


import com.model_store.model.constant.Currency;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "product")
public class Product {

    @Id
    private Long id;
    private String name;
    private String description;
    private Double price;
    private Integer count;
    private Currency currency;
    private String originality;
    private Long participantId;

    @Column("createdat")
    private Instant createdAt;
}