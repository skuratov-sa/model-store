package com.model_store.model.page;

import jakarta.validation.constraints.Min;
import lombok.Data;

@Data
public class Pageable {
    @Min(value = 0, message = "Pageable.size не может быть меньше чем 0")
    private int size;
    @Min(value = 0, message = "Pageable.page не может быть меньше чем 0")
    private int page;
}