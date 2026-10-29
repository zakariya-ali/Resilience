package com.example.order;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.UUID;

@RestController
public class ImageUploadController {
    private final Path uploadDirectory;
    private final KafkaTemplate<String, byte[]> kafkaTemplate;
    private final String imageTopic;
    private final ImageJobRepository imageJobs;

    public ImageUploadController(@Value("${order.upload-directory:uploads}") String uploadDirectory,
                                 @Value("${order.image-topic:image-uploaded}") String imageTopic,
                                 KafkaTemplate<String, byte[]> kafkaTemplate,
                                 ImageJobRepository imageJobs) {
        this.uploadDirectory = Path.of(uploadDirectory).toAbsolutePath().normalize();
        this.imageTopic = imageTopic;
        this.kafkaTemplate = kafkaTemplate;
        this.imageJobs = imageJobs;
    }

    @PostMapping(path = "/api/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public UploadResponse upload(@RequestPart("image") MultipartFile image) {
        if (image.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a PNG image to upload.");
        }

        try {
            byte[] content = image.getBytes();
            var input = ImageIO.createImageInputStream(new ByteArrayInputStream(content));
            var readers = ImageIO.getImageReaders(input);
            boolean isPng = readers.hasNext() && "png".equalsIgnoreCase(readers.next().getFormatName());
            if (!isPng) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The uploaded file must be a PNG image.");
            }

            Files.createDirectories(uploadDirectory);
            UUID jobId = UUID.randomUUID();
            String id = jobId.toString();
            Files.write(uploadDirectory.resolve(id + ".png"), content);
            imageJobs.queued(jobId, content.length);
            kafkaTemplate.send(imageTopic, id, content).get(10, TimeUnit.SECONDS);
            return new UploadResponse(id, "QUEUED", "PNG uploaded and sent to Kafka for conversion.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Interrupted while sending image to Kafka.", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Could not send image to Kafka.", e);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not save the uploaded image.", e);
        }
    }

    @GetMapping("/api/images/{id}")
    public ImageStatus status(@PathVariable String id) {
        Path jpeg = jpegPath(id);
        if (Files.exists(jpeg)) {
            return new ImageStatus(id, "COMPLETED", "JPEG conversion is complete.");
        }
        if (Files.exists(uploadDirectory.resolve(id + ".png"))) {
            return new ImageStatus(id, "PROCESSING", "Inventory is converting the PNG.");
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Image job not found.");
    }

    @GetMapping("/api/images/{id}/jpeg")
    public ResponseEntity<byte[]> downloadJpeg(@PathVariable String id) {
        Path jpeg = jpegPath(id);
        try {
            if (!Files.exists(jpeg)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "JPEG is not ready yet.");
            }
            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_JPEG)
                    .header("Content-Disposition", "attachment; filename=\"" + id + ".jpg\"")
                    .body(Files.readAllBytes(jpeg));
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not read converted image.", e);
        }
    }

    private Path jpegPath(String id) {
        try {
            UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Image job not found.");
        }
        return uploadDirectory.resolve(id + ".jpg");
    }

    public record UploadResponse(String id, String status, String message) {}
    public record ImageStatus(String id, String status, String message) {}
}
