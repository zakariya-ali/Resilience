
## Kafka UI, Kafka Streams and Kafka Connect

`docker compose up -d` starts:

| Service | URL | Purpose |
|---|---|---|
| `kafka` | `localhost:9092` | Broker (KRaft) |
| `kafka-ui` | http://localhost:8085 | Browse topics, consumer groups and connectors |
| `kafka-connect` | http://localhost:8083 | Kafka Connect REST API (distributed mode) |
| `kafka-connect-init` | — | Registers every connector in `connect/connectors/` then exits |

**Kafka Streams** (`inventory-service`, `ImageStatsTopology`): joins each `image-uploaded` PNG with its
`image-converted` JPEG by ID and writes `id,pngBytes,jpegBytes,ratio` to `image-audit`, plus a running
count of conversions to `image-conversion-count`.

**Kafka Connect**: the `image-audit-file-sink` connector (FileStreamSink) copies `image-audit` into
`connect/data/image-audit.log` on the host.

## Debezium (change data capture)

`order-service` stores one row per image upload in Postgres (`image_jobs`: id, status, png/jpeg sizes,
timestamps). The row is inserted as `QUEUED` on upload and updated to `COMPLETED` when the JPEG arrives.

The `image-jobs-postgres-source` Debezium connector (in `connect/connectors/`) reads Postgres' change log
and publishes every insert/update to the `orders-db.public.image_jobs` topic as JSON
(`{"before": ..., "after": ..., "op": "c" | "u", ...}`). Kafka Connect now runs on the Debezium image,
which also still runs the FileStream sink above.

Postgres runs on `localhost:5432` (db/user/password: `orders`). order-service needs it running.
