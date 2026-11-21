CREATE INDEX objective_decision_event_history_idx
    ON uacrf_objectives.outbox_event
        (tenant_id, environment_id, aggregate_id, aggregate_sequence DESC, event_id DESC)
    WHERE aggregate_type = 'OBJECTIVE' AND event_type = 'objectives.objective.decided';
