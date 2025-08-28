//package com.model_store.service.impl;
//
//import com.model_store.mapper.ProductMapper;
//import com.model_store.model.CreateOrUpdateProductRequest;
//import com.model_store.model.FindProductRequest;
//import com.model_store.model.base.Product;
//import com.model_store.model.constant.ImageStatus;
//import com.model_store.model.constant.ImageTag;
//import com.model_store.model.constant.ParticipantRole;
//import com.model_store.model.constant.ProductStatus;
//import com.model_store.model.constant.SortByType;
//import com.model_store.model.dto.CategoryDto;
//import com.model_store.model.dto.GetProductResponse;
//import com.model_store.model.dto.ProductDto;
//import com.model_store.model.page.Pageable;
//import com.model_store.repository.ProductRepository;
//import com.model_store.service.CategoryService;
//// import com.model_store.service.ImageService; // Corrected to ImageServiceImpl below
//import org.junit.jupiter.api.BeforeEach;
//import org.junit.jupiter.api.Test;
//import org.junit.jupiter.api.extension.ExtendWith;
//import org.mockito.InjectMocks;
//import org.mockito.Mock;
//import org.mockito.junit.jupiter.MockitoExtension;
//import reactor.core.publisher.Flux;
//import reactor.core.publisher.Mono;
//import reactor.test.StepVerifier;
//
//import java.time.Instant;
//import java.util.Collections;
//import java.util.List;
//
//import static com.model_store.model.constant.ProductStatus.ACTIVE;
//import static org.mockito.ArgumentMatchers.any;
//import static org.mockito.ArgumentMatchers.anyLong;
//import static org.mockito.Mockito.*;
//@Deprecated
//@ExtendWith(MockitoExtension.class)
//class ProductServiceImplTest {
//
//    @Mock
//    private ProductRepository productRepository;
//    @Mock
//    private CategoryService categoryService;
//    @Mock
//    private ProductMapper productMapper;
//    @Mock
//    private ImageServiceImpl imageService; // Mocking the concrete class as per dependency
//
//    @InjectMocks
//    private ProductServiceImpl productService;
//
//    private Product product1;
//    private CategoryDto categoryDto1;
//    private CreateOrUpdateProductRequest createRequest;
//    private GetProductResponse getProductResponse1;
//    private ProductDto productDto1;
//
//    @BeforeEach
//    void setUp() {
//        product1 = new Product();
//        product1.setId(1L);
//        product1.setName("Test Product 1");
//        product1.setParticipantId(10L);
//        product1.setStatus(ACTIVE);
//        product1.setPrice(99.99F);
//        product1.setCount(50);
//
//        categoryDto1 = CategoryDto.builder().id(100L).name("Electronics").build();
//
//        createRequest = new CreateOrUpdateProductRequest();
//        createRequest.setName("New Product");
//        createRequest.setPrice(199.99F);
//        createRequest.setCount(20);
//        createRequest.setDescription("Description");
//        createRequest.setImageIds(List.of(1L, 2L));
//
//        getProductResponse1 = GetProductResponse.builder().id(1L).name("Test Product 1").build(); // Populate as needed
//        productDto1 = ProductDto.builder().id(1L).name("Test Product 1").build(); // Populate as needed
//    }
//
//    @Test
//    void getProductById_shouldReturnProductResponse_whenProductAndCategoryExist() {
//        Long productId = 1L;
//        List<Long> imageIds = List.of(10L, 11L);
//
//        when(productRepository.findActualProduct(productId)).thenReturn(Mono.just(product1));
//        when(imageService.findActualImages(productId, ImageTag.PRODUCT)).thenReturn(Flux.fromIterable(imageIds));
//        when(categoryService.findByProductId(product1.getCategoryId())).thenReturn(Mono.just(categoryDto1));
//        when(productMapper.toGetProductResponse(product1, categoryDto1, imageIds, Collections.emptyList())).thenReturn(getProductResponse1);
//
//        StepVerifier.create(productService.getProductById(productId))
//                .expectNext(getProductResponse1)
//                .verifyComplete();
//
//        verify(productRepository).findActualProduct(productId);
//        verify(imageService).findActualImages(productId, ImageTag.PRODUCT);
//        verify(categoryService).findByProductId(product1.getCategoryId());
//        verify(productMapper).toGetProductResponse(product1, categoryDto1, imageIds, Collections.emptyList());
//    }
//
//    @Test
//    void getProductById_shouldReturnEmpty_whenProductNotFound() {
//        Long productId = 2L; // Non-existent
//        when(productRepository.findActualProduct(productId)).thenReturn(Mono.empty());
//        // imageService.findActualImages might still be called with 2L, but the zip will be empty.
//        when(imageService.findActualImages(productId, ImageTag.PRODUCT)).thenReturn(Flux.empty());
//
//
//        StepVerifier.create(productService.getProductById(productId))
//                .verifyComplete(); // Expect empty due to product not found
//
//        verify(productRepository).findActualProduct(productId);
//        // categoryService and productMapper should not be called if product is not found
//        verify(categoryService, never()).findByProductId(anyLong());
//        verify(productMapper, never()).toGetProductResponse(any(), any(), any(), any());
//    }
//
//    @Test
//    void getProductById_shouldReturnEmpty_whenCategoryNotFound() {
//        Long productId = 1L;
//        List<Long> imageIds = List.of(10L);
//        when(productRepository.findActualProduct(productId)).thenReturn(Mono.just(product1));
//        when(imageService.findActualImages(productId, ImageTag.PRODUCT)).thenReturn(Flux.fromIterable(imageIds));
//        when(categoryService.findByProductId(product1.getCategoryId())).thenReturn(Mono.empty()); // Category not found
//
//        StepVerifier.create(productService.getProductById(productId))
//                .verifyComplete(); // Expect empty due to category not found
//
//        verify(productRepository).findActualProduct(productId);
//        verify(imageService).findActualImages(productId, ImageTag.PRODUCT);
//        verify(categoryService).findByProductId(product1.getCategoryId());
//        verify(productMapper, never()).toGetProductResponse(any(), any(), any(), Collections.emptyList());
//    }
//
//    @Test
//    void getProductById_shouldHandleEmptyImageList() {
//        Long productId = 1L;
//        List<Long> emptyImageIds = Collections.emptyList();
//
//        when(productRepository.findActualProduct(productId)).thenReturn(Mono.just(product1));
//        when(imageService.findActualImages(productId, ImageTag.PRODUCT)).thenReturn(Flux.empty()); // No images
//        when(categoryService.findByProductId(product1.getCategoryId())).thenReturn(Mono.just(categoryDto1));
//        when(productMapper.toGetProductResponse(product1, categoryDto1, emptyImageIds, Collections.emptyList())).thenReturn(getProductResponse1);
//
//
//        StepVerifier.create(productService.getProductById(productId))
//                .expectNext(getProductResponse1)
//                .verifyComplete();
//    }
//
//    @Test
//    void findById_shouldReturnProduct_whenProductExists() {
//        Long productId = 1L;
//        when(productRepository.findById(productId)).thenReturn(Mono.just(product1));
//
//        StepVerifier.create(productService.findById(productId))
//                .expectNext(product1)
//                .verifyComplete();
//        verify(productRepository).findById(productId);
//    }
//
//    @Test
//    void findById_shouldReturnEmpty_whenProductDoesNotExist() {
//        Long productId = 2L; // Non-existent
//        when(productRepository.findById(productId)).thenReturn(Mono.empty());
//
//        StepVerifier.create(productService.findById(productId))
//                .verifyComplete();
//        verify(productRepository).findById(productId);
//    }
//
//    @Test
//    void shortInfoById_shouldReturnProductDto_whenProductAndCategoryExist() {
//        Long productId = 1L;
//        Long imageId = 123L;
//
//        when(productRepository.findById(productId)).thenReturn(Mono.just(product1));
//        when(categoryService.findByProductId(product1.getCategoryId())).thenReturn(Mono.just(categoryDto1));
//        when(imageService.findMainImage(productId, ImageTag.PRODUCT)).thenReturn(Mono.just(imageId));
//        when(productMapper.toProductDto(product1, categoryDto1, imageId)).thenReturn(productDto1);
//
//        StepVerifier.create(productService.shortInfoById(productId))
//                .expectNext(productDto1)
//                .verifyComplete();
//    }
//
//    @Test
//    void shortInfoById_shouldReturnDtoWithNullImageId_whenNoMainImage() {
//        Long productId = 1L;
//
//        when(productRepository.findById(productId)).thenReturn(Mono.just(product1));
//        when(categoryService.findByProductId(product1.getCategoryId())).thenReturn(Mono.just(categoryDto1));
//        when(imageService.findMainImage(productId, ImageTag.PRODUCT)).thenReturn(Mono.empty()); // No image
//        when(productMapper.toProductDto(product1, categoryDto1, null)).thenReturn(productDto1); // Expect null imageId
//
//        StepVerifier.create(productService.shortInfoById(productId))
//                .expectNext(productDto1)
//                .verifyComplete();
//    }
//
//
//    @Test
//    void shortInfoById_shouldReturnEmpty_whenProductNotFound() {
//        Long productId = 2L;
//        when(productRepository.findById(productId)).thenReturn(Mono.empty());
//
//        StepVerifier.create(productService.shortInfoById(productId))
//                .verifyComplete();
//        verify(productRepository).findById(productId);
//        verify(categoryService, never()).findByProductId(anyLong());
//        verify(imageService, never()).findMainImage(anyLong(), any(ImageTag.class));
//    }
//
//    @Test
//    void shortInfoById_shouldReturnEmpty_whenCategoryNotFound() {
//        Long productId = 1L;
//        when(productRepository.findById(productId)).thenReturn(Mono.just(product1));
//        when(categoryService.findByProductId(product1.getCategoryId())).thenReturn(Mono.empty()); // Category not found
//        // imageService might be called, but the chain will break at zipWith due to empty categoryService Mono
//
//        StepVerifier.create(productService.shortInfoById(productId))
//                .verifyComplete();
//        verify(productRepository).findById(productId);
//        verify(categoryService).findByProductId(product1.getCategoryId());
//    }
//
//    @Test
//    void findByParams_shouldReturnPagedProducts() {
//        FindProductRequest request = new FindProductRequest();
//        request.setPageable(new Pageable(0, Instant.now(), 77F, 11L, SortByType.PRICE_ASC));
//        List<Product> productsFromRepo = List.of(product1);
//        List<ProductDto> productDtos = List.of(productDto1);
//        Integer totalCount = 1;
//
//        when(productRepository.findByParams(request, null)).thenReturn(Flux.fromIterable(productsFromRepo));
//        // Mocking the chain inside concatMap for product1
//        when(categoryService.findByProductId(product1.getCategoryId())).thenReturn(Mono.just(categoryDto1));
//        when(imageService.findMainImage(product1.getId(), ImageTag.PRODUCT)).thenReturn(Mono.just(123L));
//        when(productMapper.toProductDto(product1, categoryDto1, 123L)).thenReturn(productDto1);
//
//        when(productRepository.findCountBySearchParams(request, null)).thenReturn(Mono.just(totalCount));
//
//
//        StepVerifier.create(productService.findByParams(request))
//                .expectNext(productDtos)
//                .verifyComplete();
//
//        verify(productRepository).findByParams(request, null);
//        verify(categoryService).findByProductId(product1.getCategoryId());
//        verify(imageService).findMainImage(product1.getId(), ImageTag.PRODUCT);
//        verify(productMapper).toProductDto(product1, categoryDto1, 123L);
//        verify(productRepository).findCountBySearchParams(request, null);
//    }
//
//    @Test
//    void findByParams_shouldHandleEmptyResultFromRepository() {
//        FindProductRequest request = new FindProductRequest();
//        request.setPageable(new Pageable(0, Instant.now(), 77F, 11L, SortByType.PRICE_ASC));
//
//        when(productRepository.findByParams(request, null)).thenReturn(Flux.empty());
//        when(productRepository.findCountBySearchParams(request, null)).thenReturn(Mono.just(0));
//
//        List<ProductDto> expectedResult = Collections.emptyList();
//
//        StepVerifier.create(productService.findByParams(request))
//                .expectNext(expectedResult)
//                .verifyComplete();
//
//        verify(productRepository).findByParams(request, null);
//        verify(productRepository).findCountBySearchParams(request, null);
//        verify(categoryService, never()).findByProductId(anyLong());
//        verify(imageService, never()).findMainImage(anyLong(), any(ImageTag.class));
//    }
//
//    @Test
//    void findByParams_shouldHandleCategoryOrImageNotFoundForAProduct() {
//        FindProductRequest request = new FindProductRequest();
//        request.setPageable(new Pageable(0, Instant.now(), 77F, 11L, SortByType.PRICE_ASC));
//        Product product2 = new Product(); // Another product
//        product2.setId(2L);
//        product2.setCategoryId(200L);
//
//        // product1 has category and image
//        // product2, category will be found, but no image
//        CategoryDto categoryDto2 = CategoryDto.builder().id(200L).build();
//        ProductDto productDto2_noImage = ProductDto.builder().id(2L).build();
//
//        when(productRepository.findByParams(request, null)).thenReturn(Flux.just(product1, product2));
//
//        // For product1
//        when(categoryService.findByProductId(product1.getCategoryId())).thenReturn(Mono.just(categoryDto1));
//        when(imageService.findMainImage(product1.getId(), ImageTag.PRODUCT)).thenReturn(Mono.just(123L));
//        when(productMapper.toProductDto(product1, categoryDto1, 123L)).thenReturn(productDto1);
//
//        // For product2 - image not found
//        when(categoryService.findByProductId(product2.getCategoryId())).thenReturn(Mono.just(categoryDto2));
//        when(imageService.findMainImage(product2.getId(), ImageTag.PRODUCT)).thenReturn(Mono.empty()); // No image
//        when(productMapper.toProductDto(product2, categoryDto2, null)).thenReturn(productDto2_noImage);
//
//
//        when(productRepository.findCountBySearchParams(request, null)).thenReturn(Mono.just(2));
//
//        List<ProductDto> expectedResult = List.of(productDto1, productDto2_noImage);
//
//        StepVerifier.create(productService.findByParams(request))
//                .expectNext(expectedResult)
//                .verifyComplete();
//    }
//
//    @Test
//    void findActualProduct_shouldReturnProduct_whenProductIsActual() {
//        Long productId = 1L;
//        when(productRepository.findActualProduct(productId)).thenReturn(Mono.just(product1));
//
//        StepVerifier.create(productService.findActualProduct(productId))
//                .expectNext(product1)
//                .verifyComplete();
//        verify(productRepository).findActualProduct(productId);
//    }
//
//    @Test
//    void findActualProduct_shouldReturnEmpty_whenProductNotActualOrFound() {
//        Long productId = 2L;
//        when(productRepository.findActualProduct(productId)).thenReturn(Mono.empty());
//
//        StepVerifier.create(productService.findActualProduct(productId))
//                .verifyComplete();
//        verify(productRepository).findActualProduct(productId);
//    }
//
//    @Test
//    void createProduct_shouldSaveProductAndUpdateImages() {
//        Long participantId = 10L;
//        Product productToSave = new Product(); // Mapped from request
//        productToSave.setId(5L); // ID after save
//
//        when(productMapper.toProduct(createRequest, participantId, ACTIVE)).thenReturn(productToSave);
//        when(productRepository.save(productToSave)).thenReturn(Mono.just(productToSave));
//        when(imageService.updateImagesStatus(createRequest.getImageIds(), productToSave.getId(), ImageStatus.ACTIVE, ImageTag.PRODUCT))
//                .thenReturn(Mono.empty());
//
//        StepVerifier.create(productService.createProduct(createRequest, participantId, ParticipantRole.USER))
//                .expectNext(productToSave.getId())
//                .verifyComplete();
//
//        verify(productMapper).toProduct(createRequest, participantId, ACTIVE);
//        verify(productRepository).save(productToSave);
//        verify(imageService).updateImagesStatus(createRequest.getImageIds(), productToSave.getId(), ImageStatus.ACTIVE, ImageTag.PRODUCT);
//    }
//
//    @Test
//    void createProduct_shouldHandleEmptyImageIds() {
//        Long participantId = 10L;
//        createRequest.setImageIds(Collections.emptyList()); // No image IDs
//        Product productToSave = new Product();
//        productToSave.setId(6L);
//
//        when(productMapper.toProduct(createRequest, participantId, ACTIVE)).thenReturn(productToSave);
//        when(productRepository.save(productToSave)).thenReturn(Mono.just(productToSave));
//        // imageService.updateImagesStatus should not be called if imageIds is empty
//
//        StepVerifier.create(productService.createProduct(createRequest, participantId, ParticipantRole.USER))
//                .expectNext(productToSave.getId())
//                .verifyComplete();
//
//        verify(imageService, never()).updateImagesStatus(anyList(), anyLong(), any(ImageStatus.class), any(ImageTag.class));
//    }
//
//
//    @Test
//    void updateProduct_shouldUpdateProductAndImages_whenProductExistsAndBelongsToParticipant() {
//        Long productId = 1L;
//        Long participantId = 10L; // product1 belongs to this participant
//        Product updatedProduct = new Product(); // Result of mapper.updateProduct
//        updatedProduct.setId(productId);
//        // ... other fields set by mapper
//
//        Product savedProduct = new Product(); // Result of repository.save
//        savedProduct.setId(productId);
//
//
//        when(productRepository.findActualProduct(productId)).thenReturn(Mono.just(product1));
//        when(productMapper.updateProduct(createRequest, product1)).thenReturn(updatedProduct);
//        when(productRepository.save(updatedProduct)).thenReturn(Mono.just(savedProduct));
//        when(imageService.updateImagesStatus(createRequest.getImageIds(), productId, ImageStatus.ACTIVE, ImageTag.PRODUCT))
//                .thenReturn(Mono.empty());
//
//        StepVerifier.create(productService.updateProduct(productId, createRequest, participantId))
//                .verifyComplete();
//
//        verify(productRepository).findActualProduct(productId);
//        verify(productMapper).updateProduct(createRequest, product1);
//        verify(productRepository).save(updatedProduct);
//        verify(imageService).updateImagesStatus(createRequest.getImageIds(), productId, ImageStatus.ACTIVE, ImageTag.PRODUCT);
//    }
//
//    @Test
//    void updateProduct_shouldFail_whenProductNotFound() {
//        Long productId = 2L; // Non-existent
//        Long participantId = 10L;
//        when(productRepository.findActualProduct(productId)).thenReturn(Mono.empty());
//
//        StepVerifier.create(productService.updateProduct(productId, createRequest, participantId))
//                .expectError(com.amazonaws.services.kms.model.NotFoundException.class)
//                .verify();
//    }
//
//    @Test
//    void updateProduct_shouldFail_whenProductDoesNotBelongToParticipant() {
//        Long productId = 1L;
//        Long differentParticipantId = 20L; // Product belongs to 10L
//        when(productRepository.findActualProduct(productId)).thenReturn(Mono.just(product1));
//
//        StepVerifier.create(productService.updateProduct(productId, createRequest, differentParticipantId))
//                .expectError(com.amazonaws.services.kms.model.NotFoundException.class) // Error from filter + switchIfEmpty
//                .verify();
//    }
//
//    @Test
//    void deleteProduct_shouldSetStatusToDeleted_whenProductExistsAndBelongsToParticipant() {
//        Long productId = 1L;
//        Long participantId = 10L; // product1 belongs to this participant
//
//        when(productRepository.findActualProduct(productId)).thenReturn(Mono.just(product1));
//        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> {
//            Product p = invocation.getArgument(0);
//            if (p.getStatus().equals(ProductStatus.DELETED)) {
//                return Mono.just(p);
//            }
//            return Mono.error(new AssertionError("Product status not DELETED"));
//        });
//        when(imageService.deleteImagesByEntityId(product1.getParticipantId(), ImageTag.PRODUCT)) // participantId is used here as per implementation
//            .thenReturn(Mono.empty());
//
//
//        StepVerifier.create(productService.deleteProduct(productId, participantId))
//                .verifyComplete();
//
//        verify(productRepository).findActualProduct(productId);
//        verify(productRepository).save(any(Product.class));
//        verify(imageService).deleteImagesByEntityId(product1.getParticipantId(), ImageTag.PRODUCT);
//    }
//
//    @Test
//    void deleteProduct_shouldFail_whenProductNotFound() {
//        Long productId = 2L; // Non-existent
//        Long participantId = 10L;
//        when(productRepository.findActualProduct(productId)).thenReturn(Mono.empty());
//
//        StepVerifier.create(productService.deleteProduct(productId, participantId))
//                .expectError(com.amazonaws.services.kms.model.NotFoundException.class)
//                .verify();
//    }
//
//    @Test
//    void deleteProduct_shouldFail_whenProductDoesNotBelongToParticipant() {
//        Long productId = 1L;
//        Long differentParticipantId = 20L;
//        when(productRepository.findActualProduct(productId)).thenReturn(Mono.just(product1)); // product1 belongs to 10L
//
//        StepVerifier.create(productService.deleteProduct(productId, differentParticipantId))
//                .expectError(com.amazonaws.services.kms.model.NotFoundException.class)
//                .verify();
//    }
//
//    @Test
//    void updateProductStatus_shouldUpdateStatus_whenProductExists() {
//        Long productId = 1L;
//        ProductStatus newStatus = ACTIVE;
//
//        when(productRepository.findActualProduct(productId)).thenReturn(Mono.just(product1));
//        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> {
//            Product p = invocation.getArgument(0);
//            if (p.getStatus().equals(newStatus)) {
//                return Mono.just(p);
//            }
//            return Mono.error(new AssertionError("Status not updated correctly"));
//        });
//
//        StepVerifier.create(productService.updateProductStatus(productId, newStatus))
//                .verifyComplete();
//        verify(productRepository).save(any(Product.class));
//    }
//
//    @Test
//    void updateProductStatus_shouldFail_whenProductNotFound() {
//        Long productId = 2L; // Non-existent
//        ProductStatus newStatus = ACTIVE;
//        when(productRepository.findActualProduct(productId)).thenReturn(Mono.empty());
//
//        StepVerifier.create(productService.updateProductStatus(productId, newStatus))
//                .expectError(com.amazonaws.services.kms.model.NotFoundException.class)
//                .verify();
//        verify(productRepository, never()).save(any(Product.class));
//    }
//
//    @Test
//    void save_shouldSaveProductAndReturnId() {
//        Product productToSave = new Product(); // A new product instance
//        productToSave.setName("Save Test");
//        Product savedProduct = new Product();
//        savedProduct.setId(100L); // ID given after save
//        savedProduct.setName("Save Test");
//
//
//        when(productRepository.save(productToSave)).thenReturn(Mono.just(savedProduct));
//
//        StepVerifier.create(productService.save(productToSave))
//                .expectNext(100L)
//                .verifyComplete();
//        verify(productRepository).save(productToSave);
//    }
//}
