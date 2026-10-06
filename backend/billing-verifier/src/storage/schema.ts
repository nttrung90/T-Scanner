/**
 * SQL Schema and table definitions for SQLite storage driver.
 * Enforces single-owner uniqueness on purchase_token, indexes by owner, and tracks monotonic sequence versions.
 */

export const SCHEMA_SQL = `
CREATE TABLE IF NOT EXISTS token_records (
  purchase_token TEXT PRIMARY KEY,
  owner_app_user_id TEXT NOT NULL,
  obfuscated_account_id TEXT,
  product_id TEXT NOT NULL,
  first_bound_at INTEGER NOT NULL,
  latest_snapshot_version INTEGER NOT NULL,
  entitlement_json TEXT NOT NULL,
  last_event_time_millis INTEGER DEFAULT 0,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_token_records_owner 
  ON token_records(owner_app_user_id);

CREATE TABLE IF NOT EXISTS user_version_sequences (
  owner_app_user_id TEXT PRIMARY KEY,
  latest_version INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS ack_retry_queue (
  purchase_token TEXT PRIMARY KEY,
  product_id TEXT NOT NULL,
  product_type TEXT NOT NULL,
  owner_app_user_id TEXT,
  attempt_count INTEGER NOT NULL DEFAULT 0,
  last_attempt_at INTEGER,
  next_attempt_at INTEGER NOT NULL,
  status TEXT NOT NULL DEFAULT 'PENDING',
  error_message TEXT,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_ack_retry_status_next 
  ON ack_retry_queue(status, next_attempt_at);
`;
