package com.model_store.mapper;

import com.model_store.model.CreateOrUpdateProductRequest;
import com.model_store.model.ReviewResponseDto;
import com.model_store.model.base.Product;
import com.model_store.model.constant.ProductAvailabilityType;
import com.model_store.model.constant.ProductStatus;
import com.model_store.model.dto.CategoryDto;
import com.model_store.model.dto.GetProductResponse;
import com.model_store.model.dto.ProductDto;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;
import java.util.Optional;

@Mapper(componentModel = "spring")
public interface ProductMapper {
    @Mapping(target = "id", ignore = true)
    Product toProduct(CreateOrUpdateProductRequest product, Long participantId, ProductStatus status);

    default Product updateProduct(CreateOrUpdateProductRequest productRequest, Product product) {
        var isPurchasable = productRequest.getAvailability().equals(ProductAvailabilityType.PURCHASABLE);
        var count = isPurchasable ? Optional.ofNullable(productRequest.getCount()).orElse(product.getCount()) : null;

        return Product.builder()
                .id(product.getId())
                .name(Optional.ofNullable(productRequest.getName()).orElse(product.getName()))
                .description(Optional.ofNullable(productRequest.getDescription()).orElse(product.getDescription()))
                .count(count)
                .price(Optional.ofNullable(productRequest.getPrice()).orElse(product.getPrice()))
                .prepaymentAmount(Optional.ofNullable(productRequest.getPrepaymentAmount()).orElse(product.getPrepaymentAmount()))
                .currency(Optional.ofNullable(productRequest.getCurrency()).orElse(product.getCurrency()))
                .originality(Optional.ofNullable(productRequest.getOriginality()).orElse(product.getOriginality()))
                .categoryId(Optional.ofNullable(productRequest.getCategoryId()).orElse(product.getCategoryId()))
                .availability(Optional.ofNullable(product.getAvailability()).orElse(product.getAvailability()))
                .externalUrl(Optional.ofNullable(product.getExternalUrl()).orElse(product.getExternalUrl()))
                .participantId(product.getParticipantId())
                .status(product.getStatus())
                .build();
    }

    @Mapping(target = "id", source = "product.id")
    @Mapping(target = "name", source = "product.name")
    @Mapping(target = "sellerId", source = "product.participantId")
    ProductDto toProductDto(Product product, CategoryDto category, Long imageId);

    @Mapping(target = "id", source = "product.id")
    @Mapping(target = "name", source = "product.name")
    @Mapping(target = "sellerId", source = "product.participantId")
    ProductDto toProductDto(Product product, Long imageId);

    @Mapping(target = "id", source = "product.id")
    @Mapping(target = "name", source = "product.name")
    GetProductResponse toGetProductResponse(Product product, CategoryDto category, List<Long> imageIds, List<ReviewResponseDto> reviews);
}