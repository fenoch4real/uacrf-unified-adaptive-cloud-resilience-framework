ALTER TABLE uacrf_connections.observation
    ADD COLUMN observed_at_epoch_second bigint,
    ADD COLUMN observed_at_nano integer,
    ADD CONSTRAINT observation_exact_time_valid CHECK (
        (observed_at_epoch_second IS NULL AND observed_at_nano IS NULL)
        OR (observed_at_epoch_second IS NOT NULL AND observed_at_nano IS NOT NULL
            AND observed_at_nano BETWEEN 0 AND 999999999)
    );
