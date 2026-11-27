package io.uacrf.outbox;

import io.uacrf.crypto.CanonicalJson;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

/**
 * Leased Transactional Outbox Relay publishing `integration.outbox_event` rows into
 * Kafka/Redpanda topics using CloudEvents envelope formatting.
 */
public final class OutboxKafkaRelay {

    private OutboxKafkaRelay() {
    }

    public static int relayPendingBatch(JdbcTemplate jdbc, String bootstrapServers, String topic, int limit) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT outbox_id, tenant_id, environment_id, event_type, aggregate_type, aggregate_id,
                       correlation_id, payload_json::text AS payload_json, created_at
                FROM integration.outbox_event
                WHERE status = 'PENDING'
                ORDER BY created_at ASC
                LIMIT ?
                """, limit);

        if (rows.isEmpty()) {
            return 0;
        }

        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);

        int published = 0;
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            for (Map<String, Object> row : rows) {
                UUID outboxId = (UUID) row.get("outbox_id");
                String tenantId = String.valueOf(row.get("tenant_id"));
                String eventType = String.valueOf(row.get("event_type"));

                String cloudEvent = CanonicalJson.toJson(Map.of(
                        "specversion", "1.0",
                        "id", outboxId.toString(),
                        "type", eventType,
                        "source", "uacrf://outbox/" + row.get("aggregate_type"),
                        "tenantId", tenantId,
                        "environmentId", String.valueOf(row.get("environment_id")),
                        "correlationId", String.valueOf(row.get("correlation_id")),
                        "data", row.get("payload_json")
                ));

                producer.send(new ProducerRecord<>(topic, tenantId, cloudEvent)).get();
                jdbc.update("UPDATE integration.outbox_event SET status = 'PUBLISHED', published_at = ? WHERE outbox_id = ?",
                        Timestamp.from(Instant.now()), outboxId);
                published++;
            }
            producer.flush();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to relay outbox events to Kafka/Redpanda", ex);
        }
        return published;
    }
}
