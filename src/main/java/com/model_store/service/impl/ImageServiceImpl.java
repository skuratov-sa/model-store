package com.model_store.service.impl;

import com.amazonaws.services.kms.model.NotFoundException;
import com.model_store.configuration.property.ApplicationProperties;
import com.model_store.configuration.property.S3ConfigurationProperties;
import com.model_store.exception.ApiErrors;
import com.model_store.exception.constant.ErrorCode;
import com.model_store.mapper.ImageMapper;
import com.model_store.model.base.Image;
import com.model_store.model.base.Product;
import com.model_store.model.constant.ImageStatus;
import com.model_store.model.constant.ImageTag;
import com.model_store.model.dto.ImageMetadataDto;
import com.model_store.model.dto.ImageResponse;
import com.model_store.repository.ImageRepository;
import com.model_store.repository.OrderRepository;
import com.model_store.repository.ParticipantRepository;
import com.model_store.repository.ProductRepository;
import com.model_store.service.ImageService;
import com.model_store.service.S3Service;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
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
    private final ApplicationProperties applicationProperties;
    private final S3ConfigurationProperties s3Properties;

    private final static String defaultImageName = "not_found.jpeg";

    @Override
    public Flux<ImageResponse> findImagesByIds(List<Long> imageIds) {
        return Flux.fromIterable(imageIds)
                .flatMap(imageRepository::findById)
                .filter(image -> image.getStatus() == ImageStatus.ACTIVE && image.getTag() == ImageTag.ORDER)
                .flatMap(image -> s3Service.getFile(image.getTag(), "original/" + image.getFilename()), 10)
                .onErrorResume(e -> findImageDefault());
    }

    @Override
    public Flux<ImageMetadataDto> findImageMetadataByIds(List<Long> imageIds) {
        return Flux.fromIterable(imageIds)
                .flatMap(imageRepository::findById)
                .filter(image -> image.getStatus() == ImageStatus.ACTIVE)
                .filter(image -> image.getTag() != ImageTag.ORDER)
                .map(image -> new ImageMetadataDto(
                        image.getId(),
                        buildCdnUrl(image, "original"),
                        buildCdnUrl(image, "medium"),
                        buildCdnUrl(image, "thumbnail"),
                        image.getWidth(),
                        image.getHeight(),
                        image.getContentType()
                ));
    }

    private String buildCdnUrl(Image image, String variant) {
        String bucket = resolveBucketName(image.getTag());
        return applicationProperties.getCdnBaseUrl() + "/" + bucket + "/" + variant + "/" + image.getFilename();
    }

    private String resolveBucketName(ImageTag tag) {
        return switch (tag) {
            case PARTICIPANT -> s3Properties.getParticipantBucketName();
            case PRODUCT -> s3Properties.getProductBucketName();
            case ORDER -> s3Properties.getOrderBucketName();
            case SYSTEM -> s3Properties.getSystemBucketName();
        };
    }

    @Override
    public Mono<Long> findMainImage(Long entityId, ImageTag tag) {
        return imageRepository.findActualIdsByEntity(entityId, tag).next();
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
    public Mono<Void> updateImagesStatus(List<Long> imageIds, Long entityId, ImageStatus status, ImageTag tag) {
        if (imageIds == null || imageIds.isEmpty()) return Mono.empty();
        return imageRepository.updateStatusByIds(
                imageIds.toArray(Long[]::new),
                entityId,
                status.name(),
                tag != null ? tag.name() : null
        );
    }

    @Override
    public Mono<Void> replaceForParticipant(Long imageId, Long entityId, ImageTag tag) {
        Mono<Void> markOldDeleted =
                imageRepository.findByEntityIdAndTag(entityId, tag)
                        .map(img -> {
                            img.setStatus(ImageStatus.DELETE);
                            return img;
                        })
                        .flatMap(imageRepository::save)
                        .then();

        Mono<Void> activateNew =
                imageRepository.findById(imageId)
                        .filter(i -> i.getStatus() == ImageStatus.ACTIVE)
                        .switchIfEmpty(Mono.error(new NotFoundException("Фотография с id " + imageId + "; Не найдена или не активна")))
                        .map(img -> {
                            img.setStatus(ImageStatus.ACTIVE);
                            img.setEntityId(entityId);
                            return img;
                        })
                        .flatMap(imageRepository::save)
                        .then();

        return markOldDeleted.then(activateNew);
    }

    @Override
    public Flux<Long> saveImages(ImageTag tag, Long entityId, List<FilePart> files) {
        ImageStatus status = tag == ImageTag.PARTICIPANT ? ImageStatus.ACTIVE : ImageStatus.TEMPORARY;
        return Flux.fromIterable(files)
                .flatMap(file -> s3Service.uploadFile(file, tag))
                .map(result -> imageMapper.toImage(
                        entityId, tag, result.filename(), status,
                        result.contentType(), result.width(), result.height()
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
    public Mono<Void> deleteAllByIds(List<Long> ids) {
        return imageRepository.deleteAllByIds(ids.toArray(Long[]::new));
    }

    @Override
    public Mono<Void> deleteImages(List<Long> imageIds, ImageTag tag, Long participantId) {
        return Flux.fromIterable(imageIds)
                .flatMap(imageRepository::findById)
                .filter(image -> image.getTag() == tag && image.getStatus() == ImageStatus.ACTIVE)
                .switchIfEmpty(Mono.error(
                        ApiErrors.notFound(ErrorCode.IMAGE_NOT_FOUND, "Не удалось найти изображение")
                ))
                .collectList()
                .flatMap(images -> isUserAccessible(tag, images, participantId)
                        .filter(Boolean::booleanValue)
                        .flatMap(ignore -> updateImagesStatus(images, ImageStatus.DELETE))
                );
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
}