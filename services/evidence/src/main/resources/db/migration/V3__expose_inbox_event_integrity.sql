CREATE INDEX quarantined_inbox_event_conflicting_id_idx
    ON uacrf_evidence.quarantined_inbox_event (tenant_id, environment_id, event_id)
    WHERE reason = 'EVENT_CONFLICT';

CREATE INDEX quarantined_inbox_event_conflicting_sequence_idx
    ON uacrf_evidence.quarantined_inbox_event
        (tenant_id, environment_id, source_service, aggregate_id, aggregate_sequence)
    WHERE reason IN ('EVENT_CONFLICT', 'SEQUENCE_REGRESSION');

CREATE VIEW uacrf_evidence.inbox_event_integrity AS
SELECT
    event.tenant_id,
    event.environment_id,
    event.event_id,
    event.source_service,
    event.event_type,
    event.aggregate_id,
    event.aggregate_sequence,
    CASE
        WHEN EXISTS (
            SELECT 1
            FROM uacrf_evidence.quarantined_inbox_event AS quarantine
            WHERE quarantine.tenant_id = event.tenant_id
              AND quarantine.environment_id = event.environment_id
              AND quarantine.event_id = event.event_id
              AND quarantine.reason = 'EVENT_CONFLICT'
        ) OR EXISTS (
            SELECT 1
            FROM uacrf_evidence.quarantined_inbox_event AS quarantine
            WHERE quarantine.tenant_id = event.tenant_id
              AND quarantine.environment_id = event.environment_id
              AND quarantine.source_service = event.source_service
              AND quarantine.aggregate_id = event.aggregate_id
              AND quarantine.aggregate_sequence = event.aggregate_sequence
              AND quarantine.reason IN ('EVENT_CONFLICT', 'SEQUENCE_REGRESSION')
        ) THEN 'CONFLICT'
        ELSE 'NO_KNOWN_CONFLICT'
    END AS integrity_status
FROM uacrf_evidence.inbox_event AS event;
