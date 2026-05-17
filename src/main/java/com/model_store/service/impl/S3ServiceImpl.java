package com.model_store.service.impl;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.DeleteObjectRequest;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.ObjectListing;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import com.model_store.configuration.property.S3ConfigurationProperties;
import com.model_store.mapper.ImageMapper;
import com.model_store.model.constant.ImageTag;
import com.model_store.model.dto.ImageResponse;
import com.model_store.service.S3Service;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.imgscalr.Scalr;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static java.util.Objects.isNull;

@Service
@RequiredArgsConstructor
public class S3ServiceImpl implements S3Service {

    private static final int MEDIUM_BOX = 1200;
    private static final int THUMBNAIL_BOX = 400;
    private static final double MEDIUM_QUALITY = 0.85;
    private static final double THUMBNAIL_QUALITY = 0.80;

    private static final String ORIGINAL = "original";
    private static final String MEDIUM = "medium";
    private static final String THUMBNAIL = "thumbnail";

    private final AmazonS3 amazonS3;
    private final ImageMapper imageMapper;
    private final S3ConfigurationProperties s3properties;

    @Override
    public Mono<UploadResult> uploadFile(FilePart file, ImageTag tag) {
        return convertMultipartFileToFile(file)
                .flatMap(tempFile ->
                        Mono.fromCallable(() -> processAndUpload(file, tempFile, tag))
                                .subscribeOn(Schedulers.boundedElastic())
                );
    }

    @SneakyThrows
    private UploadResult processAndUpload(FilePart file, File tempFile, ImageTag tag) {
        try {
            BufferedImage original = ImageIO.read(tempFile);
            if (isNull(original)) {
                throw new IllegalArgumentException("Cannot read image file: " + file.filename());
            }

            String basename = UUID.randomUUID().toString().substring(0, 10) + ".jpg";
            String bucket = resolveBucket(tag);
            String tmpDir = System.getProperty("java.io.tmpdir");

            uploadAsJpeg(original, bucket, ORIGINAL,   basename, 1.0,            tmpDir);
            uploadAsJpeg(resize(original, MEDIUM_BOX),    bucket, MEDIUM,    basename, MEDIUM_QUALITY,    tmpDir);
            uploadAsJpeg(resize(original, THUMBNAIL_BOX), bucket, THUMBNAIL, basename, THUMBNAIL_QUALITY, tmpDir);

            return new UploadResult(basename, original.getWidth(), original.getHeight(), "image/jpeg");
        } finally {
            tempFile.delete();
        }
    }

    private BufferedImage resize(BufferedImage img, int box) {
        if (img.getWidth() > box || img.getHeight() > box) {
            return Scalr.resize(img, Scalr.Method.ULTRA_QUALITY, Scalr.Mode.AUTOMATIC, box, box);
        }
        return img;
    }

    @SneakyThrows
    private void uploadAsJpeg(BufferedImage img, String bucket, String variant, String basename,
                               double quality, String tmpDir) {
        File dest = new File(tmpDir, variant + "_" + basename);
        try {
            writeJpeg(toRgb(img), dest, quality);
            amazonS3.putObject(new PutObjectRequest(bucket, variant + "/" + basename, dest));
        } finally {
            dest.delete();
        }
    }

    @SneakyThrows
    private void writeJpeg(BufferedImage img, File dest, double quality) {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality((float) quality);
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(dest)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(img, null, null), param);
        } finally {
            writer.dispose();
        }
    }

    private BufferedImage toRgb(BufferedImage img) {
        if (img.getType() == BufferedImage.TYPE_INT_RGB) return img;
        BufferedImage rgb = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        g.drawImage(img, 0, 0, Color.WHITE, null);
        g.dispose();
        return rgb;
    }

    @Override
    public List<String> listFiles(String bucketName) {
        ObjectListing objectListing = amazonS3.listObjects(bucketName);
        return objectListing.getObjectSummaries().stream()
                .map(S3ObjectSummary::getKey)
                .collect(Collectors.toList());
    }

    @SneakyThrows
    @Override
    public Mono<ImageResponse> getFile(ImageTag imageTag, String fileName) {
        return Mono.fromCallable(() -> amazonS3.getObject(new GetObjectRequest(resolveBucket(imageTag), fileName)))
                .map(s3 -> imageMapper.toImageResponseDto(fileName, getContentType(s3), toByteArray(s3.getObjectContent())))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Void> deleteFile(ImageTag tag, String fileName) {
        return Mono.fromCallable(() -> {
                    String bucket = resolveBucket(tag);
                    amazonS3.deleteObject(new DeleteObjectRequest(bucket, ORIGINAL + "/" + fileName));
                    amazonS3.deleteObject(new DeleteObjectRequest(bucket, MEDIUM + "/" + fileName));
                    amazonS3.deleteObject(new DeleteObjectRequest(bucket, THUMBNAIL + "/" + fileName));
                    return null;
                })
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    private Mono<File> convertMultipartFileToFile(FilePart file) {
        String uniqueId = UUID.randomUUID().toString().substring(0, 10);
        File tempFile = new File(System.getProperty("java.io.tmpdir") + "/" + uniqueId + "_" + file.filename());
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

    private String getContentType(S3Object s3Object) {
        String contentType = s3Object.getObjectMetadata().getContentType();
        return isNull(contentType) ? getContentTypeFromExtension(s3Object.getKey()) : contentType;
    }

    private String getContentTypeFromExtension(String fileName) {
        if (fileName.endsWith(".jpg") || fileName.endsWith(".jpeg")) return "image/jpeg";
        if (fileName.endsWith(".png")) return "image/png";
        if (fileName.endsWith(".gif")) return "image/gif";
        if (fileName.endsWith(".bmp")) return "image/bmp";
        if (fileName.endsWith(".webp")) return "image/webp";
        return "application/octet-stream";
    }

    public String resolveBucket(ImageTag tag) {
        return switch (tag) {
            case PARTICIPANT -> s3properties.getParticipantBucketName();
            case PRODUCT -> s3properties.getProductBucketName();
            case ORDER -> s3properties.getOrderBucketName();
            case SYSTEM -> s3properties.getSystemBucketName();
        };
    }
}
