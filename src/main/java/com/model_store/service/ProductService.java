package com.model_store.service;

import com.model_store.mapper.ProductMapper;
import com.model_store.model.CreateOrUpdateProductRequest;
import com.model_store.model.FindProductRequest;
import com.model_store.model.base.Product;
import com.model_store.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class ProductService {
    private final ProductRepository productRepository;
    private final ProductMapper productMapper;

    public Mono<Product> findById(Long productId) {
        return productRepository.findById(productId);
    }

    public Flux<Product> findByParams(FindProductRequest searchParams) {
//        return productRepository.findByParams(searchParams);

        return null;
    }

    public Flux<Product> findFavoriteByParams(FindProductRequest searchParams) {
        return null;
    }

    public Flux<Product> findCartByParams(FindProductRequest searchParams) {
        return null;
    }

    public Mono<Void> createProduct(CreateOrUpdateProductRequest request) {
        Product product = productMapper.toProduct(request);
        return productRepository.save(product)
                .then();
    }

    public Mono<Void> updateProduct(Long id, CreateOrUpdateProductRequest request) {
        Product product = productMapper.toProduct(request);
        product.setId(id);

        return productRepository.save(product).then();
    }

    public Mono<Void> deleteProduct(Long id) {
        return productRepository.deleteById(id);
    }
}