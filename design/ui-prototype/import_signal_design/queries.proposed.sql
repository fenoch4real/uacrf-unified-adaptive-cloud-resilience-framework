-- Read sketches only. Use authenticated scope, current authorization and bounded pagination.
-- Source/local digest comparison and controlled transaction admission remain worker responsibilities.
SELECT * FROM uacrf_ingestion_design.objective_import
WHERE tenant_id=:tenant_id AND environment_id=:environment_id AND binding_id=:binding_id
ORDER BY created_at DESC,id LIMIT :page_size;
SELECT * FROM uacrf_ingestion_design.signal_receipt
WHERE tenant_id=:tenant_id AND environment_id=:environment_id AND connection_id=:connection_id AND source_event_id=:source_event_id;
SELECT * FROM uacrf_ingestion_design.evaluation_schedule
WHERE tenant_id=:tenant_id AND environment_id=:environment_id AND state='PENDING' AND due_at<=:now
ORDER BY urgent DESC,due_at LIMIT :batch_size;
-- Lease acquisition, retry, duplicate conflict detection and audit/outbox writes require
-- controlled atomic procedures; this SELECT is not a concurrency-safe dispatch operation.
