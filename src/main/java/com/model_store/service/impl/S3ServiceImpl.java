package com.model_store.service.impl;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.DeleteObjectRequest;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.ObjectListing;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import com.model_store.mapper.ImageMapper;
import com.model_store.model.dto.ImageResponse;
import com.model_store.service.S3Service;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.List;
import java.util.stream.Collectors;

import static java.util.Objects.isNull;

@Service
@RequiredArgsConstructor
public class S3ServiceImpl implements S3Service {

    private final AmazonS3 amazonS3;
    private final ImageMapper imageMapper;

    /**
     * Метод для загрузки файла в MinIO.
     *
     * @param file - файл, который нужно загрузить.
     * @param key  - ключ (имя файла) в корзине.
     * @return путь к файлу
     */
    @SneakyThrows
    public Mono<String> uploadFile(FilePart file, String key) {
        return convertMultipartFileToFile(file)
                .flatMap(tempFile -> Mono.fromRunnable(() -> {
                    uploadFile(tempFile, key);
                    tempFile.delete(); // Удаляем временный файл после загрузки
                }).thenReturn(tempFile.getName()))
                .subscribeOn(Schedulers.boundedElastic()); // Используем отдельный пул потоков для блокирующих операций
    }

    /**
     * Метод для загрузки файла в MinIO.
     *
     * @param file       - файл, который нужно загрузить.
     * @param bucketName - ключ (имя файла) в корзине.
     */
    public void uploadFile(File file, String bucketName) {
        amazonS3.putObject(new PutObjectRequest(bucketName, file.getName(), file));
    }

    /**
     * Метод для получения списка объектов (файлов) в корзине.
     *
     * @return Список ключей файлов в корзине.
     */
    public List<String> listFiles(String bucketName) {
        ObjectListing objectListing = amazonS3.listObjects(bucketName);
        return objectListing.getObjectSummaries().stream()
                .map(S3ObjectSummary::getKey)
                .collect(Collectors.toList());
    }

    /**
     * Метод для получения файла по ключу.
     *
     * @param bucketName - название корзины
     * @param fileName   - ключ файла в корзине.
     * @return объект S3 с файлом.
     */
    @SneakyThrows
    public Mono<ImageResponse> getFile(String bucketName, String fileName) {
        return Mono.fromCallable(() -> amazonS3.getObject(new GetObjectRequest(bucketName, fileName)))
                .map(s3 -> imageMapper.toImageResponseDto(fileName, getContentType(s3), toByteArray(s3.getObjectContent())))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Метод для удаления файла из корзины MinIO.
     *
     * @param bucketName - название корзины
     * @param fileName   - ключ файла, который нужно удалить.
     */
    public Mono<Void> deleteFile(String bucketName, String fileName) {
        return Mono.fromCallable(() -> {
                    amazonS3.deleteObject(new DeleteObjectRequest(bucketName, fileName));
                    return Mono.empty();
                })
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    /**
     * Метод для конвертации MultipartFile в обычный File.
     *
     * @param file - MultipartFile, который нужно конвертировать.
     * @return - File, готовый для загрузки.
     */
    private Mono<File> convertMultipartFileToFile(FilePart file) {
        File tempFile = new File(System.getProperty("java.io.tmpdir") + "/" + file.filename());
        return file.transferTo(tempFile).then(Mono.just(tempFile));
    }

    @SneakyThrows
    private byte[] toByteArray(InputStream inputStream) {
        try (ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                byteArrayOutputStream.write(buffer, 0, bytesRead);
            }
            return byteArrayOutputStream.toByteArray();
        }
    }

    /**
     * Метод для получения contentType изображения из S3.
     * Если метаданные не могут быть получены, пытаемся определить тип по расширению файла.
     *
     * @param s3Object - полученный файл из корзины
     * @return contentType для изображения
     */
    private String getContentType(S3Object s3Object) {
        String contentType = s3Object.getObjectMetadata().getContentType();
        return isNull(contentType)
                ? getContentTypeFromExtension(s3Object.getKey())
                : contentType;
    }

    /**
     * Метод для определения contentType по расширению файла.
     *
     * @param fileName - имя файла
     * @return contentType (например, image/jpeg, image/png и т. д.)
     */
    private String getContentTypeFromExtension(String fileName) {
        if (fileName.endsWith(".jpg") || fileName.endsWith(".jpeg")) {
            return "image/jpeg";
        } else if (fileName.endsWith(".png")) {
            return "image/png";
        } else if (fileName.endsWith(".gif")) {
            return "image/gif";
        } else if (fileName.endsWith(".bmp")) {
            return "image/bmp";
        } else if (fileName.endsWith(".webp")) {
            return "image/webp";
        } else {
            return "application/octet-stream"; // Если тип не удалось определить, возвращаем общий тип
        }
    }
}
