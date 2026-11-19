ALTER TABLE uacrf_evidence.inbox_event
    ADD COLUMN aggregate_type text;

UPDATE uacrf_evidence.inbox_event
SET aggregate_type = CASE event_type
    WHEN 'connections.connection.registered' THEN 'CONNECTION'
    WHEN 'connections.source-binding.created' THEN 'SOURCE_BINDING'
    WHEN 'connections.observation.accepted' THEN 'OBSERVATION'
    ELSE event_type
END;

ALTER TABLE uacrf_evidence.inbox_event
    ALTER COLUMN aggregate_type SET NOT NULL;

DO $$
DECLARE
    old_constraint text;
BEGIN
    SELECT conname
    INTO old_constraint
    FROM pg_constraint
    WHERE conrelid = 'uacrf_evidence.inbox_event'::regclass
      AND contype = 'u'
      AND regexp_replace(pg_get_constraintdef(oid), '[[:space:]]', '', 'g')
          = 'UNIQUE(tenant_id,environment_id,source_service,aggregate_id,aggregate_sequence)';

    IF old_constraint IS NULL THEN
        RAISE EXCEPTION 'Expected the legacy inbox aggregate-sequence constraint';
    END IF;

    EXECUTE format(
        'ALTER TABLE uacrf_evidence.inbox_event DROP CONSTRAINT %I',
        old_constraint);
END $$;

ALTER TABLE uacrf_evidence.inbox_event
    ADD CONSTRAINT inbox_event_aggregate_sequence_key
    UNIQUE (tenant_id, environment_id, source_service, aggregate_type, aggregate_id, aggregate_sequence);

ALTER TABLE uacrf_evidence.quarantined_inbox_event
    ADD COLUMN aggregate_type text;

UPDATE uacrf_evidence.quarantined_inbox_event
SET aggregate_type = CASE event_type
    WHEN 'connections.connection.registered' THEN 'CONNECTION'
    WHEN 'connections.source-binding.created' THEN 'SOURCE_BINDING'
    WHEN 'connections.observation.accepted' THEN 'OBSERVATION'
    ELSE event_type
END
WHERE event_type IS NOT NULL;

UPDATE uacrf_evidence.quarantined_inbox_event AS quarantine
SET aggregate_type = event.aggregate_type
FROM uacrf_evidence.inbox_event AS event
WHERE quarantine.aggregate_type IS NULL
  AND quarantine.tenant_id = event.tenant_id
  AND quarantine.environment_id = event.environment_id
  AND quarantine.source_service = event.source_service
  AND quarantine.aggregate_id = event.aggregate_id
  AND quarantine.aggregate_sequence = event.aggregate_sequence;

DROP INDEX uacrf_evidence.quarantined_inbox_event_conflicting_sequence_idx;

CREATE INDEX quarantined_inbox_event_conflicting_sequence_idx
    ON uacrf_evidence.quarantined_inbox_event
        (tenant_id, environment_id, source_service, aggregate_type, aggregate_id, aggregate_sequence)
    WHERE reason IN ('EVENT_CONFLICT', 'SEQUENCE_REGRESSION');

DROP VIEW uacrf_evidence.inbox_event_integrity;

CREATE VIEW uacrf_evidence.inbox_event_integrity AS
SELECT
    event.tenant_id,
    event.environment_id,
    event.event_id,
    event.source_service,
    event.event_type,
    event.aggregate_type,
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
              AND (quarantine.aggregate_type = event.aggregate_type OR quarantine.aggregate_type IS NULL)
              AND quarantine.aggregate_id = event.aggregate_id
              AND quarantine.aggregate_sequence = event.aggregate_sequence
              AND quarantine.reason IN ('EVENT_CONFLICT', 'SEQUENCE_REGRESSION')
        ) THEN 'CONFLICT'
        ELSE 'NO_KNOWN_CONFLICT'
    END AS integrity_status
FROM uacrf_evidence.inbox_event AS event;
