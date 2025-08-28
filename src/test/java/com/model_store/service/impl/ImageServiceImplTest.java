package com.model_store.service.impl;

import com.model_store.mapper.ImageMapper;
import com.model_store.model.base.Image;
import com.model_store.model.base.Order;
import com.model_store.model.base.Product;
import com.model_store.model.constant.ImageStatus;
import com.model_store.model.constant.ImageTag;
import com.model_store.model.dto.ImageResponse;
import com.model_store.repository.ImageRepository;
import com.model_store.repository.OrderRepository;
import com.model_store.repository.ParticipantRepository;
import com.model_store.repository.ProductRepository;
import com.model_store.service.S3Service;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.codec.multipart.FilePart;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
@Deprecated
@ExtendWith(MockitoExtension.class)
class ImageServiceImplTest {

    @Mock
    private ImageRepository imageRepository;

    @Mock
    private S3Service s3Service;

    @Mock
    private ImageMapper imageMapper;

    @Mock
    private ParticipantRepository participantRepository; // Not directly used in tested methods so far but good to have

    @Mock
    private ProductRepository productRepository;

    @Mock
    private OrderRepository orderRepository;

    @InjectMocks
    private ImageServiceImpl imageService;

    private Image image1;
    private Image image2;
    private FilePart filePart1;

    @BeforeEach
    void setUp() {
        image1 = new Image();
        image1.setId(1L);
        image1.setEntityId(10L);
        image1.setTag(ImageTag.PRODUCT);
        image1.setStatus(ImageStatus.ACTIVE);
        image1.setFilename("image1.jpg");

        image2 = new Image();
        image2.setId(2L);
        image2.setEntityId(10L);
        image2.setTag(ImageTag.PRODUCT);
        image2.setStatus(ImageStatus.TEMPORARY);
        image2.setFilename("image2.png");

        filePart1 = mock(FilePart.class);
        when(filePart1.filename()).thenReturn("file1.jpg");
    }

    @Test
    void findImagesByIds_shouldReturnImageResponses_forActiveImages() {
        List<Long> imageIds = List.of(1L);
        ImageResponse imageResponse =  ImageResponse.builder().fileName("image1.jpg")
                .imageData( "content".getBytes())
                .build();

                when(imageRepository.findById(1L)).thenReturn(Mono.just(image1)); // image1 is ACTIVE
        when(s3Service.getFile(ImageTag.PRODUCT, "image1.jpg")).thenReturn(Mono.just(imageResponse));

        StepVerifier.create(imageService.findImagesByIds(imageIds))
                .expectNext(imageResponse)
                .verifyComplete();

        verify(imageRepository).findById(1L);
        verify(s3Service).getFile(ImageTag.PRODUCT, "image1.jpg");
    }

    @Test
    void findImagesByIds_shouldFilterOutNonActiveImages() {
        List<Long> imageIds = List.of(2L); // image2 is TEMPORARY

        when(imageRepository.findById(2L)).thenReturn(Mono.just(image2));
        // s3Service.getFile should not be called for non-active images

        StepVerifier.create(imageService.findImagesByIds(imageIds))
                .verifyComplete(); // Expect empty due to filtering

        verify(imageRepository).findById(2L);
        verify(s3Service, never()).getFile(any(ImageTag.class), anyString());
    }

    @Test
    void findImagesByIds_shouldReturnEmpty_whenNoImagesFound() {
        List<Long> imageIds = List.of(3L);
        when(imageRepository.findById(3L)).thenReturn(Mono.empty());

        StepVerifier.create(imageService.findImagesByIds(imageIds))
                .verifyComplete();

        verify(imageRepository).findById(3L);
        verify(s3Service, never()).getFile(any(ImageTag.class), anyString());
    }

    @Test
    void findMainImage_shouldReturnFirstImageId_whenImagesExist() {
        Long entityId = 10L;
        ImageTag tag = ImageTag.PRODUCT;
        when(imageRepository.findActualIdsByEntity(entityId, tag)).thenReturn(Flux.just(1L, 2L)); // Returns multiple IDs

        StepVerifier.create(imageService.findMainImage(entityId, tag))
                .expectNext(1L) // Should return the first one
                .verifyComplete();

        verify(imageRepository).findActualIdsByEntity(entityId, tag);
    }

    @Test
    void findMainImage_shouldReturnEmpty_whenNoImagesExist() {
        Long entityId = 10L;
        ImageTag tag = ImageTag.PRODUCT;
        when(imageRepository.findActualIdsByEntity(entityId, tag)).thenReturn(Flux.empty());

        StepVerifier.create(imageService.findMainImage(entityId, tag))
                .verifyComplete();

        verify(imageRepository).findActualIdsByEntity(entityId, tag);
    }

    @Test
    void findActualImages_shouldReturnFluxOfImageIds() {
        Long entityId = 10L;
        ImageTag tag = ImageTag.PRODUCT;
        when(imageRepository.findActualIdsByEntity(entityId, tag)).thenReturn(Flux.just(1L, 2L, 3L));

        StepVerifier.create(imageService.findActualImages(entityId, tag))
                .expectNext(1L)
                .expectNext(2L)
                .expectNext(3L)
                .verifyComplete();

        verify(imageRepository).findActualIdsByEntity(entityId, tag);
    }

    @Test
    void findActualImages_shouldReturnEmptyFlux_whenNoImagesFound() {
        Long entityId = 10L;
        ImageTag tag = ImageTag.PRODUCT;
        when(imageRepository.findActualIdsByEntity(entityId, tag)).thenReturn(Flux.empty());

        StepVerifier.create(imageService.findActualImages(entityId, tag))
                .verifyComplete();

        verify(imageRepository).findActualIdsByEntity(entityId, tag);
    }

    @Test
    void updateImagesStatus_shouldUpdateStatus_whenImageFoundAndMatchesCriteria() {
        List<Long> imageIds = List.of(1L);
        Long entityId = 10L;
        ImageStatus newStatus = ImageStatus.DELETE;
        ImageTag tag = ImageTag.PRODUCT;

        Image updatedImage = new Image();
        updatedImage.setId(1L);
        updatedImage.setEntityId(entityId);
        updatedImage.setTag(tag);
        updatedImage.setStatus(newStatus); // Status is updated
        updatedImage.setFilename("image1.jpg");


        when(imageRepository.findById(1L)).thenReturn(Mono.just(image1)); // image1 has entityId 10L, tag PRODUCT
        when(imageRepository.save(any(Image.class))).thenAnswer(invocation -> {
            Image saved = invocation.getArgument(0);
            if (saved.getId().equals(1L) && saved.getStatus().equals(newStatus) && saved.getEntityId().equals(entityId)) {
                return Mono.just(saved);
            }
            return Mono.error(new AssertionError("Save condition not met"));
        });

        StepVerifier.create(imageService.updateImagesStatus(imageIds, entityId, newStatus, tag))
                .verifyComplete();

        verify(imageRepository).findById(1L);
        verify(imageRepository).save(any(Image.class));
    }

    @Test
    void updateImagesStatus_shouldNotUpdate_whenImageTagDoesNotMatch() {
        List<Long> imageIds = List.of(1L);
        Long entityId = 10L;
        ImageStatus newStatus = ImageStatus.DELETE;
        ImageTag differentTag = ImageTag.PARTICIPANT; // Different tag

        when(imageRepository.findById(1L)).thenReturn(Mono.just(image1)); // image1 has tag PRODUCT

        StepVerifier.create(imageService.updateImagesStatus(imageIds, entityId, newStatus, differentTag))
                .expectError(com.amazonaws.services.kms.model.NotFoundException.class) // Due to switchIfEmpty
                .verify();

        verify(imageRepository).findById(1L);
        verify(imageRepository, never()).save(any(Image.class));
    }

    @Test
    void updateImagesStatus_shouldNotUpdate_whenImageEntityIdDoesNotMatch() {
        List<Long> imageIds = List.of(1L);
        Long differentEntityId = 20L; // Different entityId
        ImageStatus newStatus = ImageStatus.DELETE;
        ImageTag tag = ImageTag.PRODUCT;

        when(imageRepository.findById(1L)).thenReturn(Mono.just(image1)); // image1 has entityId 10L

        StepVerifier.create(imageService.updateImagesStatus(imageIds, differentEntityId, newStatus, tag))
                .expectError(com.amazonaws.services.kms.model.NotFoundException.class) // Due to switchIfEmpty
                .verify();

        verify(imageRepository).findById(1L);
        verify(imageRepository, never()).save(any(Image.class));
    }
    
    @Test
    void updateImagesStatus_shouldUpdateEntityId_whenEntityIdIsNotNullInImage() {
        List<Long> imageIds = List.of(1L);
        Long newEntityId = 20L; // new entity id to set
        ImageStatus newStatus = ImageStatus.ACTIVE;
        ImageTag tag = ImageTag.PRODUCT;

        Image imageWithNullEntityId = new Image();
        imageWithNullEntityId.setId(1L);
        imageWithNullEntityId.setEntityId(null); // Initial entityId is null
        imageWithNullEntityId.setTag(tag);
        imageWithNullEntityId.setStatus(ImageStatus.TEMPORARY);
        imageWithNullEntityId.setFilename("image1.jpg");

        when(imageRepository.findById(1L)).thenReturn(Mono.just(imageWithNullEntityId));
        when(imageRepository.save(any(Image.class))).thenAnswer(invocation -> {
            Image saved = invocation.getArgument(0);
            // Verify that entityId is updated
            if (saved.getId().equals(1L) && saved.getStatus().equals(newStatus) && newEntityId.equals(saved.getEntityId())) {
                return Mono.just(saved);
            }
            return Mono.error(new AssertionError("Save condition not met: entityId not updated as expected or other fields incorrect."));
        });

        StepVerifier.create(imageService.updateImagesStatus(imageIds, newEntityId, newStatus, tag))
                .verifyComplete();

        verify(imageRepository).findById(1L);
        verify(imageRepository).save(any(Image.class));
    }


    @Test
    void updateImagesStatus_shouldFail_whenImageNotFoundInRepository() {
        List<Long> imageIds = List.of(3L); // Non-existent image
        Long entityId = 10L;
        ImageStatus newStatus = ImageStatus.DELETE;
        ImageTag tag = ImageTag.PRODUCT;

        when(imageRepository.findById(3L)).thenReturn(Mono.empty());

        StepVerifier.create(imageService.updateImagesStatus(imageIds, entityId, newStatus, tag))
                .expectError(com.amazonaws.services.kms.model.NotFoundException.class)
                .verify();

        verify(imageRepository).findById(3L);
        verify(imageRepository, never()).save(any(Image.class));
    }

    @Test
    void saveImages_shouldUploadAndSaveImageDetails() {
        ImageTag tag = ImageTag.PRODUCT;
        Long entityId = 10L;
        List<FilePart> files = List.of(filePart1);
        String uploadedFilename = "s3_uploaded_filename.jpg";

        when(s3Service.uploadFile(filePart1, tag)).thenReturn(Mono.just(uploadedFilename));
        when(imageMapper.toImage(entityId, tag, uploadedFilename, ImageStatus.TEMPORARY)).thenReturn(image1); // Assuming TEMPORARY for PRODUCT
        when(imageRepository.save(image1)).thenReturn(Mono.just(image1));

        StepVerifier.create(imageService.saveImages(tag, entityId, files))
                .expectNext(image1.getId())
                .verifyComplete();

        verify(s3Service).uploadFile(filePart1, tag);
        verify(imageMapper).toImage(entityId, tag, uploadedFilename, ImageStatus.TEMPORARY);
        verify(imageRepository).save(image1);
    }

    @Test
    void saveImages_shouldSetStatusToActive_forParticipantTag() {
        ImageTag tag = ImageTag.PARTICIPANT;
        Long entityId = 1L; // Participant ID
        List<FilePart> files = List.of(filePart1);
        String uploadedFilename = "s3_uploaded_participant_image.jpg";
        Image participantImage = new Image();
        participantImage.setId(3L);
        participantImage.setEntityId(entityId);
        participantImage.setTag(tag);
        participantImage.setStatus(ImageStatus.ACTIVE); // Should be ACTIVE
        participantImage.setFilename(uploadedFilename);

        when(s3Service.uploadFile(filePart1, tag)).thenReturn(Mono.just(uploadedFilename));
        when(imageMapper.toImage(entityId, tag, uploadedFilename, ImageStatus.ACTIVE)).thenReturn(participantImage);
        when(imageRepository.save(participantImage)).thenReturn(Mono.just(participantImage));

        StepVerifier.create(imageService.saveImages(tag, entityId, files))
                .expectNext(participantImage.getId())
                .verifyComplete();

        verify(s3Service).uploadFile(filePart1, tag);
        verify(imageMapper).toImage(entityId, tag, uploadedFilename, ImageStatus.ACTIVE);
        verify(imageRepository).save(participantImage);
    }

    @Test
    void deleteImagesByEntityId_shouldUpdateStatusToDelete() {
        Long entityId = 10L;
        ImageTag tag = ImageTag.PRODUCT;
        when(imageRepository.updateStatusById(entityId, tag, ImageStatus.DELETE)).thenReturn(Mono.empty());

        StepVerifier.create(imageService.deleteImagesByEntityId(entityId, tag))
                .verifyComplete();

        verify(imageRepository).updateStatusById(entityId, tag, ImageStatus.DELETE);
    }

    @Test
    void isActualEntity_shouldReturnTrue_forProductTagAndActualProduct() {
        Long entityId = 10L;
        Long participantId = 1L;
        ImageTag tag = ImageTag.PRODUCT;
        Product product = new Product(); // Assume this is an actual product
        when(productRepository.findActualProduct(entityId)).thenReturn(Mono.just(product));

        StepVerifier.create(imageService.isActualEntity(entityId, tag, participantId))
                .expectNext(true)
                .verifyComplete();

        verify(productRepository).findActualProduct(entityId);
    }

    @Test
    void isActualEntity_shouldReturnFalse_forProductTagAndNoProduct() {
        Long entityId = 10L;
        Long participantId = 1L;
        ImageTag tag = ImageTag.PRODUCT;
        when(productRepository.findActualProduct(entityId)).thenReturn(Mono.empty());

        StepVerifier.create(imageService.isActualEntity(entityId, tag, participantId))
                .expectNext(false)
                .verifyComplete();

        verify(productRepository).findActualProduct(entityId);
    }

    @Test
    void isActualEntity_shouldReturnTrue_forParticipantTagAndMatchingIds() {
        Long entityId = 1L; // Same as participantId
        Long participantId = 1L;
        ImageTag tag = ImageTag.PARTICIPANT;

        StepVerifier.create(imageService.isActualEntity(entityId, tag, participantId))
                .expectNext(true)
                .verifyComplete();
    }

    @Test
    void isActualEntity_shouldReturnFalse_forParticipantTagAndNonMatchingIds() {
        Long entityId = 2L; // Different from participantId
        Long participantId = 1L;
        ImageTag tag = ImageTag.PARTICIPANT;

        StepVerifier.create(imageService.isActualEntity(entityId, tag, participantId))
                .expectNext(false)
                .verifyComplete();
    }
    
    @Test
    void isActualEntity_shouldReturnTrue_forSystemTag() {
        Long entityId = 1L; 
        Long participantId = 1L;
        ImageTag tag = ImageTag.SYSTEM;

        StepVerifier.create(imageService.isActualEntity(entityId, tag, participantId))
                .expectNext(true)
                .verifyComplete();
    }

    @Test
    void isActualEntity_shouldReturnTrue_forOrderTag() {
        Long entityId = 1L; 
        Long participantId = 1L;
        ImageTag tag = ImageTag.ORDER;

        StepVerifier.create(imageService.isActualEntity(entityId, tag, participantId))
                .expectNext(true)
                .verifyComplete();
    }
    
    @Test
    void isActualEntity_shouldReturnTrue_whenEntityIdIsNull() {
        Long entityId = null; 
        Long participantId = 1L;
        ImageTag tag = ImageTag.PRODUCT; // Any tag

        StepVerifier.create(imageService.isActualEntity(entityId, tag, participantId))
                .expectNext(true)
                .verifyComplete();
    }


    @Test
    void findTemporaryImages_shouldReturnFluxOfImages() {
        when(imageRepository.findImagesToDelete()).thenReturn(Flux.just(image1, image2)); // Assuming these are temporary

        StepVerifier.create(imageService.findTemporaryImages())
                .expectNext(image1)
                .expectNext(image2)
                .verifyComplete();

        verify(imageRepository).findImagesToDelete();
    }

    @Test
    void deleteById_shouldCallRepositoryDelete() {
        Long imageId = 1L;
        when(imageRepository.deleteById(imageId)).thenReturn(Mono.empty());

        StepVerifier.create(imageService.deleteById(imageId))
                .verifyComplete();

        verify(imageRepository).deleteById(imageId);
    }

    @Test
    void deleteImages_shouldUpdateStatusToDelete_forParticipantOwnedImages() {
        List<Long> imageIds = List.of(1L);
        ImageTag tag = ImageTag.PARTICIPANT;
        Long participantId = 10L; // image1.entityId is 10L, which is participantId here

        image1.setTag(ImageTag.PARTICIPANT); // Ensure correct tag for this test case

        when(imageRepository.findByIdAndTag(1L, tag)).thenReturn(Flux.just(image1));
        // isUserAccessible for PARTICIPANT will check if image.entityId matches participantId
        when(imageRepository.saveAll(anyList())).thenAnswer(invocation -> {
            List<Image> imagesToSave = invocation.getArgument(0);
            if (imagesToSave.size() == 1 && imagesToSave.get(0).getId().equals(1L) && imagesToSave.get(0).getStatus().equals(ImageStatus.DELETE)) {
                return Flux.fromIterable(imagesToSave);
            }
            return Flux.error(new AssertionError("SaveAll condition not met"));
        });


        StepVerifier.create(imageService.deleteImages(imageIds, tag, participantId))
                .verifyComplete();

        verify(imageRepository).findByIdAndTag(1L, tag);
        verify(imageRepository).saveAll(anyList());
    }

    @Test
    void deleteImages_shouldNotUpdate_whenParticipantDoesNotOwnImageForParticipantTag() {
        List<Long> imageIds = List.of(1L);
        ImageTag tag = ImageTag.PARTICIPANT;
        Long otherParticipantId = 99L; // Different participant

        image1.setTag(ImageTag.PARTICIPANT);
        image1.setEntityId(10L); // Image belongs to participant 10L

        when(imageRepository.findByIdAndTag(1L, tag)).thenReturn(Flux.just(image1));
        // isUserAccessible will return false

        StepVerifier.create(imageService.deleteImages(imageIds, tag, otherParticipantId))
                .verifyComplete(); // Should complete without error but not save

        verify(imageRepository).findByIdAndTag(1L, tag);
        verify(imageRepository, never()).saveAll(anyList());
    }


    @Test
    void deleteImages_shouldUpdateStatusToDelete_forProductOwnedByParticipant() {
        List<Long> imageIds = List.of(1L);
        ImageTag tag = ImageTag.PRODUCT;
        Long participantId = 1L; // Participant who owns the product

        Product product = new Product();
        product.setId(10L); // image1.entityId
        product.setParticipantId(participantId);

        image1.setTag(ImageTag.PRODUCT); // Ensure correct tag
        image1.setEntityId(product.getId());


        when(imageRepository.findByIdAndTag(1L, tag)).thenReturn(Flux.just(image1));
        when(productRepository.findActualProduct(product.getId())).thenReturn(Mono.just(product));
        when(imageRepository.saveAll(anyList())).thenAnswer(invocation -> Flux.fromIterable(invocation.getArgument(0)));

        StepVerifier.create(imageService.deleteImages(imageIds, tag, participantId))
                .verifyComplete();

        verify(imageRepository).findByIdAndTag(1L, tag);
        verify(productRepository).findActualProduct(product.getId());
        verify(imageRepository).saveAll(anyList());
    }
    
    @Test
    void deleteImages_shouldNotUpdate_whenProductNotOwnedByParticipant() {
        List<Long> imageIds = List.of(1L);
        ImageTag tag = ImageTag.PRODUCT;
        Long participantId = 1L; 
        Long otherParticipantId = 2L; // Product owned by another participant

        Product product = new Product();
        product.setId(10L); 
        product.setParticipantId(otherParticipantId);

        image1.setTag(ImageTag.PRODUCT); 
        image1.setEntityId(product.getId());

        when(imageRepository.findByIdAndTag(1L, tag)).thenReturn(Flux.just(image1));
        when(productRepository.findActualProduct(product.getId())).thenReturn(Mono.just(product));
        // isUserAccessible will return false

        StepVerifier.create(imageService.deleteImages(imageIds, tag, participantId))
                .verifyComplete();

        verify(imageRepository).findByIdAndTag(1L, tag);
        verify(productRepository).findActualProduct(product.getId());
        verify(imageRepository, never()).saveAll(anyList());
    }


    @Test
    void deleteImages_shouldFail_whenImageNotFound() {
        List<Long> imageIds = List.of(99L); // Non-existent image
        ImageTag tag = ImageTag.PRODUCT;
        Long participantId = 1L;

        when(imageRepository.findByIdAndTag(99L, tag)).thenReturn(Flux.empty());

        StepVerifier.create(imageService.deleteImages(imageIds, tag, participantId))
                .expectError(com.model_store.exception.EntityNotFoundException.class)
                .verify();

        verify(imageRepository).findByIdAndTag(99L, tag);
        verify(imageRepository, never()).saveAll(anyList());
    }
    
    @Test
    void deleteImages_shouldUpdateStatusToDelete_forOrderAccessibleByParticipant_AsCustomer() {
        List<Long> imageIds = List.of(1L);
        ImageTag tag = ImageTag.ORDER;
        Long participantId = 1L; // This participant is the customer

        Order order = new Order();
        order.setId(20L); // image1.entityId will be set to this
        order.setCustomerId(participantId);
        order.setSellerId(2L); // Some other seller

        image1.setTag(ImageTag.ORDER);
        image1.setEntityId(order.getId());

        when(imageRepository.findByIdAndTag(1L, tag)).thenReturn(Flux.just(image1));
        when(orderRepository.findById(order.getId())).thenReturn(Mono.just(order));
        when(imageRepository.saveAll(anyList())).thenAnswer(invocation -> Flux.fromIterable(invocation.getArgument(0)));

        StepVerifier.create(imageService.deleteImages(imageIds, tag, participantId))
                .verifyComplete();

        verify(imageRepository).findByIdAndTag(1L, tag);
        verify(orderRepository).findById(order.getId());
        verify(imageRepository).saveAll(anyList());
    }

    @Test
    void deleteImages_shouldUpdateStatusToDelete_forOrderAccessibleByParticipant_AsSeller() {
        List<Long> imageIds = List.of(1L);
        ImageTag tag = ImageTag.ORDER;
        Long participantId = 2L; // This participant is the seller

        Order order = new Order();
        order.setId(20L);
        order.setCustomerId(1L); // Some other customer
        order.setSellerId(participantId);

        image1.setTag(ImageTag.ORDER);
        image1.setEntityId(order.getId());

        when(imageRepository.findByIdAndTag(1L, tag)).thenReturn(Flux.just(image1));
        when(orderRepository.findById(order.getId())).thenReturn(Mono.just(order));
        when(imageRepository.saveAll(anyList())).thenAnswer(invocation -> Flux.fromIterable(invocation.getArgument(0)));

        StepVerifier.create(imageService.deleteImages(imageIds, tag, participantId))
                .verifyComplete();

        verify(imageRepository).findByIdAndTag(1L, tag);
        verify(orderRepository).findById(order.getId());
        verify(imageRepository).saveAll(anyList());
    }

    @Test
    void deleteImages_shouldNotUpdate_whenOrderNotAccessibleByParticipant() {
        List<Long> imageIds = List.of(1L);
        ImageTag tag = ImageTag.ORDER;
        Long participantId = 3L; // Neither customer nor seller

        Order order = new Order();
        order.setId(20L);
        order.setCustomerId(1L);
        order.setSellerId(2L);

        image1.setTag(ImageTag.ORDER);
        image1.setEntityId(order.getId());

        when(imageRepository.findByIdAndTag(1L, tag)).thenReturn(Flux.just(image1));
        when(orderRepository.findById(order.getId())).thenReturn(Mono.just(order));
        // isUserAccessible will be false

        StepVerifier.create(imageService.deleteImages(imageIds, tag, participantId))
                .verifyComplete(); // Completes because filter simply results in empty

        verify(imageRepository).findByIdAndTag(1L, tag);
        verify(orderRepository).findById(order.getId());
        verify(imageRepository, never()).saveAll(anyList());
    }

}
