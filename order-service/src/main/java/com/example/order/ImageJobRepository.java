package com.example.order;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * Records each image job in Postgres. Debezium captures inserts/updates on image_jobs
 * and publishes them to the orders-db.public.image_jobs topic.
 */
@Repository
public class ImageJobRepository {
    private final JdbcTemplate jdbc;

    public ImageJobRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void queued(UUID id, long pngBytes) {
        jdbc.update("INSERT INTO image_jobs (id, status, png_bytes) VALUES (?, 'QUEUED', ?)", id, pngBytes);
    }

    public void completed(UUID id, long jpegBytes) {
        jdbc.update("UPDATE image_jobs SET status = 'COMPLETED', jpeg_bytes = ?, updated_at = now() WHERE id = ?",
                jpegBytes, id);
    }
}
