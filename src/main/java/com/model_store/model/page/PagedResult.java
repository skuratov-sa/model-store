package com.model_store.model.page;

import lombok.Data;

import java.util.List;
import java.util.Optional;

@Data
public class PagedResult<T> {
    private final Long totalElements;
//    private final Integer page;
    private final List<T> content;

    public PagedResult(List<T> content, long totalElements, Pageable page) {
        this.content = content;
        this.totalElements = totalElements;
//        this.page = Optional.ofNullable(page).map(Pageable::getPage).orElse(0);
    }
}