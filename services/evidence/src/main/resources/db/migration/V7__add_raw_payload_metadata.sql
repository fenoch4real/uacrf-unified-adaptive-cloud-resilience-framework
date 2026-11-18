ALTER TABLE uacrf_evidence.evidence_event
    ADD COLUMN payload_byte_length bigint;

UPDATE uacrf_evidence.evidence_event
SET payload_byte_length = octet_length(convert_to(payload, 'UTF8'));

ALTER TABLE uacrf_evidence.evidence_event
    ALTER COLUMN payload_byte_length SET NOT NULL,
    ADD CONSTRAINT evidence_payload_byte_length_matches_payload
        CHECK (payload_byte_length = octet_length(convert_to(payload, 'UTF8')));

ALTER TABLE uacrf_evidence.quarantined_evidence
    ADD COLUMN payload_byte_length bigint,
    ADD COLUMN actual_payload_digest char(64);

UPDATE uacrf_evidence.quarantined_evidence
SET payload_byte_length = octet_length(convert_to(payload, 'UTF8')),
    actual_payload_digest =
        CASE
            WHEN reason <> 'DIGEST_MISMATCH'
                 AND claimed_digest ~ '^[0-9a-f]{64}$'
            THEN claimed_digest
            ELSE NULL
        END;

ALTER TABLE uacrf_evidence.quarantined_evidence
    ALTER COLUMN payload_byte_length SET NOT NULL,
    ADD CONSTRAINT quarantined_evidence_payload_byte_length_matches_payload
        CHECK (payload_byte_length = octet_length(convert_to(payload, 'UTF8'))),
    ADD CONSTRAINT quarantined_evidence_actual_digest_format
        CHECK (actual_payload_digest IS NULL OR actual_payload_digest ~ '^[0-9a-f]{64}$');
