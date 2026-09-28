package com.model_store.mapper;

import com.model_store.model.base.Category;
import com.model_store.model.dto.CategoryDto;
import com.model_store.model.dto.CategoryResponse;
import org.mapstruct.Mapper;

import java.util.HashMap;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static java.util.Objects.isNull;

@Mapper(componentModel = "spring")
public interface CategoryMapper {

    default List<CategoryResponse> toCategoryResponse(List<Category> categories) {
        List<Category> ordered = categories.stream()
                .sorted(Comparator.comparing(Category::getDisplayOrder,
                                Comparator.nullsLast(Integer::compareTo))
                        .thenComparing(Category::getId))
                .toList();
        Map<Long, CategoryResponse> map = new HashMap<>();
        ordered.forEach(category -> map.put(category.getId(), new CategoryResponse(category)));

        for (Category category : ordered) {
            Long parentId = category.getParentId();
            Long categoryId = category.getId();

            if (!isNull(parentId)) {
                var parent = map.get(parentId);
                var child = map.get(categoryId);
                parent.addCategoryResponse(child);
            }
        }

        return ordered.stream()
                .filter(category -> isNull(category.getParentId()))
                .map(Category::getId)
                .map(map::get)
                .toList();
    }

    CategoryDto toCategoryDto(Category category);
}
