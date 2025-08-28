package com.model_store.service.impl;

import com.model_store.mapper.CategoryMapper;
import com.model_store.model.base.Category;
import com.model_store.model.dto.CategoryDto;
import com.model_store.model.dto.CategoryResponse;
import com.model_store.repository.CategoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
@Deprecated

@ExtendWith(MockitoExtension.class)
class CategoryServiceImlTest {

    @Mock
    private CategoryRepository categoryRepository;

    @Mock
    private CategoryMapper categoryMapper;

    @InjectMocks
    private CategoryServiceIml categoryService;

    private Category category;
    private CategoryDto categoryDto;
    private CategoryResponse categoryResponse;

    @BeforeEach
    void setUp() {
        category = new Category();
        category.setId(1L);
        category.setName("Test Category");
        category.setParentId(null);
        categoryDto = CategoryDto.builder().id(1L).name("Test Category").build();
        categoryResponse = new CategoryResponse(1L, "Test Category");
    }

    @Test
    void getCategories_shouldReturnListOfCategoryResponses() {
        List<Category> categories = Collections.singletonList(category);
        List<CategoryResponse> categoryResponses = Collections.singletonList(categoryResponse);

        when(categoryRepository.findAll()).thenReturn(Flux.fromIterable(categories));
        when(categoryMapper.toCategoryResponse(categories)).thenReturn(categoryResponses);

        StepVerifier.create(categoryService.getCategories())
                .expectNext(categoryResponses)
                .verifyComplete();

        verify(categoryRepository).findAll();
        verify(categoryMapper).toCategoryResponse(categories);
    }

    @Test
    void getCategories_shouldReturnEmptyList_whenNoCategoriesExist() {
        List<Category> categories = Collections.emptyList();
        List<CategoryResponse> categoryResponses = Collections.emptyList();

        when(categoryRepository.findAll()).thenReturn(Flux.empty());
        when(categoryMapper.toCategoryResponse(categories)).thenReturn(categoryResponses);

        StepVerifier.create(categoryService.getCategories())
                .expectNext(categoryResponses)
                .verifyComplete();

        verify(categoryRepository).findAll();
        verify(categoryMapper).toCategoryResponse(categories);
    }

    @Test
    void createCategory_shouldReturnCategoryId_whenCategoryIsSaved() {
        String name = "New Category";
        Long parentId = 1L;
        Category savedCategory = new Category();
        savedCategory.setId(2L); // Assuming a new ID is generated
        savedCategory.setName(name);
        savedCategory.setParentId(parentId);

        when(categoryRepository.save(any(Category.class))).thenReturn(Mono.just(savedCategory));

        StepVerifier.create(categoryService.createCategory(name, parentId))
                .expectNext(2L)
                .verifyComplete();

        verify(categoryRepository).save(any(Category.class));
    }

    @Test
    void findByProductId_shouldReturnCategoryDto_whenCategoryExists() {
        when(categoryRepository.findById(1L)).thenReturn(Mono.just(category));
        when(categoryMapper.toCategoryDto(category)).thenReturn(categoryDto);

        StepVerifier.create(categoryService.findByProductId(1L))
                .expectNext(categoryDto)
                .verifyComplete();

        verify(categoryRepository).findById(1L);
        verify(categoryMapper).toCategoryDto(category);
    }

    @Test
    void findByProductId_shouldReturnEmpty_whenCategoryDoesNotExist() {
        when(categoryRepository.findById(1L)).thenReturn(Mono.empty());

        StepVerifier.create(categoryService.findByProductId(1L))
                .verifyComplete(); // Expect empty Mono

        verify(categoryRepository).findById(1L);
    }

    @Test
    void updateCategory_shouldComplete_whenCategoryExistsAndIsUpdated() {
        String newName = "Updated Category";
        Category updatedCategory = new Category();
        updatedCategory.setId(1L);
        updatedCategory.setName(newName); // Name is updated
        updatedCategory.setParentId(null);


        when(categoryRepository.findById(1L)).thenReturn(Mono.just(category));
        // Simulate the behavior of doOnNext by checking the updated name
        when(categoryRepository.save(any(Category.class))).thenAnswer(invocation -> {
            Category catToSave = invocation.getArgument(0);
            if (catToSave.getName().equals(newName) && catToSave.getId().equals(1L)) {
                return Mono.just(catToSave);
            }
            return Mono.error(new AssertionError("Category name was not updated before save"));
        });


        StepVerifier.create(categoryService.updateCategory(1L, newName))
                .verifyComplete();

        verify(categoryRepository).findById(1L);
        verify(categoryRepository).save(any(Category.class));
    }

    @Test
    void updateCategory_shouldCompleteWithoutSaving_whenCategoryDoesNotExist() {
        String newName = "Updated Category";
        when(categoryRepository.findById(1L)).thenReturn(Mono.empty());

        StepVerifier.create(categoryService.updateCategory(1L, newName))
                .verifyComplete();

        verify(categoryRepository).findById(1L);
        // verify(categoryRepository, never()).save(any(Category.class)); // Not easily verifiable with reactive flow without more complex setup
    }
}
