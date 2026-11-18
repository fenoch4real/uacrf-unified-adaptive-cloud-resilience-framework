ALTER TABLE uacrf_connections.connection
    ADD COLUMN aws_external_id text;

UPDATE uacrf_connections.connection
SET aws_external_id = 'uacrf-' || gen_random_uuid()::text
WHERE provider = 'AWS';

ALTER TABLE uacrf_connections.connection
    ADD CONSTRAINT connection_aws_external_id_check
        CHECK ((provider = 'AWS') = (aws_external_id IS NOT NULL));

CREATE UNIQUE INDEX connection_aws_external_id_unique_idx
    ON uacrf_connections.connection (aws_external_id)
    WHERE aws_external_id IS NOT NULL;
