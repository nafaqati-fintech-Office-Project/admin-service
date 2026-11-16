-- Append-only record of every admin action. Holds ids and action names only: never personal or financial data.
CREATE TABLE audit_log (
    id      BIGSERIAL    PRIMARY KEY,
    at      TIMESTAMPTZ  NOT NULL,
    actor   VARCHAR(64)  NOT NULL,   -- the admin's user id
    action  VARCHAR(60)  NOT NULL,
    target  VARCHAR(80),             -- an account ref, request id or flag key
    outcome VARCHAR(10)  NOT NULL CHECK (outcome IN ('SUCCESS', 'FAILED', 'DENIED')),
    detail  VARCHAR(200)
);
CREATE INDEX audit_log_at ON audit_log (at DESC);

CREATE FUNCTION audit_log_is_append_only() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'audit_log is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER audit_log_no_change BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION audit_log_is_append_only();

CREATE TABLE feature_flags (
    key        VARCHAR(60)  PRIMARY KEY,
    summary    VARCHAR(200) NOT NULL,
    enabled    BOOLEAN      NOT NULL DEFAULT FALSE,
    updated_at TIMESTAMPTZ  NOT NULL,
    updated_by VARCHAR(64)
);

INSERT INTO feature_flags (key, summary, enabled, updated_at) VALUES
    ('family-premium-enforcement', 'Enforce Premium for Family features', FALSE, now()),
    ('sms-import', 'SMS transaction import', TRUE, now()),
    ('paymob-checkout', 'Paymob checkout in the apps', TRUE, now());

-- Announcements go to a whole segment, never to one person and never on financial behaviour.
CREATE TABLE broadcasts (
    id         UUID         PRIMARY KEY,
    title      VARCHAR(80)  NOT NULL,
    body       VARCHAR(300) NOT NULL,
    audience   VARCHAR(10)  NOT NULL CHECK (audience IN ('ALL', 'PREMIUM', 'FREE')),
    channel    VARCHAR(10)  NOT NULL CHECK (channel IN ('PUSH', 'IN_APP', 'EMAIL')),
    status     VARCHAR(10)  NOT NULL CHECK (status IN ('QUEUED', 'SENT', 'FAILED')),
    created_by VARCHAR(64)  NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL,
    sent_at    TIMESTAMPTZ,
    reach      INTEGER
);
CREATE INDEX broadcasts_created ON broadcasts (created_at DESC);
