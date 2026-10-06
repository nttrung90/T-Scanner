import type { BillingEntitlement } from './types.ts';
import { SqliteStorageDriver } from './storage/sqliteDriver.ts';
import type { StorageDriver, TokenOwnerRecord, BindResult, AckRetryItem, BindOptions } from './storage/types.ts';

export type { TokenOwnerRecord, BindResult, AckRetryItem };

/**
 * Authoritative transactional store for token ownership and entitlement snapshots.
 * Backed by durable SQLite storage with atomic CAS monotonic version sequencing.
 */
export class EntitlementStore {
  private driver: StorageDriver;

  constructor(driverOrPath?: StorageDriver | string) {
    if (typeof driverOrPath === 'string') {
      this.driver = new SqliteStorageDriver(driverOrPath);
    } else if (driverOrPath) {
      this.driver = driverOrPath;
    } else {
      const dbPath = process.env.DATABASE_URL || process.env.SQLITE_PATH;
      if (!dbPath) {
        if (process.env.NODE_ENV === 'production') {
          throw new Error('FATAL: Missing DATABASE_URL or SQLITE_PATH in production. In-memory storage is prohibited.');
        }
        this.driver = new SqliteStorageDriver(':memory:');
      } else {
        if (process.env.NODE_ENV === 'production' && dbPath === ':memory:') {
          throw new Error('FATAL: In-memory storage (:memory:) is prohibited in production.');
        }
        this.driver = new SqliteStorageDriver(dbPath);
      }
    }
  }

  isDurable(): boolean {
    if (this.driver instanceof SqliteStorageDriver) {
      return (this.driver as any).dbPath !== ':memory:';
    }
    return true;
  }

  getDriver(): StorageDriver {
    return this.driver;
  }

  /**
   * Atomically binds a purchase token to an app user or updates an existing binding.
   *
   * Invariants:
   * - A purchase token can only ever belong to one ownerAppUserId.
   * - Attempting to bind a token to a second user results in success: false and conflictOwner.
   * - Replays by the same owner succeed idempotently, bumping snapshot version monotonically.
   */
  async bindOrUpdate(
    ownerAppUserId: string,
    purchaseToken: string,
    entitlement: BillingEntitlement,
    obfuscatedAccountId?: string,
    options?: BindOptions
  ): Promise<BindResult> {
    return this.driver.bindOrUpdate(ownerAppUserId, purchaseToken, entitlement, obfuscatedAccountId, options);
  }

  /**
   * Retrieves all entitlements currently owned by a user.
   */
  async getEntitlementsByOwner(ownerAppUserId: string): Promise<BillingEntitlement[]> {
    return this.driver.getEntitlementsByOwner(ownerAppUserId);
  }

  /**
   * Retrieves the record for a token if present.
   */
  async getRecordByToken(purchaseToken: string): Promise<TokenOwnerRecord | undefined> {
    return this.driver.getRecordByToken(purchaseToken);
  }

  /**
   * Retrieves the current monotonic version sequence for a user.
   */
  async getUserVersion(ownerAppUserId: string): Promise<number> {
    return this.driver.getUserVersion(ownerAppUserId);
  }

  /**
   * Enqueues an acknowledge retry item in the durable outbox queue.
   */
  async enqueueAckRetry(item: {
    purchaseToken: string;
    productId: string;
    productType: 'subs' | 'inapp';
    ownerAppUserId?: string;
  }): Promise<void> {
    return this.driver.enqueueAckRetry(item);
  }

  /**
   * Retrieves pending ack retry items ready for processing.
   */
  async getPendingAckRetries(limit?: number): Promise<AckRetryItem[]> {
    return this.driver.getPendingAckRetries(limit);
  }

  /**
   * Marks an acknowledge retry as successfully completed.
   */
  async markAckSuccess(purchaseToken: string): Promise<void> {
    return this.driver.markAckSuccess(purchaseToken);
  }

  /**
   * Updates an acknowledge retry attempt count, error message, and next attempt time or failure.
   */
  async markAckFailure(purchaseToken: string, errorMessage: string, retryDelayMs?: number): Promise<void> {
    return this.driver.markAckFailure(purchaseToken, errorMessage, retryDelayMs);
  }

  /**
   * Clears all records (useful for test isolation).
   */
  async clear(): Promise<void> {
    return this.driver.clear();
  }

  /**
   * Closes the underlying database connection.
   */
  close(): void {
    this.driver.close();
  }
}
