package com.model_store.model.base;


import com.model_store.model.constant.Currency;
import com.model_store.model.constant.ProductStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

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
    private Float price;
    private Integer count;
    private Currency currency;
    private String originality;
    private Long participantId;
    private Long categoryId;
    private ProductStatus status;

//    @JsonFormat(pattern = "dd.MM.yyyy HH:mm:ss", timezone = "Europe/Moscow")
//    private Instant createdAt;
}