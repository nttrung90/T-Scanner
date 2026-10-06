import { DatabaseSync } from 'node:sqlite';
import type { BillingEntitlement } from '../types.ts';
import { SCHEMA_SQL } from './schema.ts';
import type { AckRetryItem, BindOptions, BindResult, StorageDriver, TokenOwnerRecord } from './types.ts';

export class SqliteStorageDriver implements StorageDriver {
  private db: DatabaseSync;
  private readonly dbPath: string;

  constructor(dbPath: string = ':memory:') {
    this.dbPath = dbPath;
    this.db = new DatabaseSync(dbPath);
    this.initSchema();
  }

  private initSchema(): void {
    this.db.exec(SCHEMA_SQL);
    try {
      this.db.exec('ALTER TABLE token_records ADD COLUMN last_event_time_millis INTEGER DEFAULT 0');
    } catch {
      // Column already exists
    }
  }

  async bindOrUpdate(
    ownerAppUserId: string,
    purchaseToken: string,
    entitlement: BillingEntitlement,
    obfuscatedAccountId?: string,
    options: BindOptions = {}
  ): Promise<BindResult> {
    const now = Date.now();
    this.db.exec('BEGIN IMMEDIATE');
    try {
      const selectStmt = this.db.prepare(
        'SELECT purchase_token, owner_app_user_id, obfuscated_account_id, product_id, first_bound_at, latest_snapshot_version, entitlement_json, last_event_time_millis FROM token_records WHERE purchase_token = ?'
      );
      const existingRow = selectStmt.get(purchaseToken) as any;

      if (existingRow) {
        if (existingRow.owner_app_user_id !== ownerAppUserId) {
          this.db.exec('ROLLBACK');
          return {
            success: false,
            conflictOwner: existingRow.owner_app_user_id
          };
        }

        const existingEntitlement = JSON.parse(existingRow.entitlement_json) as BillingEntitlement;
        const lastEventTime = Number(existingRow.last_event_time_millis || 0);

        // 1. RTDN event ordering guard: stale older event arriving after newer event must not override
        if (options.eventTimeMillis !== undefined && options.eventTimeMillis > 0 && options.eventTimeMillis <= lastEventTime) {
          this.db.exec('ROLLBACK');
          const record: TokenOwnerRecord = {
            purchaseToken,
            ownerAppUserId,
            obfuscatedAccountId: existingRow.obfuscated_account_id || undefined,
            productId: existingRow.product_id,
            firstBoundAtMillis: Number(existingRow.first_bound_at),
            latestSnapshotVersion: Number(existingRow.latest_snapshot_version),
            entitlement: existingEntitlement
          };
          return { success: true, record, staleIgnored: true };
        }

        // 2. CAS version conflict guard:
        // If caller expected absent, but row exists:
        if (options.expectedAbsent || options.expectedVersion === null) {
          this.db.exec('ROLLBACK');
          const record: TokenOwnerRecord = {
            purchaseToken,
            ownerAppUserId,
            obfuscatedAccountId: existingRow.obfuscated_account_id || undefined,
            productId: existingRow.product_id,
            firstBoundAtMillis: Number(existingRow.first_bound_at),
            latestSnapshotVersion: Number(existingRow.latest_snapshot_version),
            entitlement: existingEntitlement
          };
          return { success: false, record, casConflict: true };
        }

        // If caller provided expectedVersion (version when Play query started),
        // and existing row has already been updated to a newer version by another concurrent operation:
        if (options.expectedVersion !== undefined && Number(existingRow.latest_snapshot_version) !== options.expectedVersion) {
          this.db.exec('ROLLBACK');
          const record: TokenOwnerRecord = {
            purchaseToken,
            ownerAppUserId,
            obfuscatedAccountId: existingRow.obfuscated_account_id || undefined,
            productId: existingRow.product_id,
            firstBoundAtMillis: Number(existingRow.first_bound_at),
            latestSnapshotVersion: Number(existingRow.latest_snapshot_version),
            entitlement: existingEntitlement
          };
          return { success: false, record, casConflict: true };
        }

        // Same owner: compute monotonically increasing version
        const nextVersion = Math.max(
          Number(existingRow.latest_snapshot_version) + 1,
          entitlement.snapshotVersion || 1
        );

        const updatedEntitlement: BillingEntitlement = {
          ...entitlement,
          ownerAppUserId,
          snapshotVersion: nextVersion
        };

        const newEventTime = Math.max(lastEventTime, options.eventTimeMillis || 0);

        const updateStmt = this.db.prepare(`
          UPDATE token_records
          SET latest_snapshot_version = ?,
              entitlement_json = ?,
              obfuscated_account_id = COALESCE(?, obfuscated_account_id),
              last_event_time_millis = ?,
              updated_at = ?
          WHERE purchase_token = ?
        `);
        updateStmt.run(
          nextVersion,
          JSON.stringify(updatedEntitlement),
          obfuscatedAccountId || null,
          newEventTime,
          now,
          purchaseToken
        );

        const seqUpsert = this.db.prepare(`
          INSERT INTO user_version_sequences (owner_app_user_id, latest_version, updated_at)
          VALUES (?, ?, ?)
          ON CONFLICT(owner_app_user_id) DO UPDATE SET
            latest_version = MAX(latest_version + 1, excluded.latest_version),
            updated_at = excluded.updated_at
        `);
        seqUpsert.run(ownerAppUserId, nextVersion, now);

        // Atomic grant + ack outbox enqueue in same transaction
        if (options.ackRequired && options.ackItem) {
          const ackUpsert = this.db.prepare(`
            INSERT INTO ack_retry_queue (
              purchase_token, product_id, product_type, owner_app_user_id,
              attempt_count, next_attempt_at, status, created_at, updated_at
            ) VALUES (?, ?, ?, ?, 0, ?, 'PENDING', ?, ?)
            ON CONFLICT(purchase_token) DO UPDATE SET
              status = CASE WHEN status = 'COMPLETED' THEN status ELSE 'PENDING' END,
              updated_at = excluded.updated_at
          `);
          ackUpsert.run(
            purchaseToken,
            options.ackItem.productId,
            options.ackItem.productType,
            options.ackItem.ownerAppUserId || ownerAppUserId,
            now,
            now,
            now
          );
        }

        this.db.exec('COMMIT');

        const record: TokenOwnerRecord = {
          purchaseToken,
          ownerAppUserId,
          obfuscatedAccountId: obfuscatedAccountId || existingRow.obfuscated_account_id || undefined,
          productId: entitlement.productId,
          firstBoundAtMillis: Number(existingRow.first_bound_at),
          latestSnapshotVersion: nextVersion,
          entitlement: updatedEntitlement
        };

        return { success: true, record };
      }

      // When row does not exist:
      // If caller expected it to exist (options.expectedVersion is a number, or expectedAbsent is false):
      if (options.expectedAbsent === false || (options.expectedVersion !== undefined && options.expectedVersion !== null)) {
        this.db.exec('ROLLBACK');
        return { success: false, casConflict: true };
      }

      // New binding: query user sequence
      const seqStmt = this.db.prepare('SELECT latest_version FROM user_version_sequences WHERE owner_app_user_id = ?');
      const seqRow = seqStmt.get(ownerAppUserId) as any;
      const currentSeq = seqRow ? Number(seqRow.latest_version) : 0;
      const initialVersion = Math.max(currentSeq + 1, entitlement.snapshotVersion || 1);

      const boundEntitlement: BillingEntitlement = {
        ...entitlement,
        ownerAppUserId,
        snapshotVersion: initialVersion
      };

      const newEventTime = options.eventTimeMillis || 0;

      const insertStmt = this.db.prepare(`
        INSERT INTO token_records (
          purchase_token, owner_app_user_id, obfuscated_account_id,
          product_id, first_bound_at, latest_snapshot_version,
          entitlement_json, last_event_time_millis, created_at, updated_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      `);
      insertStmt.run(
        purchaseToken,
        ownerAppUserId,
        obfuscatedAccountId || null,
        entitlement.productId,
        now,
        initialVersion,
        JSON.stringify(boundEntitlement),
        newEventTime,
        now,
        now
      );

      const seqUpsert = this.db.prepare(`
        INSERT INTO user_version_sequences (owner_app_user_id, latest_version, updated_at)
        VALUES (?, ?, ?)
        ON CONFLICT(owner_app_user_id) DO UPDATE SET
          latest_version = MAX(latest_version + 1, excluded.latest_version),
          updated_at = excluded.updated_at
      `);
      seqUpsert.run(ownerAppUserId, initialVersion, now);

      // Atomic grant + ack outbox enqueue in same transaction
      if (options.ackRequired && options.ackItem) {
        const ackUpsert = this.db.prepare(`
          INSERT INTO ack_retry_queue (
            purchase_token, product_id, product_type, owner_app_user_id,
            attempt_count, next_attempt_at, status, created_at, updated_at
          ) VALUES (?, ?, ?, ?, 0, ?, 'PENDING', ?, ?)
          ON CONFLICT(purchase_token) DO UPDATE SET
            status = CASE WHEN status = 'COMPLETED' THEN status ELSE 'PENDING' END,
            updated_at = excluded.updated_at
        `);
        ackUpsert.run(
          purchaseToken,
          options.ackItem.productId,
          options.ackItem.productType,
          options.ackItem.ownerAppUserId || ownerAppUserId,
          now,
          now,
          now
        );
      }

      this.db.exec('COMMIT');

      const record: TokenOwnerRecord = {
        purchaseToken,
        ownerAppUserId,
        obfuscatedAccountId,
        productId: entitlement.productId,
        firstBoundAtMillis: now,
        latestSnapshotVersion: initialVersion,
        entitlement: boundEntitlement
      };

      return { success: true, record };
    } catch (err: unknown) {
      try {
        this.db.exec('ROLLBACK');
      } catch {}
      throw err;
    }
  }

  async getEntitlementsByOwner(ownerAppUserId: string): Promise<BillingEntitlement[]> {
    const stmt = this.db.prepare(
      'SELECT entitlement_json FROM token_records WHERE owner_app_user_id = ? ORDER BY first_bound_at ASC'
    );
    const rows = stmt.all(ownerAppUserId) as any[];
    return rows.map((r) => JSON.parse(r.entitlement_json));
  }

  async getRecordByToken(purchaseToken: string): Promise<TokenOwnerRecord | undefined> {
    const stmt = this.db.prepare(
      'SELECT purchase_token, owner_app_user_id, obfuscated_account_id, product_id, first_bound_at, latest_snapshot_version, entitlement_json FROM token_records WHERE purchase_token = ?'
    );
    const row = stmt.get(purchaseToken) as any;
    if (!row) return undefined;

    return {
      purchaseToken: row.purchase_token,
      ownerAppUserId: row.owner_app_user_id,
      obfuscatedAccountId: row.obfuscated_account_id || undefined,
      productId: row.product_id,
      firstBoundAtMillis: Number(row.first_bound_at),
      latestSnapshotVersion: Number(row.latest_snapshot_version),
      entitlement: JSON.parse(row.entitlement_json)
    };
  }

  async getUserVersion(ownerAppUserId: string): Promise<number> {
    const stmt = this.db.prepare('SELECT latest_version FROM user_version_sequences WHERE owner_app_user_id = ?');
    const row = stmt.get(ownerAppUserId) as any;
    return row ? Number(row.latest_version) : 0;
  }

  async enqueueAckRetry(item: {
    purchaseToken: string;
    productId: string;
    productType: 'subs' | 'inapp';
    ownerAppUserId?: string;
  }): Promise<void> {
    const now = Date.now();
    const stmt = this.db.prepare(`
      INSERT INTO ack_retry_queue (
        purchase_token, product_id, product_type, owner_app_user_id,
        attempt_count, next_attempt_at, status, created_at, updated_at
      ) VALUES (?, ?, ?, ?, 0, ?, 'PENDING', ?, ?)
      ON CONFLICT(purchase_token) DO UPDATE SET
        next_attempt_at = excluded.next_attempt_at,
        status = 'PENDING',
        updated_at = excluded.updated_at
    `);
    stmt.run(
      item.purchaseToken,
      item.productId,
      item.productType,
      item.ownerAppUserId || null,
      now,
      now,
      now
    );
  }

  async getPendingAckRetries(limit: number = 20): Promise<AckRetryItem[]> {
    const now = Date.now();
    const stmt = this.db.prepare(`
      SELECT purchase_token, product_id, product_type, owner_app_user_id,
             attempt_count, last_attempt_at, next_attempt_at, status, error_message
      FROM ack_retry_queue
      WHERE status = 'PENDING' AND next_attempt_at <= ?
      ORDER BY next_attempt_at ASC
      LIMIT ?
    `);
    const rows = stmt.all(now, limit) as any[];
    return rows.map((r) => ({
      purchaseToken: r.purchase_token,
      productId: r.product_id,
      productType: r.product_type,
      ownerAppUserId: r.owner_app_user_id || undefined,
      attemptCount: Number(r.attempt_count),
      lastAttemptAt: r.last_attempt_at ? Number(r.last_attempt_at) : undefined,
      nextAttemptAt: Number(r.next_attempt_at),
      status: r.status,
      errorMessage: r.error_message || undefined
    }));
  }

  async markAckSuccess(purchaseToken: string): Promise<void> {
    const now = Date.now();
    const stmt = this.db.prepare(`
      UPDATE ack_retry_queue
      SET status = 'COMPLETED', updated_at = ?
      WHERE purchase_token = ?
    `);
    stmt.run(now, purchaseToken);
  }

  async markAckFailure(purchaseToken: string, errorMessage: string, retryDelayMs: number = 30000): Promise<void> {
    const now = Date.now();
    const isPermanent = retryDelayMs < 0;
    const status = isPermanent ? 'FAILED' : 'PENDING';
    const nextAttempt = isPermanent ? 0 : now + retryDelayMs;
    const stmt = this.db.prepare(`
      UPDATE ack_retry_queue
      SET attempt_count = attempt_count + 1,
          last_attempt_at = ?,
          next_attempt_at = ?,
          status = ?,
          error_message = ?,
          updated_at = ?
      WHERE purchase_token = ?
    `);
    stmt.run(now, nextAttempt, status, errorMessage, now, purchaseToken);
  }

  async clear(): Promise<void> {
    this.db.exec('DELETE FROM token_records; DELETE FROM user_version_sequences; DELETE FROM ack_retry_queue;');
  }

  close(): void {
    try {
      this.db.close();
    } catch {}
  }
}
