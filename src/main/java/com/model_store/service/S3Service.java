package com.model_store.service;

import com.model_store.model.constant.ImageTag;
import com.model_store.model.dto.ImageResponse;
import org.springframework.http.codec.multipart.FilePart;
import reactor.core.publisher.Mono;

import java.util.List;

public interface S3Service {
    Mono<String> uploadFile(FilePart file, ImageTag tag);

    Mono<ImageResponse> getFile(ImageTag imageTag, String fileName);

    Mono<Void> deleteFile(ImageTag bucketName, String fileName);

    List<String> listFiles(String bucketName);

}
