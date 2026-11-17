ALTER TABLE uacrf_assessment.incident
 ADD COLUMN source_evaluation_ids jsonb CHECK (
   source_evaluation_ids IS NULL OR (
     jsonb_typeof(source_evaluation_ids) = 'array'
     AND jsonb_array_length(source_evaluation_ids) BETWEEN 1 AND 100
   )
 );
