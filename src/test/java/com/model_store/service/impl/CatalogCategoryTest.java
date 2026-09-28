package com.model_store.service.impl;

import com.model_store.model.dto.CategoryResponse;
import com.model_store.service.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogCategoryTest extends IntegrationTest {

    @Test
    void seededCatalogHasRequestedTreeAndAdultCategory() {
        List<CategoryResponse> roots = categoryService.getCategories().block();

        CategoryResponse catalog = roots.stream()
                .filter(root -> root.getName().equals("Каталог"))
                .findFirst()
                .orElseThrow();
        assertThat(catalog.getName()).isEqualTo("Каталог");
        assertThat(catalog.getChilds()).extracting(CategoryResponse::getName)
                .containsExactly("Фигурки", "Карточки", "Манга", "Другое");

        CategoryResponse figures = catalog.getChilds().getFirst();
        assertThat(figures.getChilds()).extracting(CategoryResponse::getName)
                .containsExactly("Тип", "Производитель", "Title");
        assertThat(figures.getChilds().getFirst().getChilds())
                .extracting(CategoryResponse::getName)
                .contains("NSFW (18+)")
                .doesNotContain("По состоянию");

        assertThat(count(catalog)).isEqualTo(113);
    }

    private int count(CategoryResponse node) {
        return 1 + node.getChilds().stream().mapToInt(this::count).sum();
    }
}
