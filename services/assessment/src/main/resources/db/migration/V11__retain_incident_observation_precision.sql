ALTER TABLE uacrf_assessment.incident
 ADD COLUMN observed_from_epoch_second bigint,
 ADD COLUMN observed_from_nano integer,
 ADD CONSTRAINT incident_observation_precision CHECK (
   (observed_from_epoch_second IS NULL AND observed_from_nano IS NULL)
   OR (observed_from_epoch_second IS NOT NULL AND observed_from_nano IS NOT NULL
     AND observed_from_nano BETWEEN 0 AND 999999999)
 );
