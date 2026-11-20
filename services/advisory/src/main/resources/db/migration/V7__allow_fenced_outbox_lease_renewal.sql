CREATE OR REPLACE FUNCTION uacrf_advisory.protect_outbox_delivery() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'DELETE' OR NEW.event_id IS DISTINCT FROM OLD.event_id THEN
    RAISE EXCEPTION 'outbox delivery history is immutable' USING ERRCODE = '23514';
  END IF;
  IF OLD.state = 'PENDING' AND NEW.state = 'CLAIMED'
    AND NEW.attempt_count = OLD.attempt_count + 1
    AND NEW.claim_token IS NOT NULL AND NEW.lease_expires_at > clock_timestamp()
    AND NEW.lease_expires_at <= clock_timestamp() + INTERVAL '5 minutes'
    AND NEW.published_at IS NULL THEN
    RETURN NEW;
  END IF;
  IF OLD.state = 'CLAIMED' AND NEW.state = 'CLAIMED'
    AND OLD.lease_expires_at > clock_timestamp()
    AND NEW.attempt_count = OLD.attempt_count
    AND NEW.claim_token = OLD.claim_token
    AND NEW.lease_expires_at > OLD.lease_expires_at
    AND NEW.lease_expires_at <= clock_timestamp() + INTERVAL '5 minutes'
    AND NEW.published_at IS NULL THEN
    RETURN NEW;
  END IF;
  IF OLD.state = 'CLAIMED' AND NEW.state = 'CLAIMED'
    AND OLD.lease_expires_at <= clock_timestamp()
    AND NEW.attempt_count = OLD.attempt_count + 1
    AND NEW.claim_token IS NOT NULL AND NEW.claim_token <> OLD.claim_token
    AND NEW.lease_expires_at > clock_timestamp()
    AND NEW.lease_expires_at <= clock_timestamp() + INTERVAL '5 minutes'
    AND NEW.published_at IS NULL THEN
    RETURN NEW;
  END IF;
  IF OLD.state = 'CLAIMED' AND NEW.state = 'PUBLISHED'
    AND OLD.lease_expires_at > clock_timestamp()
    AND NEW.attempt_count = OLD.attempt_count
    AND NEW.claim_token IS NULL AND NEW.lease_expires_at IS NULL
    AND NEW.published_at IS NOT NULL THEN
    RETURN NEW;
  END IF;
  RAISE EXCEPTION 'invalid outbox delivery transition' USING ERRCODE = '23514';
END $$;
