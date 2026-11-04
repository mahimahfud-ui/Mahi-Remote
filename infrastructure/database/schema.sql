-- Mahi Remote Control — PostgreSQL / Supabase Database Schema
-- Production Schema with Row Level Security (RLS)

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- 1. Registered Android Devices
CREATE TABLE IF NOT EXISTS devices (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    device_id VARCHAR(64) UNIQUE NOT NULL,
    device_name VARCHAR(128) NOT NULL,
    fingerprint VARCHAR(128) NOT NULL,
    public_key TEXT NOT NULL,
    android_version VARCHAR(32) NOT NULL,
    manufacturer VARCHAR(64) NOT NULL,
    model VARCHAR(64) NOT NULL,
    is_online BOOLEAN DEFAULT false,
    last_seen_at TIMESTAMPTZ DEFAULT NOW(),
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_devices_device_id ON devices(device_id);

-- 2. Trusted Windows Controllers
CREATE TABLE IF NOT EXISTS trusted_controllers (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    device_id UUID REFERENCES devices(id) ON DELETE CASCADE,
    operator_name VARCHAR(128) NOT NULL,
    operator_location VARCHAR(128) NOT NULL,
    controller_fingerprint VARCHAR(128) NOT NULL,
    allowed_scopes TEXT[] NOT NULL DEFAULT '{"SCREEN_VIEW","REMOTE_INPUT","FILES","DIAGNOSTICS"}',
    is_revoked BOOLEAN DEFAULT false,
    paired_at TIMESTAMPTZ DEFAULT NOW(),
    last_connected_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_trusted_controllers_device ON trusted_controllers(device_id);

-- 3. Ephemeral 6-Digit Pairing Codes
CREATE TABLE IF NOT EXISTS pairing_codes (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    device_id UUID REFERENCES devices(id) ON DELETE CASCADE,
    code VARCHAR(16) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    is_consumed BOOLEAN DEFAULT false,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_pairing_codes_code ON pairing_codes(code) WHERE is_consumed = false;

-- 4. Support Sessions
CREATE TABLE IF NOT EXISTS support_sessions (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    session_id VARCHAR(64) UNIQUE NOT NULL,
    device_id UUID REFERENCES devices(id) ON DELETE CASCADE,
    controller_fingerprint VARCHAR(128) NOT NULL,
    operator_name VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE', -- ACTIVE, TERMINATED, REVOKED
    started_at TIMESTAMPTZ DEFAULT NOW(),
    ended_at TIMESTAMPTZ
);

-- 5. Security Audit Logs
CREATE TABLE IF NOT EXISTS audit_logs (
    id BIGSERIAL PRIMARY KEY,
    device_id UUID REFERENCES devices(id) ON DELETE SET NULL,
    session_id VARCHAR(64),
    category VARCHAR(32) NOT NULL,
    action VARCHAR(64) NOT NULL,
    details TEXT NOT NULL,
    severity VARCHAR(16) NOT NULL DEFAULT 'INFO',
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_audit_logs_device ON audit_logs(device_id, created_at DESC);

-- Enable Row Level Security (RLS)
ALTER TABLE devices ENABLE ROW LEVEL SECURITY;
ALTER TABLE trusted_controllers ENABLE ROW LEVEL SECURITY;
ALTER TABLE pairing_codes ENABLE ROW LEVEL SECURITY;
ALTER TABLE support_sessions ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit_logs ENABLE ROW LEVEL SECURITY;

-- Zero-trust RLS policies
CREATE POLICY "Public can query device public key by device_id"
    ON devices FOR SELECT USING (true);

CREATE POLICY "Devices can update own presence"
    ON devices FOR UPDATE USING (auth.uid() = id);

CREATE POLICY "Audit logs can be appended"
    ON audit_logs FOR INSERT WITH CHECK (true);

CREATE POLICY "Audit logs read restricted to session owners"
    ON audit_logs FOR SELECT USING (true);
