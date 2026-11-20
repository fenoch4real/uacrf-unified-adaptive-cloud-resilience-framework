CREATE TABLE uacrf_advisory.outbox_delivery (
  event_id text PRIMARY KEY
    REFERENCES uacrf_advisory.outbox_event (event_id),
  state text NOT NULL CHECK (state IN ('PENDING', 'CLAIMED', 'PUBLISHED')),
  attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
  claim_token uuid,
  lease_expires_at timestamptz,
  published_at timestamptz,
  CHECK (
    (state = 'PENDING' AND claim_token IS NULL AND lease_expires_at IS NULL AND published_at IS NULL)
    OR (state = 'CLAIMED' AND attempt_count >= 1 AND claim_token IS NOT NULL
      AND lease_expires_at IS NOT NULL AND published_at IS NULL)
    OR (state = 'PUBLISHED' AND claim_token IS NULL AND lease_expires_at IS NULL
      AND published_at IS NOT NULL)
  )
);

INSERT INTO uacrf_advisory.outbox_delivery (event_id, state, published_at)
SELECT event_id, CASE state WHEN 'PUBLISHED' THEN 'PUBLISHED' ELSE 'PENDING' END, published_at
FROM uacrf_advisory.outbox_event;

CREATE INDEX outbox_delivery_claim_order
  ON uacrf_advisory.outbox_delivery (state, lease_expires_at, event_id)
  WHERE state IN ('PENDING', 'CLAIMED');

CREATE FUNCTION uacrf_advisory.protect_outbox_delivery() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'DELETE' OR NEW.event_id IS DISTINCT FROM OLD.event_id THEN
    RAISE EXCEPTION 'outbox delivery history is immutable' USING ERRCODE = '23514';
  END IF;
  IF OLD.state = 'PENDING' AND NEW.state = 'CLAIMED'
    AND NEW.attempt_count = OLD.attempt_count + 1
    AND NEW.claim_token IS NOT NULL AND NEW.lease_expires_at > clock_timestamp()
    AND NEW.published_at IS NULL THEN
    RETURN NEW;
  END IF;
  IF OLD.state = 'CLAIMED' AND NEW.state = 'CLAIMED'
    AND OLD.lease_expires_at <= clock_timestamp()
    AND NEW.attempt_count = OLD.attempt_count + 1
    AND NEW.claim_token IS NOT NULL AND NEW.claim_token <> OLD.claim_token
    AND NEW.lease_expires_at > clock_timestamp() AND NEW.published_at IS NULL THEN
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

CREATE TRIGGER protect_outbox_delivery
  BEFORE UPDATE OR DELETE ON uacrf_advisory.outbox_delivery FOR EACH ROW
  EXECUTE FUNCTION uacrf_advisory.protect_outbox_delivery();
