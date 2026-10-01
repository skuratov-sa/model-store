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
import com.model_store.model.constant.OrderStatus;
import com.model_store.model.constant.ParticipantRole;
import com.model_store.model.constant.ProductAvailabilityType;
import com.model_store.model.constant.ProductStatus;
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
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Objects;
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
    private final DatabaseClient databaseClient;
    private final ApplicationProperties applicationProperties;
    private final S3ConfigurationProperties s3Properties;

    private final static String defaultImageName = "not_found.jpeg";

    @Override
    public Flux<ImageResponse> findImagesByIds(List<Long> imageIds) {
        return findVisibleImagesByIds(imageIds, null);
    }

    @Override
    public Flux<ImageResponse> findVisibleImagesByIds(List<Long> imageIds, Long viewerId) {
        if (imageIds == null || imageIds.isEmpty()) return Flux.empty();
        return imageRepository.findActiveByIds(imageIds.toArray(Long[]::new))
                .collectMap(Image::getId)
                .flatMapMany(imagesById -> Flux.fromIterable(imageIds)
                        .concatMap(id -> Mono.justOrEmpty(imagesById.get(id))
                                .flatMap(image -> {
                                    Mono<ImageResponse> response = s3Service.getFile(image.getTag(),
                                            "original/" + image.getFilename());
                                    if (image.getTag() != ImageTag.ORDER) return response;
                                    return Mono.justOrEmpty(viewerId)
                                            .flatMap(participantRepository::findById)
                                            .flatMap(viewer -> Mono.justOrEmpty(image.getEntityId())
                                                    .flatMap(orderRepository::findById)
                                                    .filter(order -> viewer.getRole() == ParticipantRole.ADMIN
                                                            || order.getCustomerId().equals(viewerId)
                                                            || order.getSellerId().equals(viewerId)))
                                            .flatMap(order -> response);
                                })
                                .onErrorResume(e -> findImageDefault())));
    }

    @Override
    public Flux<ImageResponse> findOrderImagesByIds(List<Long> imageIds, Long viewerId) {
        if (imageIds == null || imageIds.isEmpty()) return Flux.empty();
        return participantRepository.findById(viewerId)
                .flatMapMany(viewer -> imageRepository.findActiveByIds(imageIds.toArray(Long[]::new))
                        .filter(image -> image.getTag() == ImageTag.ORDER)
                        .concatMap(image -> orderRepository.findById(image.getEntityId())
                                .filter(order -> viewer.getRole() == ParticipantRole.ADMIN
                                        || order.getCustomerId().equals(viewerId)
                                        || order.getSellerId().equals(viewerId))
                                .flatMap(order -> s3Service.getFile(image.getTag(), "original/" + image.getFilename()))));
    }

    @Override
    public Mono<Void> activateOrderProof(Long imageId, Long orderId, Long participantId) {
        return imageRepository.activateCaseEvidence(new Long[]{imageId}, orderId, participantId)
                .flatMap(updated -> updated == 1 ? Mono.empty()
                        : Mono.error(ApiErrors.notFound(ErrorCode.IMAGE_NOT_FOUND, "Подтверждение оплаты не найдено")));
    }

    @Override
    public Flux<ImageMetadataDto> findImageMetadataByIds(List<Long> imageIds) {
        if (imageIds == null || imageIds.isEmpty()) return Flux.empty();
        return imageRepository.findActiveByIds(imageIds.toArray(Long[]::new))
                .filter(image -> image.getTag() != ImageTag.ORDER)
                .collectMap(Image::getId)
                .flatMapMany(imagesById -> Flux.fromIterable(imageIds)
                        .concatMap(id -> Mono.justOrEmpty(imagesById.get(id))))
                .map(this::toImageMetadataDto);
    }

    private ImageMetadataDto toImageMetadataDto(Image image) {
        return new ImageMetadataDto(
                image.getId(),
                buildCdnUrl(image, "original"),
                buildCdnUrl(image, "medium"),
                buildCdnUrl(image, "thumbnail"),
                image.getWidth(),
                image.getHeight(),
                image.getContentType()
        );
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
        ).flatMap(updated -> updated == 0
                ? Mono.error(ApiErrors.notFound(ErrorCode.IMAGE_NOT_FOUND, "Image not found"))
                : Mono.empty());
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
        return saveImages(tag, entityId, files, null);
    }

    @Override
    public Flux<Long> saveImages(ImageTag tag, Long entityId, List<FilePart> files, Long uploadedBy) {
        ImageStatus status = tag == ImageTag.PARTICIPANT ? ImageStatus.ACTIVE : ImageStatus.TEMPORARY;
        return Flux.fromIterable(files)
                .flatMap(file -> s3Service.uploadFile(file, tag))
                .map(result -> {
                    Image image = imageMapper.toImage(entityId, tag, result.filename(), status,
                            result.contentType(), result.width(), result.height());
                    image.setUploadedBy(uploadedBy);
                    return image;
                })
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
            case PRODUCT -> canEditProduct(entityId, participantId);
            case PARTICIPANT -> Mono.just(participantId.equals(entityId));
            case ORDER -> orderRepository.findById(entityId)
                    .map(order -> (order.getCustomerId().equals(participantId) || order.getSellerId().equals(participantId))
                            && order.getStatus() != OrderStatus.COMPLETED && order.getStatus() != OrderStatus.FAILED)
                    .defaultIfEmpty(false);
            case SYSTEM -> Mono.just(true);
        };
    }

    @Override
    @Transactional
    public Flux<Image> prepareExpiredImagesForDeletion() {
        return databaseClient.sql("""
                UPDATE image SET status = 'DELETE'
                WHERE (status = 'TEMPORARY' AND created_at <= now() - interval '24 hours')
                   OR (tag = 'PRODUCT' AND status = 'ACTIVE' AND EXISTS (
                       SELECT 1 FROM product p WHERE p.id = image.entity_id AND p.status = 'DELETED'
                   ))
                """)
                .fetch().rowsUpdated()
                .thenMany(databaseClient.sql("""
                SELECT id, filename, tag::text AS tag FROM image
                WHERE (status = 'DELETE' AND created_at <= now() - interval '24 hours')
                   OR (tag = 'PRODUCT' AND EXISTS (
                       SELECT 1 FROM product p WHERE p.id = image.entity_id AND p.status = 'DELETED'
                   ))
                """)
                .map((row, metadata) -> Image.builder()
                        .id(row.get("id", Long.class))
                        .filename(row.get("filename", String.class))
                        .tag(ImageTag.valueOf(row.get("tag", String.class)))
                        .build())
                .all());
    }

    @Override
    public Mono<Void> deleteMarkedImage(Long imageId) {
        return databaseClient.sql("DELETE FROM image WHERE id = :id AND status = 'DELETE'")
                .bind("id", imageId)
                .fetch().rowsUpdated().then();
    }

    @Override
    public Mono<Void> deleteById(Long id) {
        return imageRepository.deleteById(id);
    }

    @Override
    public Mono<Void> deleteImages(List<Long> imageIds, ImageTag tag, Long participantId) {
        if (tag == ImageTag.ORDER) {
            return Mono.error(ApiErrors.forbidden(ErrorCode.ACCESS_DENIED, "Изображения заказа нельзя удалить"));
        }
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
            case PRODUCT -> entityIds.contains(null)
                    ? Mono.just(false)
                    : Flux.fromIterable(entityIds)
                            .concatMap(entityId -> canEditProduct(entityId, participantId))
                            .all(Boolean::booleanValue);
            case ORDER -> Flux.fromIterable(entityIds)
                    .flatMap(orderRepository::findById)
                    .map(order -> order.getCustomerId().equals(participantId) || order.getSellerId().equals(participantId))
                    .all(Boolean::booleanValue);
            default -> Mono.just(false);
        };
    }

    private Mono<Boolean> canEditProduct(Long entityId, Long participantId) {
        return productRepository.findById(entityId)
                .filter(product -> product.getStatus() != ProductStatus.DELETED)
                .flatMap(product -> participantRepository.findById(participantId)
                        .flatMap(participant -> {
                            boolean admin = participant.getRole() == ParticipantRole.ADMIN;
                            if (Objects.equals(product.getParticipantId(), participantId)) {
                                return Mono.just(admin || (product.getStatus() == ProductStatus.ACTIVE
                                        && product.getAvailability() != ProductAvailabilityType.GIVEAWAY));
                            }
                            if (!admin) return Mono.just(false);
                            return participantRepository.findByIdAndIsAgentTrue(product.getParticipantId())
                                    .hasElement();
                        }))
                .defaultIfEmpty(false);
    }
}
