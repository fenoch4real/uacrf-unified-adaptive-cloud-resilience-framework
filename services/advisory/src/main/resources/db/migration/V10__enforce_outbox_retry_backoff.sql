CREATE OR REPLACE FUNCTION uacrf_advisory.protect_outbox_delivery() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  retry_delay_seconds integer;
BEGIN
  IF TG_OP = 'DELETE' OR NEW.event_id IS DISTINCT FROM OLD.event_id THEN
    RAISE EXCEPTION 'outbox delivery history is immutable' USING ERRCODE = '23514';
  END IF;
  IF OLD.state = 'CLAIMED' THEN
    retry_delay_seconds := CASE WHEN OLD.attempt_count >= 11 THEN 900
      ELSE power(2::numeric, OLD.attempt_count - 1)::integer END;
  END IF;
  IF OLD.state = 'PENDING' AND NEW.state = 'CLAIMED'
    AND NEW.attempt_count = OLD.attempt_count + 1
    AND NEW.claim_token IS NOT NULL AND NEW.lease_expires_at > clock_timestamp()
    AND NEW.lease_expires_at <= clock_timestamp() + INTERVAL '5 minutes'
    AND NEW.available_at IS NOT DISTINCT FROM OLD.available_at
    AND NEW.published_at IS NULL THEN
    RETURN NEW;
  END IF;
  IF OLD.state = 'CLAIMED' AND NEW.state = 'CLAIMED'
    AND OLD.lease_expires_at > clock_timestamp()
    AND NEW.attempt_count = OLD.attempt_count
    AND NEW.claim_token = OLD.claim_token
    AND NEW.lease_expires_at > OLD.lease_expires_at
    AND NEW.lease_expires_at <= clock_timestamp() + INTERVAL '5 minutes'
    AND NEW.available_at IS NOT DISTINCT FROM OLD.available_at
    AND NEW.published_at IS NULL THEN
    RETURN NEW;
  END IF;
  IF OLD.state = 'CLAIMED' AND NEW.state = 'CLAIMED'
    AND OLD.lease_expires_at <= clock_timestamp()
    AND NEW.attempt_count = OLD.attempt_count + 1
    AND NEW.claim_token IS NOT NULL AND NEW.claim_token <> OLD.claim_token
    AND NEW.lease_expires_at > clock_timestamp()
    AND NEW.lease_expires_at <= clock_timestamp() + INTERVAL '5 minutes'
    AND NEW.available_at IS NOT DISTINCT FROM OLD.available_at
    AND NEW.published_at IS NULL THEN
    RETURN NEW;
  END IF;
  IF OLD.state = 'CLAIMED' AND NEW.state = 'PENDING'
    AND OLD.lease_expires_at > clock_timestamp()
    AND NEW.attempt_count = OLD.attempt_count
    AND NEW.claim_token IS NULL AND NEW.lease_expires_at IS NULL
    AND NEW.available_at > clock_timestamp()
    AND NEW.available_at >= statement_timestamp()
      + make_interval(secs => retry_delay_seconds::double precision)
    AND NEW.available_at <= clock_timestamp() + INTERVAL '15 minutes'
    AND NEW.published_at IS NULL THEN
    RETURN NEW;
  END IF;
  IF OLD.state = 'CLAIMED' AND NEW.state = 'PUBLISHED'
    AND OLD.lease_expires_at > clock_timestamp()
    AND NEW.attempt_count = OLD.attempt_count
    AND NEW.claim_token IS NULL AND NEW.lease_expires_at IS NULL
    AND NEW.available_at IS NOT DISTINCT FROM OLD.available_at
    AND NEW.published_at IS NOT NULL THEN
    RETURN NEW;
  END IF;
  RAISE EXCEPTION 'invalid outbox delivery transition' USING ERRCODE = '23514';
END $$;
