package com.model_store.service.impl;

import com.amazonaws.services.kms.model.NotFoundException;
import com.model_store.mapper.ImageMapper;
import com.model_store.model.base.Image;
import com.model_store.model.constant.ImageStatus;
import com.model_store.model.constant.ImageTag;
import com.model_store.model.dto.ImageResponse;
import com.model_store.repository.ImageRepository;
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

    public Flux<ImageResponse> findImagesByIds(List<Long> imageIds) {
        return findImagesById(imageIds)
                .flatMap(image -> s3Service.getFile(image.getTag(), image.getFilename()));
    }

    public Mono<Long> findMainImage(Long entityId, ImageTag tag) {
        return findActualImages(entityId, tag).next();
    }

    public Flux<Long> findActualImages(Long entityId, ImageTag tag) {
        return imageRepository.findActualIdsByEntity(entityId, tag);
    }

    @Transactional
    public Mono<Void> updateImagesStatus(List<Long> imageIds, Long entityId, ImageStatus status, ImageTag tag) {
        return Flux.fromIterable(imageIds)
                .flatMap(imageRepository::findById)
                .filter(image -> (isNull(tag) || image.getTag().equals(tag)) && (isNull(image.getEntityId()) || isNull(image.getEntityId()) || image.getEntityId().equals(entityId)))
                .switchIfEmpty(Mono.error(new NotFoundException("Image not found")))
                .flatMap(image -> {
                    image.setStatus(status);
                    if (!isNull(entityId)) {
                        image.setEntityId(entityId);
                    }
                    return imageRepository.save(image);
                }).then();
    }

    private @NotNull Flux<Image> findImagesById(List<Long> imageIds) {
        return Flux.fromIterable(imageIds)
                .flatMap(imageRepository::findById)
                .filter(image -> image.getStatus().equals(ImageStatus.ACTIVE));
    }


    public Flux<Long> saveImages(ImageTag tag, Long entityId, List<FilePart> files) {
        return Flux.fromIterable(files)
                .flatMap(file -> s3Service.uploadFile(file, tag))
                .map(name -> imageMapper.toImage(entityId, tag, name, ImageStatus.TEMPORARY))
                .flatMap(imageRepository::save)
                .map(Image::getId);
    }

    public Mono<Void> deleteImagesByEntityId(Long entityId, ImageTag tag) {
        return imageRepository.updateStatusById(entityId, tag, ImageStatus.DELETE);
    }

    @Transactional
    public Mono<Void> deleteImagesByParticipant(Long participantId) {
        return imageRepository.findIdsByEntityIdAndTag(participantId, ImageTag.PARTICIPANT)
                .collectList()
                .flatMap(imageIds -> updateImagesStatus(imageIds, null, ImageStatus.DELETE, ImageTag.PARTICIPANT))
                .then();
    }

    @Override
    public Mono<Boolean> isActualEntity(Long entityId, ImageTag tag) {
        if (isNull(entityId)) return Mono.just(true);
        return switch (tag) {
            case PARTICIPANT -> participantRepository.findActualParticipant(entityId).hasElement();
            case PRODUCT -> productRepository.findActualProduct(entityId).hasElement();
            case SYSTEM, ORDER -> Mono.just(true);
        };
    }

    @Override
    public Flux<Image> findTemporaryImages() {
        return imageRepository.findImagesToDelete();
    }

    public Mono<Void> deleteById(Long id) {
        return imageRepository.deleteById(id);
    }

    private Mono<Void> removeImages(List<Long> ids) {
        return findImagesById(ids) // Найдем изображения по id
                .flatMap(image -> s3Service.deleteFile(image.getTag(), image.getFilename())) // Удаляем файл из S3
                .then(Mono.defer(() -> imageRepository.deleteAllById(ids))) // Удаляем все записи из базы данных
                .onErrorResume(e -> {
                    // Здесь можно добавить обработку ошибок, например логирование или возврат ошибки
                    log.error("Ошибка при удалении изображений", e);
                    return Mono.error(new RuntimeException("Ошибка при удалении изображений", e));
                });
    }


}