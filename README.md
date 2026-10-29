
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
