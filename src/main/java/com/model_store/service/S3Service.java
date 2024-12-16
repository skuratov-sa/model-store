package com.model_store.service;

import com.model_store.model.dto.ImageResponse;
import org.springframework.http.codec.multipart.FilePart;
import reactor.core.publisher.Mono;

import java.io.File;
import java.util.List;

public interface S3Service {
    Mono<String> uploadFile(FilePart file, String key);

    void uploadFile(File file, String bucketName);

    Mono<ImageResponse> getFile(String bucketName, String fileName);

    Mono<Void> deleteFile(String bucketName, String fileName);

    List<String> listFiles(String bucketName);

}
