package com.model_store.mapper;

import com.model_store.model.CreateOrUpdateProductRequest;
import com.model_store.model.base.Product;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface ProductMapper {
    Product toProduct(CreateOrUpdateProductRequest product);
}