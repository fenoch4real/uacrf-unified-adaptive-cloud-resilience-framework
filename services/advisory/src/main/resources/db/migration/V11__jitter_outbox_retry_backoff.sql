CREATE OR REPLACE FUNCTION uacrf_advisory.protect_outbox_delivery() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  retry_base_seconds integer;
  retry_jitter_max_millis integer;
  retry_jitter_millis integer;
  retry_token_bytes bytea;
  retry_token_bits bigint;
BEGIN
  IF TG_OP = 'DELETE' OR NEW.event_id IS DISTINCT FROM OLD.event_id THEN
    RAISE EXCEPTION 'outbox delivery history is immutable' USING ERRCODE = '23514';
  END IF;
  IF OLD.state = 'CLAIMED' THEN
    retry_base_seconds := CASE WHEN OLD.attempt_count >= 11 THEN 900
      ELSE power(2::numeric, OLD.attempt_count - 1)::integer END;
    retry_jitter_max_millis := LEAST(retry_base_seconds * 500,
      (900 - retry_base_seconds) * 1000);
    retry_token_bytes := uuid_send(OLD.claim_token);
    retry_token_bits := get_byte(retry_token_bytes, 12)::bigint * 16777216
      + get_byte(retry_token_bytes, 13)::bigint * 65536
      + get_byte(retry_token_bytes, 14)::bigint * 256
      + get_byte(retry_token_bytes, 15)::bigint;
    retry_jitter_millis := mod(retry_token_bits, retry_jitter_max_millis::bigint + 1)::integer;
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
      + make_interval(secs => retry_base_seconds::double precision
        + retry_jitter_millis::double precision / 1000.0)
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
