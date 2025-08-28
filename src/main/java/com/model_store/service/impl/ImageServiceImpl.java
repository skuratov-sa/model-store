package com.model_store.service.impl;

import com.amazonaws.services.kms.model.NotFoundException;
import com.model_store.exception.EntityNotFoundException;
import com.model_store.exception.constant.EntityException;
import com.model_store.mapper.ImageMapper;
import com.model_store.model.base.Image;
import com.model_store.model.base.Product;
import com.model_store.model.constant.ImageStatus;
import com.model_store.model.constant.ImageTag;
import com.model_store.model.dto.ImageResponse;
import com.model_store.repository.ImageRepository;
import com.model_store.repository.OrderRepository;
import com.model_store.repository.ParticipantRepository;
import com.model_store.repository.ProductRepository;
import com.model_store.service.ImageService;
import com.model_store.service.S3Service;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static java.util.Objects.isNull;

@Slf4j
@Service
@RequiredArgsConstructor
public class ImageServiceImpl implements ImageService {
    private final ImageRepository imageRepository;
    private final S3Service s3Service;
    private final ImageMapper imageMapper;
    private final ParticipantRepository participantRepository;
    private final ProductRepository productRepository;
    private final OrderRepository orderRepository;

    private final static String defaultImageName = "not_found.jpeg";

    @Override
    public Flux<ImageResponse> findImagesByIds(List<Long> imageIds) {
        return findImagesById(imageIds)
                .flatMap(image -> s3Service.getFile(image.getTag(), image.getFilename()))
                .onErrorResume(e -> findImageDefault());
    }

    @Override
    public Mono<Long> findMainImage(Long entityId, ImageTag tag) {
        return findActualImages(entityId, tag).next();
    }


    @Override
    public Mono<ImageResponse> findImageDefault() {
        return s3Service.getFile(ImageTag.SYSTEM, defaultImageName);
    }

    @Override
    public Flux<Long> findActualImages(Long entityId, ImageTag tag) {
        return imageRepository.findActualIdsByEntity(entityId, tag);
    }

    @Override
    @Transactional
    public Mono<Void> updateImagesStatus(List<Long> imageIds, Long entityId, ImageStatus status, ImageTag tag) {
        return Flux.fromIterable(imageIds)
                .flatMap(imageRepository::findById)
                .filter(image -> (isNull(tag) || image.getTag().equals(tag)) && (isNull(image.getEntityId()) || image.getEntityId().equals(entityId)))
                .switchIfEmpty(Mono.error(new NotFoundException("Image not found")))
                .flatMap(image -> {
                    image.setStatus(status);
                    if (!isNull(entityId)) {
                        image.setEntityId(entityId);
                    }
                    return imageRepository.save(image);
                }).then();
    }

    @Override
    public Flux<Long> saveImages(ImageTag tag, Long entityId, List<FilePart> files) {
        return Flux.fromIterable(files)
                .flatMap(file -> s3Service.uploadFile(file, tag))
                .map(name -> imageMapper.toImage(entityId, tag, name,
                        tag == ImageTag.PARTICIPANT ? ImageStatus.ACTIVE : ImageStatus.TEMPORARY
                ))
                .flatMap(imageRepository::save)
                .map(Image::getId);
    }

    @Override
    public Mono<Void> deleteImagesByEntityId(Long entityId, ImageTag tag) {
        return imageRepository.updateStatusById(entityId, tag, ImageStatus.DELETE);
    }

    @Override
    public Mono<Boolean> isActualEntity(Long entityId, ImageTag tag, Long participantId) {
        if (isNull(entityId)) return Mono.just(true);
        return switch (tag) {
            case PRODUCT -> productRepository.findActualProduct(entityId).hasElement();
            case PARTICIPANT -> Mono.just(participantId.equals(entityId));
            case SYSTEM, ORDER -> Mono.just(true);
        };
    }

    @Override
    public Flux<Image> findTemporaryImages() {
        return imageRepository.findImagesToDelete();
    }

    @Override
    public Mono<Void> deleteById(Long id) {
        return imageRepository.deleteById(id);
    }

    @Override
    public Mono<Void> deleteImages(List<Long> imageIds, ImageTag tag, Long participantId) {
        return Flux.fromIterable(imageIds)
                .flatMap(entityId -> imageRepository.findByIdAndTag(entityId, tag))
                .switchIfEmpty(Mono.error(new EntityNotFoundException(EntityException.IMAGE)))
                .collectList()
                .flatMap(images -> isUserAccessible(tag, images, participantId)
                        .filter(Boolean::booleanValue)
                        .flatMap(ignore -> updateImagesStatus(images, ImageStatus.DELETE))
                );
    }

    private @NotNull Flux<Image> findImagesById(List<Long> imageIds) {
        return Flux.fromIterable(imageIds)
                .flatMap(imageRepository::findById)
                .filter(image -> image.getStatus().equals(ImageStatus.ACTIVE));
    }

    private Mono<Void> updateImagesStatus(List<Image> images, ImageStatus status) {
        images.forEach(image -> image.setStatus(status));
        return imageRepository.saveAll(images).then();
    }

    private Mono<Boolean> isUserAccessible(ImageTag tag, List<Image> images, Long participantId) {
        Set<Long> entityIds = images.stream().map(Image::getEntityId).collect(Collectors.toSet());

        return switch (tag) {
            case PARTICIPANT -> Mono.just(entityIds.stream().allMatch(participantId::equals));
            case PRODUCT -> Flux.fromIterable(entityIds)
                    .flatMap(productRepository::findActualProduct)
                    .map(Product::getParticipantId)
                    .all(participantId::equals);
            case ORDER -> Flux.fromIterable(entityIds)
                    .flatMap(orderRepository::findById)
                    .map(order -> order.getCustomerId().equals(participantId) || order.getSellerId().equals(participantId))
                    .all(Boolean::booleanValue);
            default -> Mono.just(false);
        };
    }

//    @Transactional
//    public Mono<Void> deleteImagesByParticipant(Long participantId) {
//        return imageRepository.findIdsByEntityIdAndTag(participantId, ImageTag.PARTICIPANT)
//                .collectList()
//                .flatMap(imageIds -> updateImagesStatus(imageIds, null, ImageStatus.DELETE, ImageTag.PARTICIPANT))
//                .then();
//    }
}