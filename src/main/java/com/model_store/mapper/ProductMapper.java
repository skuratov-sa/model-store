package com.model_store.mapper;

import com.model_store.model.CreateOrUpdateProductRequest;
import com.model_store.model.base.Product;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.Optional;

@Mapper(componentModel = "spring")
public interface ProductMapper {
    @Mapping(target = "id", ignore = true)
    Product toProduct(CreateOrUpdateProductRequest product);

    default Product updateProduct(CreateOrUpdateProductRequest productRequest, Product product) {
        return Product.builder()
                .id(product.getId())
                .name(Optional.ofNullable(productRequest.getName()).orElse(product.getName()))
                .description(Optional.ofNullable(productRequest.getDescription()).orElse(product.getDescription()))
                .count(Optional.ofNullable(productRequest.getCount()).orElse(product.getCount()))
                .price(Optional.ofNullable(productRequest.getPrice()).orElse(product.getPrice()))
                .currency(Optional.ofNullable(productRequest.getCurrency()).orElse(product.getCurrency()))
                .originality(Optional.ofNullable(productRequest.getOriginality()).orElse(product.getOriginality()))
                .participantId(product.getParticipantId())
                .status(product.getStatus())
                .build();
    }
}