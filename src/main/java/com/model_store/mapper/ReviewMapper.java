package com.model_store.mapper;

import com.model_store.model.ReviewRequestDto;
import com.model_store.model.ReviewResponseDto;
import com.model_store.model.base.Order;
import com.model_store.model.base.Review;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface ReviewMapper {

    @Mapping(target = "comment", source = "dto.comment")
    Review toReview(ReviewRequestDto dto, Order order);

    ReviewResponseDto toReviewResponseDto(Review review, String reviewerName);
}
