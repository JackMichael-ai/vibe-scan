-- VibeScan PostgreSQL Schema
-- Multi-tenant with Row Level Security (RLS)
-- Deploy on: Railway Postgres, Supabase (free tier), Neon, or self-hosted

-- ── Extensions ────────────────────────────────────────────────────────────────
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
DO $$
BEGIN
    CREATE EXTENSION IF NOT EXISTS "timescaledb" CASCADE;
EXCEPTION
    WHEN undefined_file THEN
        RAISE NOTICE 'timescaledb extension is not installed; continuing without it';
END
$$;

-- ── Organisations (tenants) ───────────────────────────────────────────────────
CREATE TABLE organisations (
    id          UUID        PRIMARY KEY DEFAULT uuid_generate_v4(),
    name        TEXT        NOT NULL,
    plan        TEXT        NOT NULL DEFAULT 'starter',   -- starter | professional | enterprise
    country     TEXT        NOT NULL DEFAULT 'KE',
    timezone    TEXT        NOT NULL DEFAULT 'Africa/Nairobi',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    is_active   BOOLEAN     NOT NULL DEFAULT TRUE
);

-- ── Users ─────────────────────────────────────────────────────────────────────
CREATE TABLE users (
    id          UUID        PRIMARY KEY DEFAULT uuid_generate_v4(),
    org_id      UUID        NOT NULL REFERENCES organisations(id) ON DELETE CASCADE,
    email       TEXT        NOT NULL UNIQUE,
    password_hash TEXT      NOT NULL,
    role        TEXT        NOT NULL DEFAULT 'operator',  -- admin | operator | viewer
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_login  TIMESTAMPTZ
);
CREATE INDEX idx_users_org ON users(org_id);

-- ── Nodes (phones) ────────────────────────────────────────────────────────────
CREATE TABLE nodes (
    id              UUID        PRIMARY KEY DEFAULT uuid_generate_v4(),
    org_id          UUID        NOT NULL REFERENCES organisations(id) ON DELETE CASCADE,
    node_key        TEXT        NOT NULL UNIQUE,    -- the Android device UUID
    name            TEXT        NOT NULL,
    android_version INTEGER,
    device_model    TEXT,
    app_version     TEXT,
    kiosk_status    TEXT        DEFAULT 'inactive',
    mount_grade     TEXT        DEFAULT 'unknown',
    last_seen_at    TIMESTAMPTZ,
    battery_level   INTEGER,
    battery_temp    REAL,
    is_online       BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_nodes_org ON nodes(org_id);

-- ── Assets (machines being monitored) ────────────────────────────────────────
CREATE TABLE assets (
    id          UUID        PRIMARY KEY DEFAULT uuid_generate_v4(),
    org_id      UUID        NOT NULL REFERENCES organisations(id) ON DELETE CASCADE,
    node_id     UUID        REFERENCES nodes(id),
    name        TEXT        NOT NULL,
    type        TEXT        NOT NULL,           -- pump | motor | generator | conveyor | fan | other
    location    TEXT,
    shaft_rpm   REAL        NOT NULL DEFAULT 1500,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    is_active   BOOLEAN     NOT NULL DEFAULT TRUE
);
CREATE INDEX idx_assets_org  ON assets(org_id);
CREATE INDEX idx_assets_node ON assets(node_id);

-- ── Readings (time-series core) ───────────────────────────────────────────────
-- If using TimescaleDB: this becomes a hypertable (automatic partitioning by time)
CREATE TABLE readings (
    id          BIGSERIAL,
    org_id      UUID        NOT NULL,
    asset_id    UUID        NOT NULL REFERENCES assets(id) ON DELETE CASCADE,
    node_id     UUID        NOT NULL,
    health      SMALLINT    NOT NULL CHECK (health BETWEEN 0 AND 100),
    fault_code  SMALLINT    NOT NULL DEFAULT 0,
    rms         REAL,
    kurtosis    REAL,
    crest       REAL,
    dominant_hz REAL,
    ai_reliability SMALLINT DEFAULT 100,
    mount_grade TEXT,
    rms_ms2           REAL    DEFAULT 0,
    signal_confidence REAL    DEFAULT 0,
    iso_zone          CHAR(1) DEFAULT 'A',
    bpfo_energy       REAL    DEFAULT 0,
    bpfi_energy       REAL    DEFAULT 0,
    recorded_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (id, recorded_at)
);

-- Convert to TimescaleDB hypertable (comment out if not using TimescaleDB)
-- SELECT create_hypertable('readings', 'recorded_at', chunk_time_interval => INTERVAL '1 day');

CREATE INDEX idx_readings_asset_time ON readings(asset_id, recorded_at DESC);
CREATE INDEX idx_readings_org_time   ON readings(org_id, recorded_at DESC);

-- ── Alerts ────────────────────────────────────────────────────────────────────
CREATE TABLE alerts (
    id          UUID        PRIMARY KEY DEFAULT uuid_generate_v4(),
    org_id      UUID        NOT NULL,
    asset_id    UUID        NOT NULL REFERENCES assets(id) ON DELETE CASCADE,
    node_id     UUID        NOT NULL,
    severity    TEXT        NOT NULL,   -- MONITOR | WARNING | CRITICAL
    fault_label TEXT,
    action_text TEXT,
    resolved    BOOLEAN     NOT NULL DEFAULT FALSE,
    resolved_by UUID        REFERENCES users(id),
    resolved_at TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_alerts_org      ON alerts(org_id, resolved, created_at DESC);
CREATE INDEX idx_alerts_asset    ON alerts(asset_id, resolved);

-- ── Node commands (remote control from dashboard) ─────────────────────────────
CREATE TABLE node_commands (
    id          UUID        PRIMARY KEY DEFAULT uuid_generate_v4(),
    org_id      UUID        NOT NULL,
    node_id     UUID        NOT NULL REFERENCES nodes(id) ON DELETE CASCADE,
    type        TEXT        NOT NULL,   -- reset_baseline | update_rpm | update_endpoint
    payload     JSONB,
    acked       BOOLEAN     NOT NULL DEFAULT FALSE,
    acked_at    TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at  TIMESTAMPTZ NOT NULL DEFAULT NOW() + INTERVAL '7 days'
);
CREATE INDEX idx_commands_node ON node_commands(node_id, acked, expires_at);

-- ── Row Level Security ────────────────────────────────────────────────────────
-- Enforces tenant isolation at the database level.
-- Even if application code has a bug, org A cannot read org B's data.

ALTER TABLE nodes         ENABLE ROW LEVEL SECURITY;
ALTER TABLE assets        ENABLE ROW LEVEL SECURITY;
ALTER TABLE readings      ENABLE ROW LEVEL SECURITY;
ALTER TABLE alerts        ENABLE ROW LEVEL SECURITY;
ALTER TABLE node_commands ENABLE ROW LEVEL SECURITY;

-- Application connects as 'vibescan_app' role which has org_id set per session
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'vibescan_app') THEN
        CREATE ROLE vibescan_app;
    END IF;
END
$$;

CREATE POLICY tenant_isolation_nodes ON nodes
    USING (org_id = current_setting('app.current_org_id')::UUID);

CREATE POLICY tenant_isolation_assets ON assets
    USING (org_id = current_setting('app.current_org_id')::UUID);

CREATE POLICY tenant_isolation_readings ON readings
    USING (org_id = current_setting('app.current_org_id')::UUID);

CREATE POLICY tenant_isolation_alerts ON alerts
    USING (org_id = current_setting('app.current_org_id')::UUID);

CREATE POLICY tenant_isolation_commands ON node_commands
    USING (org_id = current_setting('app.current_org_id')::UUID);

-- Grant to app role
GRANT SELECT, INSERT, UPDATE, DELETE ON nodes, assets, readings, alerts, node_commands TO vibescan_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO vibescan_app;

-- ── Useful views ──────────────────────────────────────────────────────────────

-- Latest reading per asset (for dashboard overview cards)
CREATE OR REPLACE VIEW asset_health AS
SELECT DISTINCT ON (asset_id)
    a.id          AS asset_id,
    a.org_id,
    a.name        AS asset_name,
    a.type,
    a.location,
    r.health,
    r.fault_code,
    r.rms,
    r.kurtosis,
    r.dominant_hz,
    r.recorded_at AS last_reading_at,
    n.is_online,
    n.battery_level,
    n.battery_temp,
    n.mount_grade
FROM assets a
LEFT JOIN readings r ON r.asset_id = a.id
LEFT JOIN nodes n    ON n.id = a.node_id
WHERE a.is_active = TRUE
ORDER BY asset_id, r.recorded_at DESC;

-- Unresolved alert count per org (for dashboard badge)
CREATE OR REPLACE VIEW alert_summary AS
SELECT org_id,
       COUNT(*)                                  AS total_unresolved,
       COUNT(*) FILTER (WHERE severity = 'CRITICAL') AS critical_count,
       COUNT(*) FILTER (WHERE severity = 'WARNING')  AS warning_count,
       MAX(created_at)                           AS latest_alert_at
FROM alerts
WHERE resolved = FALSE
GROUP BY org_id;
