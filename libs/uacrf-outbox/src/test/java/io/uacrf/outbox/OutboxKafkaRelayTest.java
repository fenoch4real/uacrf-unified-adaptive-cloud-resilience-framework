package io.uacrf.outbox;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class OutboxKafkaRelayTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Container
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    @Test
    void relaysPendingOutboxEventsIntoRealKafkaAndMarksPublished() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(postgres.getJdbcUrl());
        ds.setUser(postgres.getUsername());
        ds.setPassword(postgres.getPassword());
        JdbcTemplate jdbc = new JdbcTemplate(ds);

        jdbc.execute("CREATE SCHEMA IF NOT EXISTS integration");
        jdbc.execute("""
                CREATE TABLE integration.outbox_event (
                    outbox_id UUID PRIMARY KEY,
                    tenant_id UUID NOT NULL,
                    environment_id UUID NOT NULL,
                    event_type VARCHAR(120) NOT NULL,
                    aggregate_type VARCHAR(80) NOT NULL,
                    aggregate_id VARCHAR(120) NOT NULL,
                    correlation_id UUID NOT NULL,
                    payload_json JSONB NOT NULL,
                    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
                    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                    published_at TIMESTAMPTZ
                )
                """);

        UUID outboxId = UUID.randomUUID();
        UUID tenantId = UUID.fromString("11111111-1111-7111-8111-111111111111");
        UUID envId = UUID.fromString("22222222-2222-7222-8222-222222222222");
        jdbc.update("""
                INSERT INTO integration.outbox_event (
                    outbox_id, tenant_id, environment_id, event_type, aggregate_type, aggregate_id, correlation_id, payload_json
                ) VALUES (?, ?, ?, 'evidence.item.qualified', 'EvidencePackage', 'pkg-1', ?, '{"status":"QUALIFIED"}'::jsonb)
                """, outboxId, tenantId, envId, UUID.randomUUID());

        int count = OutboxKafkaRelay.relayPendingBatch(jdbc, kafka.getBootstrapServers(), "uacrf.events.v1", 10);
        assertEquals(1, count);

        String dbStatus = jdbc.queryForObject(
                "SELECT status FROM integration.outbox_event WHERE outbox_id = ?", String.class, outboxId);
        assertEquals("PUBLISHED", dbStatus);

        Properties cProps = new Properties();
        cProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        cProps.put(ConsumerConfig.GROUP_ID_CONFIG, "uacrf-outbox-test");
        cProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        cProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        cProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(cProps)) {
            consumer.subscribe(List.of("uacrf.events.v1"));
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(10));
            assertEquals(1, records.count());
            String val = records.iterator().next().value();
            assertTrue(val.contains("evidence.item.qualified"));
            assertTrue(val.contains(tenantId.toString()));
        }
    }
}
