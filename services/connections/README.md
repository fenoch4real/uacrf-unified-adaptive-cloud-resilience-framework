# Connections ingestion

Observation IDs are unique per tenant, environment and connection. Intake serializes requests for
the same ID across source bindings, and accepts a replay as a duplicate only when all retained
fields, including the original nanosecond-precision observation time, match.

Observations accepted before migration V3 have only PostgreSQL's microsecond-precision timestamp.
Their original precision cannot be recovered: any subsequent replay of one of those IDs is
quarantined as `REPLAY_CONFLICT`, even if it might have been identical. Their read time remains
the retained, rounded timestamp. New observations retain and return their exact original instant.
Quarantine is not qualification; S04 remains the evidence authority.

Deploy V3 before the updated service and avoid mixed old/new ingestion instances: older instances
neither retain exact times nor acquire the new ID lock. Rollback to older intake also loses these
replay and concurrency guarantees.
