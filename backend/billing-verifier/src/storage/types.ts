import type { BillingEntitlement } from '../types.ts';

export interface TokenOwnerRecord {
  purchaseToken: string;
  ownerAppUserId: string;
  obfuscatedAccountId?: string;
  productId: string;
  firstBoundAtMillis: number;
  latestSnapshotVersion: number;
  entitlement: BillingEntitlement;
}

export interface BindResult {
  success: boolean;
  record?: TokenOwnerRecord;
  conflictOwner?: string;
  staleIgnored?: boolean;
  casConflict?: boolean;
}

export interface BindOptions {
  eventTimeMillis?: number;
  expectedVersion?: number | null;
  expectedAbsent?: boolean;
  ackRequired?: boolean;
  ackItem?: {
    productId: string;
    productType: 'subs' | 'inapp';
    ownerAppUserId?: string;
  };
}

export interface AckRetryItem {
  purchaseToken: string;
  productId: string;
  productType: 'subs' | 'inapp';
  ownerAppUserId?: string;
  attemptCount: number;
  lastAttemptAt?: number;
  nextAttemptAt: number;
  status: 'PENDING' | 'COMPLETED' | 'FAILED';
  errorMessage?: string;
}

export interface StorageDriver {
  bindOrUpdate(
    ownerAppUserId: string,
    purchaseToken: string,
    entitlement: BillingEntitlement,
    obfuscatedAccountId?: string,
    options?: BindOptions
  ): Promise<BindResult>;

  getEntitlementsByOwner(ownerAppUserId: string): Promise<BillingEntitlement[]>;

  getRecordByToken(purchaseToken: string): Promise<TokenOwnerRecord | undefined>;

  getUserVersion(ownerAppUserId: string): Promise<number>;

  enqueueAckRetry(item: {
    purchaseToken: string;
    productId: string;
    productType: 'subs' | 'inapp';
    ownerAppUserId?: string;
  }): Promise<void>;

  getPendingAckRetries(limit?: number): Promise<AckRetryItem[]>;

  markAckSuccess(purchaseToken: string): Promise<void>;

  markAckFailure(purchaseToken: string, errorMessage: string, retryDelayMs?: number): Promise<void>;

  clear(): Promise<void>;

  close(): void;
}
