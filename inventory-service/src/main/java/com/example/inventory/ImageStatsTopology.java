package com.example.inventory;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.Grouped;
import org.apache.kafka.streams.kstream.JoinWindows;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.Produced;
import org.apache.kafka.streams.kstream.StreamJoined;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafkaStreams;
import org.springframework.kafka.config.TopicBuilder;

import java.time.Duration;

/**
 * Kafka Streams topology that watches the conversion pipeline without touching the image bytes it forwards.
 * - Joins each uploaded PNG with its converted JPEG (same key) and writes a CSV audit line
 *   "id,pngBytes,jpegBytes,ratio" to the audit topic. Kafka Connect sinks that topic to a file.
 * - Keeps a running count of completed conversions and publishes it to the count topic.
 */
@Configuration
@EnableKafkaStreams
public class ImageStatsTopology {

    // Kafka Streams fails on startup if a source topic is missing, so create both inputs up front.
    @Bean
    public NewTopic imageUploadedTopic(@Value("${inventory.image-input-topic:image-uploaded}") String name) {
        return TopicBuilder.name(name).partitions(1).replicas(1).build();
    }

    @Bean
    public NewTopic imageConvertedTopic(@Value("${inventory.image-output-topic:image-converted}") String name) {
        return TopicBuilder.name(name).partitions(1).replicas(1).build();
    }

    @Bean
    public KStream<String, String> imageAuditStream(StreamsBuilder builder,
                                                    @Value("${inventory.image-input-topic:image-uploaded}") String inputTopic,
                                                    @Value("${inventory.image-output-topic:image-converted}") String outputTopic,
                                                    @Value("${inventory.image-audit-topic:image-audit}") String auditTopic,
                                                    @Value("${inventory.image-count-topic:image-conversion-count}") String countTopic) {
        // Only the sizes are needed, so drop the payloads right after reading to keep join state small.
        KStream<String, Long> pngSizes = builder
                .stream(inputTopic, Consumed.with(Serdes.String(), Serdes.ByteArray()))
                .mapValues(bytes -> bytes == null ? 0L : (long) bytes.length);
        KStream<String, Long> jpegSizes = builder
                .stream(outputTopic, Consumed.with(Serdes.String(), Serdes.ByteArray()))
                .mapValues(bytes -> bytes == null ? 0L : (long) bytes.length);

        KStream<String, String> audit = pngSizes.join(
                jpegSizes,
                (png, jpeg) -> String.format("%d,%d,%.3f", png, jpeg, png == 0 ? 0.0 : (double) jpeg / png),
                JoinWindows.ofTimeDifferenceWithNoGrace(Duration.ofMinutes(5)),
                StreamJoined.with(Serdes.String(), Serdes.Long(), Serdes.Long()));

        audit.map((id, stats) -> KeyValue.pair(id, id + "," + stats))
                .to(auditTopic, Produced.with(Serdes.String(), Serdes.String()));

        audit.groupBy((id, stats) -> "total", Grouped.with(Serdes.String(), Serdes.String()))
                .count()
                .toStream()
                .mapValues(String::valueOf)
                .to(countTopic, Produced.with(Serdes.String(), Serdes.String()));

        return audit;
    }
}
