package com.model_store.model.base;


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
@Table(name = "product_basket")
public class ProductBasket {

    @Id
    private Long id;
    private Long participantId;
    private Long productId;
    private Integer count;
}