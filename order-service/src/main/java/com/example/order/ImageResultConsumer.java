package com.example.order;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

@Component
public class ImageResultConsumer {
    private final Path uploadDirectory;

    public ImageResultConsumer(@Value("${order.upload-directory:uploads}") String uploadDirectory) {
        this.uploadDirectory = Path.of(uploadDirectory).toAbsolutePath().normalize();
    }

    @KafkaListener(topics = "${order.image-result-topic:image-converted}", groupId = "order-image-results")
    public void receive(ConsumerRecord<String, byte[]> record) {
        String id = record.key();
        byte[] jpeg = record.value();
        try {
            UUID.fromString(id);
            if (jpeg == null || jpeg.length == 0) {
                throw new IllegalArgumentException("Converted image record is empty.");
            }
            Files.createDirectories(uploadDirectory);
            Files.write(uploadDirectory.resolve(id + ".jpg"), jpeg);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Converted image record has an invalid ID or payload.", e);
        } catch (IOException e) {
            throw new IllegalStateException("Could not save converted image " + id + ".", e);
        }
    }
}
