CREATE TABLE operational_marker (
    marker_key varchar(80) PRIMARY KEY,
    occurred_at timestamptz NOT NULL,
    details jsonb NOT NULL DEFAULT '{}'::jsonb
);

COMMENT ON TABLE operational_marker IS 'External operational recovery points surfaced as application metrics';
