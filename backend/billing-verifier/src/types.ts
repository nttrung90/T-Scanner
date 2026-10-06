/**
 * Types and data models for T-Scanner VIP Billing Verifier.
 * Strictly aligned with docs/billing/ENTITLEMENT_CONTRACT.md.
 */

export type EntitlementSource =
  | 'GOOGLE_PLAY_SUBSCRIPTION'
  | 'GOOGLE_PLAY_INAPP'
  | 'LEGACY_LOCAL'
  | 'PROMO';

export type EntitlementState =
  | 'VERIFIED_ACTIVE'
  | 'IN_GRACE_PERIOD'
  | 'CANCELED_ACTIVE'
  | 'PENDING_PAYMENT'
  | 'ON_HOLD'
  | 'PAUSED'
  | 'EXPIRED'
  | 'REVOKED'
  | 'UNVERIFIED_CLIENT';

export interface BillingEntitlement {
  id: string;
  ownerAppUserId: string;
  productId: string;
  productType: 'subs' | 'inapp';
  purchaseToken: string;
  orderId?: string;
  source: EntitlementSource;
  state: EntitlementState;
  purchaseTimeMillis: number;
  expiryTimeMillis: number | null; // null represents lifetime / non-expiring
  autoRenewing: boolean;
  verifiedAtMillis: number;
  snapshotVersion: number;
}

export interface UserEntitlementSnapshot {
  ownerAppUserId: string;
  entitlements: BillingEntitlement[];
  computedAtMillis: number;
}

export interface VerificationRequest {
  ownerAppUserId?: string;
  productId: string;
  productType: 'subs' | 'inapp';
  purchaseToken: string;
  orderId?: string;
  obfuscatedAccountId?: string;
  clientPurchaseTimeMillis: number;
  packageName?: string;
}

export type RejectionReason =
  | 'OWNERSHIP_CONFLICT'
  | 'INVALID_SIGNATURE_OR_TOKEN'
  | 'PURCHASE_REVOKED'
  | 'PURCHASE_EXPIRED'
  | 'PRODUCT_NOT_ALLOWED'
  | 'PACKAGE_NAME_MISMATCH'
  | 'ACCOUNT_HASH_MISMATCH';

export type LinkedResolutionOutcome =
  | {
      status: 'RESOLVED';
      purchaseToken: string;
      productId: string;
      entitlementState: EntitlementState;
      entitlement: BillingEntitlement;
    }
  | {
      status: 'UNRESOLVED_TRANSIENT';
      purchaseToken: string;
      productId?: string;
      message: string;
    }
  | {
      status: 'UNRESOLVED_PERMANENT';
      purchaseToken: string;
      productId?: string;
      reason: 'OWNERSHIP_CONFLICT' | 'NOT_FOUND' | 'INVALID_TOKEN' | 'PRODUCT_NOT_ALLOWED' | 'ACCOUNT_HASH_MISMATCH';
      message: string;
    }
  | {
      status: 'SKIPPED';
      purchaseToken: string;
      message: string;
    };

export interface VerificationResponse {
  status: 'SUCCESS' | 'REJECTED' | 'PENDING' | 'TRANSIENT_ERROR';
  entitlement?: BillingEntitlement;
  reason?: RejectionReason;
  message?: string;
  linkedResolution?: LinkedResolutionOutcome;
}

export interface RestoreRequest {
  ownerAppUserId?: string;
  purchases: Array<{
    productId: string;
    productType: 'subs' | 'inapp';
    purchaseToken: string;
  }>;
}

export interface RestoreResponse {
  status: 'SUCCESS' | 'TRANSIENT_ERROR' | 'PARTIAL' | 'REJECTED';
  snapshot: UserEntitlementSnapshot;
  message?: string;
  results?: Array<{
    purchaseToken: string;
    status: 'SUCCESS' | 'EXPIRED' | 'REVOKED' | 'TRANSIENT_ERROR' | 'REJECTED' | 'PENDING';
    reason?: RejectionReason;
  }>;
}

export interface AcknowledgeRequest {
  purchaseToken: string;
  productId: string;
  productType: 'subs' | 'inapp';
  ownerAppUserId?: string;
}

export interface UserPrincipal {
  sub: string;
  email?: string;
  email_verified?: boolean;
  aud?: string;
  iss?: string;
  exp?: number;
  [key: string]: unknown;
}

export interface PubSubPrincipal {
  email?: string;
  email_verified?: boolean;
  sub?: string;
  aud?: string;
  iss?: string;
  exp?: number;
  [key: string]: unknown;
}

export interface AuthConfig {
  jwtSecret?: string;
  allowHmacFallback?: boolean;
  googleClientId?: string;
  expectedAudience?: string;
  allowedIssuers?: string[];
  jwksUri?: string;
  jwksFetchFn?: (url: string) => Promise<{ keys: Array<Record<string, unknown>> }>;
  keyRotationTtlMs?: number;
  pubsubExpectedAudience?: string;
  pubsubExpectedServiceAccount?: string;
  pubsubSecretToken?: string;
  pubsubJwksUri?: string;
}
