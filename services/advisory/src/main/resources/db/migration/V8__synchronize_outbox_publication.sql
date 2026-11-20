CREATE FUNCTION uacrf_advisory.require_outbox_delivery_before_event_publish()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.state = 'PUBLISHED'
    AND NOT EXISTS (
      SELECT 1
      FROM uacrf_advisory.outbox_delivery delivery
      WHERE delivery.event_id = NEW.event_id AND delivery.state = 'PUBLISHED'
    ) THEN
    RAISE EXCEPTION 'outbox event cannot publish before delivery' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER require_outbox_delivery_before_event_publish
  BEFORE UPDATE OF state ON uacrf_advisory.outbox_event FOR EACH ROW
  EXECUTE FUNCTION uacrf_advisory.require_outbox_delivery_before_event_publish();

CREATE FUNCTION uacrf_advisory.publish_outbox_event_after_delivery()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  UPDATE uacrf_advisory.outbox_event
  SET state = 'PUBLISHED', published_at = clock_timestamp()
  WHERE event_id = NEW.event_id AND state = 'PENDING';
  IF NOT FOUND THEN
    RAISE EXCEPTION 'claimed outbox event is not pending' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER publish_outbox_event_after_delivery
  AFTER UPDATE OF state ON uacrf_advisory.outbox_delivery
  FOR EACH ROW WHEN (OLD.state = 'CLAIMED' AND NEW.state = 'PUBLISHED')
  EXECUTE FUNCTION uacrf_advisory.publish_outbox_event_after_delivery();
