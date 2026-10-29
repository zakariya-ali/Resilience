-- One row per uploaded image. Debezium streams every change to this table into Kafka.
CREATE TABLE IF NOT EXISTS image_jobs (
    id          UUID PRIMARY KEY,
    status      VARCHAR(20) NOT NULL,
    png_bytes   BIGINT      NOT NULL,
    jpeg_bytes  BIGINT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
