package com.model_store.validation;

import com.model_store.model.ReviewRequestDto;
import com.model_store.model.constant.SortByType;
import com.model_store.model.page.Pageable;
import com.model_store.model.util.PriceRange;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RequestValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    // --- PriceRange ---

    @Test
    void priceRange_negativeMinPrice_failsValidation() {
        PriceRange pr = new PriceRange();
        pr.setMinPrice(-1);
        pr.setMaxPrice(100);

        Set<ConstraintViolation<PriceRange>> violations = validator.validate(pr);

        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("minPrice"));
    }

    @Test
    void priceRange_negativeMaxPrice_failsValidation() {
        PriceRange pr = new PriceRange();
        pr.setMinPrice(0);
        pr.setMaxPrice(-5);

        Set<ConstraintViolation<PriceRange>> violations = validator.validate(pr);

        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("maxPrice"));
    }

    @Test
    void priceRange_zeroValues_passesValidation() {
        PriceRange pr = new PriceRange();
        pr.setMinPrice(0);
        pr.setMaxPrice(0);

        Set<ConstraintViolation<PriceRange>> violations = validator.validate(pr);

        assertThat(violations).isEmpty();
    }

    @Test
    void priceRange_positiveValues_passesValidation() {
        PriceRange pr = new PriceRange();
        pr.setMinPrice(100);
        pr.setMaxPrice(500);

        Set<ConstraintViolation<PriceRange>> violations = validator.validate(pr);

        assertThat(violations).isEmpty();
    }

    // --- Pageable ---

    @Test
    void pageable_negativeSize_failsValidation() {
        Pageable p = new Pageable(-1, null, null, null, SortByType.DATE_DESC);

        Set<ConstraintViolation<Pageable>> violations = validator.validate(p);

        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("size"));
    }

    @Test
    void pageable_zeroSize_passesValidation() {
        Pageable p = new Pageable(0, null, null, null, SortByType.DATE_DESC);

        Set<ConstraintViolation<Pageable>> violations = validator.validate(p);

        assertThat(violations).isEmpty();
    }

    @Test
    void pageable_positiveSize_passesValidation() {
        Pageable p = new Pageable(20, null, null, 0L, SortByType.DATE_DESC);

        Set<ConstraintViolation<Pageable>> violations = validator.validate(p);

        assertThat(violations).isEmpty();
    }

    // --- ReviewRequestDto ---

    @Test
    void review_nullOrderId_failsValidation() {
        ReviewRequestDto r = new ReviewRequestDto();
        r.setOrderId(null);
        r.setRating(3);

        Set<ConstraintViolation<ReviewRequestDto>> violations = validator.validate(r);

        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("orderId"));
    }

    @Test
    void review_ratingZero_failsValidation() {
        ReviewRequestDto r = new ReviewRequestDto();
        r.setOrderId(1L);
        r.setRating(0);

        Set<ConstraintViolation<ReviewRequestDto>> violations = validator.validate(r);

        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("rating"));
    }

    @Test
    void review_ratingSix_failsValidation() {
        ReviewRequestDto r = new ReviewRequestDto();
        r.setOrderId(1L);
        r.setRating(6);

        Set<ConstraintViolation<ReviewRequestDto>> violations = validator.validate(r);

        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("rating"));
    }

    @Test
    void review_ratingOne_passesValidation() {
        ReviewRequestDto r = new ReviewRequestDto();
        r.setOrderId(1L);
        r.setRating(1);

        assertThat(validator.validate(r)).isEmpty();
    }

    @Test
    void review_ratingFive_passesValidation() {
        ReviewRequestDto r = new ReviewRequestDto();
        r.setOrderId(1L);
        r.setRating(5);

        assertThat(validator.validate(r)).isEmpty();
    }
}
