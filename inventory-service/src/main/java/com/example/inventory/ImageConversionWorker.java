package com.example.inventory;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

@Component
public class ImageConversionWorker {
    private final KafkaTemplate<String, byte[]> kafkaTemplate;
    private final String outputTopic;

    public ImageConversionWorker(KafkaTemplate<String, byte[]> kafkaTemplate,
                                 @Value("${inventory.image-output-topic:image-converted}") String outputTopic) {
        this.kafkaTemplate = kafkaTemplate;
        this.outputTopic = outputTopic;
    }

    @KafkaListener(topics = "${inventory.image-input-topic:image-uploaded}")
    public void convert(ConsumerRecord<String, byte[]> record) {
        String imageId = record.key();
        if (imageId == null || record.value() == null) {
            throw new IllegalArgumentException("Image record must contain an ID and image bytes.");
        }

        try {
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(record.value()));
            if (source == null) {
                throw new IllegalArgumentException("Kafka record does not contain a readable image.");
            }

            // JPEG has no alpha channel. Composite transparent PNG pixels onto white.
            BufferedImage jpeg = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = jpeg.createGraphics();
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, jpeg.getWidth(), jpeg.getHeight());
            graphics.drawImage(source, 0, 0, null);
            graphics.dispose();

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!ImageIO.write(jpeg, "jpeg", output)) {
                throw new IllegalStateException("No JPEG encoder is available.");
            }
            kafkaTemplate.send(outputTopic, imageId, output.toByteArray()).get();
        } catch (IOException e) {
            throw new IllegalStateException("Could not convert image " + imageId + " to JPEG.", e);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IllegalStateException("Could not publish converted image " + imageId + ".", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while publishing converted image " + imageId + ".", e);
        }
    }
}
