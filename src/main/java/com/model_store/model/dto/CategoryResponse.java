package com.model_store.model.dto;

import com.model_store.model.base.Category;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;


@Getter
@Setter
public class CategoryResponse {
    private final Long id;
    private final String name;
    private List<CategoryResponse> childs = new ArrayList<>();

    public CategoryResponse(Category category) {
        this.id = category.getId();
        this.name = category.getName();
    }

    public CategoryResponse(Long id, String name) {
        this.id = id;
        this.name = name;
    }

    public void addCategoryResponse(CategoryResponse categoryResponse) {
        childs.add(categoryResponse);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CategoryResponse that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}