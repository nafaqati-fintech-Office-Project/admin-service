-- Broadcasts are now delivered through notification-service (in-app + push). SENDING while it runs; SENT when every account was
-- reached, PARTIAL when some deliveries failed, FAILED when none got through. failed = deliveries that did not get through.
ALTER TABLE broadcasts DROP CONSTRAINT broadcasts_status_check;
ALTER TABLE broadcasts ADD CONSTRAINT broadcasts_status_check
    CHECK (status IN ('QUEUED', 'SENDING', 'SENT', 'PARTIAL', 'FAILED'));
ALTER TABLE broadcasts ADD COLUMN failed INTEGER;
