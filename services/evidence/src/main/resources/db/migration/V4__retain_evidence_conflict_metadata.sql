ALTER TABLE uacrf_evidence.quarantined_evidence
    ADD COLUMN source_class text,
    ADD COLUMN observed_at timestamptz,
    ADD COLUMN kind text,
    ADD COLUMN submitted_by text;

CREATE INDEX quarantined_evidence_conflicting_id_idx
    ON uacrf_evidence.quarantined_evidence (tenant_id, environment_id, evidence_id)
    WHERE reason = 'REPLAY_CONFLICT';

CREATE INDEX quarantined_evidence_conflicting_sequence_idx
    ON uacrf_evidence.quarantined_evidence (tenant_id, environment_id, source_ref, sequence)
    WHERE reason IN ('REPLAY_CONFLICT', 'STALE_SEQUENCE');
