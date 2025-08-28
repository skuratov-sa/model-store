package com.model_store.service1;

import com.model_store.mapper.ProductMapper;
import com.model_store.model.CreateOrUpdateProductRequest;
import com.model_store.model.FindProductRequest;
import com.model_store.model.base.Product;
import com.model_store.model.constant.ImageStatus;
import com.model_store.model.constant.ImageTag;
import com.model_store.model.constant.ParticipantRole;
import com.model_store.model.constant.ProductStatus;
import com.model_store.model.constant.SortByType;
import com.model_store.model.dto.CategoryDto;
import com.model_store.model.dto.GetProductResponse;
import com.model_store.model.dto.ProductDto;
import com.model_store.model.page.Pageable;
import com.model_store.repository.ProductRepository;
import com.model_store.service.CategoryService;
import com.model_store.service.impl.ImageServiceImpl;
import com.model_store.service.impl.ProductServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

import static com.model_store.model.constant.ProductStatus.ACTIVE;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductServiceImplTest {

    @Mock
    private ProductRepository productRepository;
    @Mock
    private CategoryService categoryService;
    @Mock
    private ProductMapper productMapper;
    @Mock
    private ImageServiceImpl imageService; // Mocking the concrete class as per dependency

    @InjectMocks
    private ProductServiceImpl productService;

    private Product product1;
    private CategoryDto categoryDto1;
    private CreateOrUpdateProductRequest createRequest;
    private GetProductResponse getProductResponse1;
    private ProductDto productDto1;

    @BeforeEach
    void setUp() {
        product1 = new Product();
        product1.setId(1L);
        product1.setName("Test Product 1");
        product1.setParticipantId(10L);
        product1.setStatus(ACTIVE);
        product1.setPrice(99.99F);
        product1.setCount(50);

        categoryDto1 = CategoryDto.builder().id(100L).name("Electronics").build();

        createRequest = new CreateOrUpdateProductRequest();
        createRequest.setName("New Product");
        createRequest.setPrice(199.99F);
        createRequest.setCount(20);
        createRequest.setDescription("Description");
        createRequest.setImageIds(List.of(1L, 2L));

        getProductResponse1 = GetProductResponse.builder().id(1L).name("Test Product 1").build(); // Populate as needed
        productDto1 = ProductDto.builder().id(1L).name("Test Product 1").build(); // Populate as needed
    }

    @Test
    void productTest() {
        Long productId = 1L;
        List<Long> imageIds = List.of(10L, 11L);

    }
}
