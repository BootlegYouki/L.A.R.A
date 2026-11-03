-- Desktop Issue #40: Hub connection tracking
-- Records the last connected Hub address for auto-reconnect on app restart

CREATE TABLE IF NOT EXISTS hub_connections (
    hub_address TEXT PRIMARY KEY NOT NULL,     -- IP:port or remote tunnel hostname
    connected_at TEXT NOT NULL,                -- ISO 8601 timestamp when first connected
    last_seen_at TEXT NOT NULL                 -- ISO 8601 timestamp when last verified reachable
);

CREATE INDEX IF NOT EXISTS idx_hub_connections_last_seen ON hub_connections(last_seen_at DESC);
