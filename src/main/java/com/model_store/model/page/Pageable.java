package com.model_store.model.page;

import lombok.Data;

@Data
public class Pageable {
    private int size;
    private int page;
}