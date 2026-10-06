import { createHash } from 'node:crypto';
import type {
  BillingEntitlement,
  EntitlementSource,
  EntitlementState,
  RestoreRequest,
  RestoreResponse,
  VerificationRequest,
  VerificationResponse,
  LinkedResolutionOutcome
} from './types.ts';
import { EntitlementStore } from './store.ts';
import { GooglePlayApiError, type GooglePlayBillingApi } from './googlePlayClient.ts';

export const APP_PACKAGE_NAME = 'com.tscanner.app';
export const ALLOWED_SUBSCRIPTION_IDS = new Set([
  'tscanner_vip_yearly',
  'tscanner_vip_monthly',
  'vip_yearly',
  'vip_monthly'
]);
export const ALLOWED_INAPP_IDS = new Set([
  'tscanner_vip_lifetime',
  'vip_lifetime'
]);

export const PRODUCT_ALIASES: Record<string, string> = {
  'vip_yearly': 'tscanner_vip_yearly',
  'vip_monthly': 'tscanner_vip_monthly',
  'vip_lifetime': 'tscanner_vip_lifetime'
};

export interface Logger {
  info(msg: string): void;
  warn(msg: string): void;
  error(msg: string, err?: unknown): void;
}

export const defaultLogger: Logger = {
  info: (msg) => console.log(`[INFO] ${msg}`),
  warn: (msg) => console.warn(`[WARN] ${msg}`),
  error: (msg, err) => console.error(`[ERROR] ${msg}`, err || '')
};

export class BillingVerifierService {
  private googlePlayApi: GooglePlayBillingApi;
  private store: EntitlementStore;
  private logger: Logger;

  constructor(
    googlePlayApi: GooglePlayBillingApi,
    store: EntitlementStore,
    logger: Logger = defaultLogger
  ) {
    this.googlePlayApi = googlePlayApi;
    this.store = store;
    this.logger = logger;
  }

  /**
   * Masks a sensitive purchase token for safe structured logging.
   */
  private maskToken(token: string): string {
    if (!token || token.length < 8) return '***';
    return `${token.slice(0, 6)}...${token.slice(-4)}`;
  }

  /**
   * Computes the deterministic SHA-256 hash of the canonical app user ID.
   */
  computeObfuscatedAccountId(canonicalUserId: string): string {
    return createHash('sha256').update(canonicalUserId).digest('hex');
  }

  /**
   * Authoritatively verifies a purchase against Google Play and binds ownership.
   */
  async verifyPurchase(req: VerificationRequest): Promise<VerificationResponse> {
    const masked = this.maskToken(req.purchaseToken);
    this.logger.info(`Verifying purchase for user=${req.ownerAppUserId}, product=${req.productId}, token=${masked}`);

    // 1. Package name validation
    const pkg = req.packageName || APP_PACKAGE_NAME;
    if (pkg !== APP_PACKAGE_NAME) {
      this.logger.warn(`Package name mismatch: expected ${APP_PACKAGE_NAME}, got ${pkg}`);
      return {
        status: 'REJECTED',
        reason: 'PACKAGE_NAME_MISMATCH',
        message: `Package name '${pkg}' does not match '${APP_PACKAGE_NAME}'`
      };
    }

    // 2. Product ID allowlist validation
    const isSub = req.productType === 'subs';
    const isAllowed = isSub ? ALLOWED_SUBSCRIPTION_IDS.has(req.productId) : ALLOWED_INAPP_IDS.has(req.productId);
    if (!isAllowed) {
      this.logger.warn(`Disallowed product ID: ${req.productId} for type ${req.productType}`);
      return {
        status: 'REJECTED',
        reason: 'PRODUCT_NOT_ALLOWED',
        message: `Product '${req.productId}' is not permitted in catalog.`
      };
    }

    // 3. Query authoritative Google Play Developer API with CAS snapshot and bounded retry
    const MAX_CAS_RETRIES = 5;
    for (let attempt = 0; attempt <= MAX_CAS_RETRIES; attempt++) {
      const existingBeforeQuery = await this.store.getRecordByToken(req.purchaseToken);
      const expectedVersion = existingBeforeQuery ? existingBeforeQuery.latestSnapshotVersion : null;

      let expiryTimeMillis: number | null = null;
      let autoRenewing = false;
      let state: EntitlementState = 'VERIFIED_ACTIVE';
      let orderId = req.orderId;
      let externalAccountIdFromPlay: string | undefined;
      let acknowledgementState: number = 1;
      let startTimeMillis: number | undefined;
      let linkedPurchaseToken: string | undefined;

      try {
        if (isSub) {
          const subResult = await this.googlePlayApi.getSubscription(pkg, req.productId, req.purchaseToken);
          acknowledgementState = subResult.acknowledgementState;
          orderId = subResult.orderId || orderId;
          externalAccountIdFromPlay = subResult.obfuscatedExternalAccountId;
          autoRenewing = subResult.autoRenewing;
          expiryTimeMillis = subResult.expiryTimeMillis;
          startTimeMillis = subResult.startTimeMillis;
          linkedPurchaseToken = subResult.linkedPurchaseToken;

          // Validate ObfuscatedAccountId if recorded at purchase time BEFORE any mutation (R05)
          const expectedHash = this.computeObfuscatedAccountId(req.ownerAppUserId);
          if (externalAccountIdFromPlay && externalAccountIdFromPlay !== expectedHash) {
            this.logger.warn(
              `ObfuscatedAccountId mismatch: external=${externalAccountIdFromPlay}, expected=${expectedHash}`
            );
            return {
              status: 'REJECTED',
              reason: 'ACCOUNT_HASH_MISMATCH',
              message: 'This purchase belongs to another Google Play account owner.'
            };
          }

          if (subResult.subscriptionState) {
            switch (subResult.subscriptionState) {
              case 'SUBSCRIPTION_STATE_PENDING':
                return {
                  status: 'PENDING',
                  message: 'Subscription payment is pending processing by Google Play.'
                };
              case 'SUBSCRIPTION_STATE_PENDING_PURCHASE_CANCELED':
                state = 'REVOKED';
                break;
              case 'SUBSCRIPTION_STATE_ACTIVE':
                if (subResult.expiryTimeMillis && Date.now() >= subResult.expiryTimeMillis) {
                  state = 'EXPIRED';
                } else {
                  state = subResult.autoRenewing ? 'VERIFIED_ACTIVE' : 'CANCELED_ACTIVE';
                }
                break;
              case 'SUBSCRIPTION_STATE_IN_GRACE_PERIOD':
                state = 'IN_GRACE_PERIOD';
                break;
              case 'SUBSCRIPTION_STATE_CANCELED':
                if (subResult.expiryTimeMillis && Date.now() >= subResult.expiryTimeMillis) {
                  state = 'EXPIRED';
                } else {
                  state = 'CANCELED_ACTIVE';
                }
                break;
              case 'SUBSCRIPTION_STATE_PAUSED':
                state = 'PAUSED';
                break;
              case 'SUBSCRIPTION_STATE_ON_HOLD':
                state = 'ON_HOLD';
                break;
              case 'SUBSCRIPTION_STATE_EXPIRED':
                state = 'EXPIRED';
                break;
              default:
                return {
                  status: 'REJECTED',
                  reason: 'INVALID_SIGNATURE_OR_TOKEN',
                  message: `Unknown or invalid subscription state from Google Play: ${subResult.subscriptionState}`
                };
            }
          } else {
            // Subscriptions v1 fallback
            if (subResult.paymentState === 0) {
              return {
                status: 'PENDING',
                message: 'Subscription payment is pending processing by Google Play.'
              };
            }
            if (Date.now() >= subResult.expiryTimeMillis) {
              state = 'EXPIRED';
            } else if (!subResult.autoRenewing) {
              state = 'CANCELED_ACTIVE';
            } else {
              state = 'VERIFIED_ACTIVE';
            }
          }
        } else {
          const inAppResult = await this.googlePlayApi.getInAppProduct(pkg, req.productId, req.purchaseToken);
          acknowledgementState = inAppResult.acknowledgementState;
          orderId = inAppResult.orderId || orderId;
          externalAccountIdFromPlay = inAppResult.obfuscatedExternalAccountId;

          // In-app purchaseState: 0: Purchased, 1: Canceled, 2: Pending
          if (inAppResult.purchaseState === 2) {
            return {
              status: 'PENDING',
              message: 'In-app purchase is pending processing.'
            };
          }
          if (inAppResult.purchaseState === 1) {
            state = 'REVOKED';
          } else {
            state = 'VERIFIED_ACTIVE';
          }
          expiryTimeMillis = null;
          startTimeMillis = inAppResult.purchaseTimeMillis;
        }
      } catch (err: unknown) {
        if (err instanceof GooglePlayApiError) {
          if (err.isTransient || err.statusCode >= 500 || err.statusCode === 429) {
            this.logger.warn(`Transient Google Play API error (${err.statusCode}): ${err.message}`);
            return {
              status: 'TRANSIENT_ERROR',
              message: 'Google Play service temporarily unavailable. Please retry later.'
            };
          }
          if (err.statusCode === 404) {
            return {
              status: 'REJECTED',
              reason: 'INVALID_SIGNATURE_OR_TOKEN',
              message: 'Purchase token not recognized by Google Play.'
            };
          }
          if (err.statusCode === 400) {
            return {
              status: 'REJECTED',
              reason: /product|mismatch/i.test(err.message) ? 'PRODUCT_NOT_ALLOWED' : 'INVALID_SIGNATURE_OR_TOKEN',
              message: err.message
            };
          }
        }
        this.logger.error(`Error querying Google Play:`, err);
        return {
          status: 'TRANSIENT_ERROR',
          message: 'Unexpected verification error.'
        };
      }

      // 4. Validate ObfuscatedAccountId if recorded at purchase time
      const expectedHash = this.computeObfuscatedAccountId(req.ownerAppUserId);
      if (externalAccountIdFromPlay && externalAccountIdFromPlay !== expectedHash) {
        this.logger.warn(
          `ObfuscatedAccountId mismatch: external=${externalAccountIdFromPlay}, expected=${expectedHash}`
        );
        return {
          status: 'REJECTED',
          reason: 'ACCOUNT_HASH_MISMATCH',
          message: 'This purchase belongs to another Google Play account owner.'
        };
      }

      // 5. Build authoritative entitlement
      const source: EntitlementSource = isSub
        ? 'GOOGLE_PLAY_SUBSCRIPTION'
        : 'GOOGLE_PLAY_INAPP';

      const entitlement: BillingEntitlement = {
        id: `${source}_${req.purchaseToken}`,
        ownerAppUserId: req.ownerAppUserId,
        productId: req.productId,
        productType: req.productType,
        purchaseToken: req.purchaseToken,
        orderId,
        source,
        state,
        purchaseTimeMillis: startTimeMillis ? Number(startTimeMillis) : req.clientPurchaseTimeMillis,
        expiryTimeMillis,
        autoRenewing,
        verifiedAtMillis: Date.now(),
        snapshotVersion: 1
      };

      const needsAck = (state === 'VERIFIED_ACTIVE' || state === 'CANCELED_ACTIVE' || state === 'IN_GRACE_PERIOD') && acknowledgementState === 0;

      // 6. Bind ownership, save to store, and enqueue acknowledge outbox job atomically in the same transaction
      const bindResult = await this.store.bindOrUpdate(
        req.ownerAppUserId,
        req.purchaseToken,
        entitlement,
        req.obfuscatedAccountId || expectedHash,
        {
          expectedVersion,
          ackRequired: needsAck,
          ackItem: needsAck ? {
            productId: req.productId,
            productType: req.productType,
            ownerAppUserId: req.ownerAppUserId
          } : undefined
        }
      );

      if (!bindResult.success) {
        if (bindResult.casConflict) {
          if (attempt < MAX_CAS_RETRIES) {
            this.logger.warn(`CAS conflict on token ${masked}, retrying verification attempt ${attempt + 1}/${MAX_CAS_RETRIES}...`);
            continue;
          }
          this.logger.error(`CAS conflict retry budget exhausted for token ${masked}`);
          return {
            status: 'TRANSIENT_ERROR',
            message: 'Concurrent modification conflict while verifying purchase. Please retry.'
          };
        }

        this.logger.warn(
          `Ownership conflict: purchase token already bound to user '${bindResult.conflictOwner}', requested by '${req.ownerAppUserId}'`
        );
        return {
          status: 'REJECTED',
          reason: 'OWNERSHIP_CONFLICT',
          message: `This purchase receipt is permanently linked to another user account.`
        };
      }

      if (needsAck) {
        this.logger.info(`Atomically committed entitlement and outbox ack job for token ${masked}`);
      }

      this.logger.info(`Successfully verified and bound purchase: token=${masked} to owner=${req.ownerAppUserId}`);

      let linkedResolution: LinkedResolutionOutcome | undefined;
      if (isSub && linkedPurchaseToken && linkedPurchaseToken !== req.purchaseToken) {
        try {
          linkedResolution = await this.resolveLinkedSubscriptionToken(
            pkg,
            req.productId,
            linkedPurchaseToken,
            req.ownerAppUserId,
            new Set([req.purchaseToken])
          );
        } catch (linkErr) {
          this.logger.warn(`Failed to resolve linked token: ${linkErr}`);
          linkedResolution = {
            status: 'UNRESOLVED_TRANSIENT',
            purchaseToken: linkedPurchaseToken,
            message: String(linkErr)
          };
        }
      }

      const committed = bindResult.record!.entitlement;
      if (committed.state === 'VERIFIED_ACTIVE' || committed.state === 'CANCELED_ACTIVE' || committed.state === 'IN_GRACE_PERIOD') {
        return {
          status: 'SUCCESS',
          entitlement: committed,
          linkedResolution
        };
      } else if (committed.state === 'EXPIRED') {
        return {
          status: 'REJECTED',
          reason: 'PURCHASE_EXPIRED',
          entitlement: committed,
          message: 'Subscription period has expired.',
          linkedResolution
        };
      } else if (committed.state === 'REVOKED') {
        return {
          status: 'REJECTED',
          reason: 'PURCHASE_REVOKED',
          entitlement: committed,
          message: 'Purchase was refunded or canceled by Google Play.',
          linkedResolution
        };
      } else if (committed.state === 'ON_HOLD') {
        return {
          status: 'REJECTED',
          reason: 'PURCHASE_EXPIRED',
          entitlement: committed,
          message: 'Subscription is on hold due to payment failure.',
          linkedResolution
        };
      } else if (committed.state === 'PAUSED') {
        return {
          status: 'REJECTED',
          reason: 'PURCHASE_EXPIRED',
          entitlement: committed,
          message: 'Subscription is paused.',
          linkedResolution
        };
      } else {
        return {
          status: 'PENDING',
          message: `Subscription is in state ${committed.state}.`,
          entitlement: committed,
          linkedResolution
        };
      }
    }

    return {
      status: 'TRANSIENT_ERROR',
      message: 'Concurrent modification conflict while verifying purchase. Please retry.'
    };
  }

  /**
   * Restores all verified entitlements belonging to the authenticated owner.
   */
  async restorePurchases(req: RestoreRequest): Promise<RestoreResponse> {
    this.logger.info(`Restoring purchases for user=${req.ownerAppUserId}, candidateTokens=${req.purchases.length}`);

    const isAuthoritative = (st: 'SUCCESS' | 'EXPIRED' | 'REVOKED' | 'TRANSIENT_ERROR' | 'REJECTED' | 'PENDING') => {
      return st === 'SUCCESS' || st === 'EXPIRED' || st === 'REVOKED';
    };

    const resultMap = new Map<string, {
      purchaseToken: string;
      status: 'SUCCESS' | 'EXPIRED' | 'REVOKED' | 'TRANSIENT_ERROR' | 'REJECTED' | 'PENDING';
      reason?: any;
    }>();

    const recordResult = (
      token: string,
      status: 'SUCCESS' | 'EXPIRED' | 'REVOKED' | 'TRANSIENT_ERROR' | 'REJECTED' | 'PENDING',
      reason?: any
    ) => {
      const prev = resultMap.get(token);
      if (!prev) {
        resultMap.set(token, { purchaseToken: token, status, reason });
        return;
      }
      // Authoritative over transient/pending:
      if (isAuthoritative(prev.status) && !isAuthoritative(status)) {
        return;
      }
      // Definitive rejection over transient/pending:
      if (prev.status === 'REJECTED' && (status === 'TRANSIENT_ERROR' || status === 'PENDING')) {
        return;
      }
      // Latest authoritative or non-transient state wins
      resultMap.set(token, { purchaseToken: token, status, reason });
    };

    // Candidate set = known owner records in store + client candidates, deduplicated by token (R01 backend)
    const knownEntitlements = req.ownerAppUserId
      ? await this.store.getEntitlementsByOwner(req.ownerAppUserId)
      : [];

    const candidateMap = new Map<string, { productId: string; productType: 'subs' | 'inapp'; purchaseToken: string }>();

    for (const ent of knownEntitlements) {
      if (ent.source === 'GOOGLE_PLAY_SUBSCRIPTION' || ent.source === 'GOOGLE_PLAY_INAPP') {
        candidateMap.set(ent.purchaseToken, {
          productId: ent.productId,
          productType: ent.productType,
          purchaseToken: ent.purchaseToken
        });
      }
    }

    for (const p of req.purchases) {
      if (!candidateMap.has(p.purchaseToken)) {
        candidateMap.set(p.purchaseToken, {
          productId: p.productId,
          productType: p.productType,
          purchaseToken: p.purchaseToken
        });
      }
    }

    // Verify candidates to refresh state authoritatively
    for (const p of candidateMap.values()) {
      const existing = resultMap.get(p.purchaseToken);
      if (existing && isAuthoritative(existing.status)) {
        // Skip redundant network queries if token was already authoritatively resolved
        continue;
      }

      const verifyRes = await this.verifyPurchase({
        ownerAppUserId: req.ownerAppUserId,
        productId: p.productId,
        productType: p.productType,
        purchaseToken: p.purchaseToken,
        clientPurchaseTimeMillis: Date.now()
      });

      if (verifyRes.status === 'SUCCESS') {
        recordResult(p.purchaseToken, 'SUCCESS');
      } else if (verifyRes.status === 'PENDING') {
        recordResult(p.purchaseToken, 'PENDING');
      } else if (verifyRes.status === 'REJECTED') {
        if (verifyRes.reason === 'PURCHASE_EXPIRED') {
          recordResult(p.purchaseToken, 'EXPIRED', verifyRes.reason);
        } else if (verifyRes.reason === 'PURCHASE_REVOKED') {
          recordResult(p.purchaseToken, 'REVOKED', verifyRes.reason);
        } else {
          recordResult(p.purchaseToken, 'REJECTED', verifyRes.reason);
        }
      } else if (verifyRes.status === 'TRANSIENT_ERROR') {
        recordResult(p.purchaseToken, 'TRANSIENT_ERROR');
      } else {
        recordResult(p.purchaseToken, 'REJECTED', verifyRes.reason);
      }

      if (verifyRes.linkedResolution) {
        const lr = verifyRes.linkedResolution;
        if (lr.status === 'RESOLVED') {
          let outcomeStatus: 'SUCCESS' | 'EXPIRED' | 'REVOKED' | 'REJECTED' = 'SUCCESS';
          let reason: any;
          if (lr.entitlementState === 'VERIFIED_ACTIVE' || lr.entitlementState === 'CANCELED_ACTIVE' || lr.entitlementState === 'IN_GRACE_PERIOD') {
            outcomeStatus = 'SUCCESS';
          } else if (lr.entitlementState === 'EXPIRED') {
            outcomeStatus = 'EXPIRED';
            reason = 'PURCHASE_EXPIRED';
          } else if (lr.entitlementState === 'REVOKED') {
            outcomeStatus = 'REVOKED';
            reason = 'PURCHASE_REVOKED';
          } else {
            outcomeStatus = 'REJECTED';
          }
          recordResult(lr.purchaseToken, outcomeStatus, reason);
        } else if (lr.status === 'UNRESOLVED_TRANSIENT') {
          recordResult(lr.purchaseToken, 'TRANSIENT_ERROR');
        } else if (lr.status === 'UNRESOLVED_PERMANENT') {
          recordResult(lr.purchaseToken, 'REJECTED', lr.reason as any);
        }
      }
    }

    const results = Array.from(resultMap.values());
    const hasAuthoritativeResolution = results.some(r => isAuthoritative(r.status));
    const hasTransientError = results.some(r => r.status === 'TRANSIENT_ERROR');

    const entitlements = req.ownerAppUserId
      ? await this.store.getEntitlementsByOwner(req.ownerAppUserId)
      : [];

    const knownTokenSet = new Set(knownEntitlements.map(e => e.purchaseToken));
    const knownActiveUnresolved = results.some(
      r => (r.status === 'REJECTED' || r.status === 'TRANSIENT_ERROR') &&
        knownTokenSet.has(r.purchaseToken) &&
        entitlements.some(e => e.purchaseToken === r.purchaseToken && (e.state === 'VERIFIED_ACTIVE' || e.state === 'CANCELED_ACTIVE' || e.state === 'IN_GRACE_PERIOD'))
    );

    const hasMixedResolution = hasAuthoritativeResolution && results.some(r => r.status === 'REJECTED' || r.status === 'TRANSIENT_ERROR' || r.status === 'PENDING');

    let status: 'SUCCESS' | 'TRANSIENT_ERROR' | 'PARTIAL' | 'REJECTED' = 'SUCCESS';
    let message: string | undefined;

    if (candidateMap.size > 0 && hasTransientError) {
      status = hasAuthoritativeResolution ? 'PARTIAL' : 'TRANSIENT_ERROR';
      message = status === 'PARTIAL'
        ? 'One or more purchases could not be verified due to network issues.'
        : 'Verification service temporarily unavailable.';
    } else if (candidateMap.size > 0 && results.every(r => r.status === 'REJECTED' && r.reason === 'INVALID_SIGNATURE_OR_TOKEN')) {
      status = 'REJECTED';
      message = 'None of the submitted purchases could be verified.';
    } else if (candidateMap.size > 0 && results.every(r => r.status === 'PENDING')) {
      status = 'PARTIAL';
      message = 'Submitted purchase is currently pending approval or payment.';
    } else if (knownActiveUnresolved || hasMixedResolution || results.some(r => r.status === 'PENDING')) {
      status = 'PARTIAL';
      message = 'One or more purchases could not be verified with Google Play.';
    }

    return {
      status,
      message,
      snapshot: {
        ownerAppUserId: req.ownerAppUserId,
        entitlements,
        computedAtMillis: Date.now()
      },
      results
    };
  }

  public async resolveLinkedSubscriptionToken(
    pkg: string,
    currentProductId: string,
    linkedToken: string,
    ownerAppUserId: string,
    visitedTokens: Set<string>,
    depth = 0
  ): Promise<LinkedResolutionOutcome> {
    if (depth >= 1 || visitedTokens.has(linkedToken)) {
      this.logger.warn(`Stopping linked token resolution cycle or depth bound for token ${maskToken(linkedToken)}`);
      return {
        status: 'SKIPPED',
        purchaseToken: linkedToken,
        message: 'Cycle or depth bound reached'
      };
    }
    visitedTokens.add(linkedToken);

    const MAX_LINKED_CAS_RETRIES = 5;
    for (let attempt = 0; attempt <= MAX_LINKED_CAS_RETRIES; attempt++) {
      const existingRecord = await this.store.getRecordByToken(linkedToken);
      if (existingRecord && existingRecord.ownerAppUserId !== ownerAppUserId) {
        this.logger.warn(
          `Ownership conflict on linked token ${maskToken(linkedToken)}: bound to ${existingRecord.ownerAppUserId}, requested by ${ownerAppUserId}`
        );
        return {
          status: 'UNRESOLVED_PERMANENT',
          purchaseToken: linkedToken,
          productId: existingRecord.productId,
          reason: 'OWNERSHIP_CONFLICT',
          message: `Linked token already bound to ${existingRecord.ownerAppUserId}`
        };
      }

      const expectedVersion = existingRecord ? existingRecord.latestSnapshotVersion : null;
      const expectedAbsent = existingRecord === null || existingRecord === undefined;
      const targetQuerySku = existingRecord?.productId || '';

      let linkedPlayResult: GooglePlaySubscriptionResult;
      try {
        linkedPlayResult = await this.googlePlayApi.getSubscription(pkg, targetQuerySku, linkedToken);
      } catch (err: unknown) {
        if (err instanceof GooglePlayApiError) {
          if (err.isTransient || err.statusCode >= 500 || err.statusCode === 429) {
            this.logger.warn(`Transient Google Play API error on linked token (${err.statusCode}): ${err.message}`);
            return {
              status: 'UNRESOLVED_TRANSIENT',
              purchaseToken: linkedToken,
              productId: targetQuerySku || undefined,
              message: err.message
            };
          }
          if (err.statusCode === 404) {
            this.logger.warn(`Linked token not found on Google Play (404): ${maskToken(linkedToken)}`);
            return {
              status: 'UNRESOLVED_PERMANENT',
              purchaseToken: linkedToken,
              productId: targetQuerySku || undefined,
              reason: 'NOT_FOUND',
              message: err.message
            };
          }
          if (err.statusCode === 400) {
            this.logger.warn(`Invalid linked token on Google Play (400): ${maskToken(linkedToken)}: ${err.message}`);
            return {
              status: 'UNRESOLVED_PERMANENT',
              purchaseToken: linkedToken,
              productId: targetQuerySku || undefined,
              reason: 'INVALID_TOKEN',
              message: err.message
            };
          }
        }
        this.logger.error(`Error querying linked token ${maskToken(linkedToken)} on Google Play:`, err);
        return {
          status: 'UNRESOLVED_TRANSIENT',
          purchaseToken: linkedToken,
          productId: targetQuerySku || undefined,
          message: String(err)
        };
      }

      const resolvedProductId = existingRecord?.productId || linkedPlayResult.lineItemProductId || currentProductId;
      if (!ALLOWED_SUBSCRIPTION_IDS.has(resolvedProductId)) {
        this.logger.warn(`Disallowed product ID for linked token: ${resolvedProductId}`);
        return {
          status: 'UNRESOLVED_PERMANENT',
          purchaseToken: linkedToken,
          productId: resolvedProductId,
          reason: 'PRODUCT_NOT_ALLOWED',
          message: `Product ${resolvedProductId} not allowed in catalog`
        };
      }

      const expectedHash = this.computeObfuscatedAccountId(ownerAppUserId);
      if (linkedPlayResult.obfuscatedExternalAccountId && linkedPlayResult.obfuscatedExternalAccountId !== expectedHash) {
        this.logger.warn(`ObfuscatedAccountId mismatch on linked token ${maskToken(linkedToken)}`);
        return {
          status: 'UNRESOLVED_PERMANENT',
          purchaseToken: linkedToken,
          productId: resolvedProductId,
          reason: 'ACCOUNT_HASH_MISMATCH',
          message: 'Account hash mismatch on linked token'
        };
      }

      let linkedState: EntitlementState;
      if (linkedPlayResult.subscriptionState) {
        switch (linkedPlayResult.subscriptionState) {
          case 'SUBSCRIPTION_STATE_PENDING_PURCHASE_CANCELED':
            linkedState = 'REVOKED';
            break;
          case 'SUBSCRIPTION_STATE_PENDING':
            linkedState = 'PENDING_PAYMENT';
            break;
          case 'SUBSCRIPTION_STATE_ACTIVE':
            if (linkedPlayResult.expiryTimeMillis && Date.now() >= linkedPlayResult.expiryTimeMillis) {
              linkedState = 'EXPIRED';
            } else {
              linkedState = linkedPlayResult.autoRenewing ? 'VERIFIED_ACTIVE' : 'CANCELED_ACTIVE';
            }
            break;
          case 'SUBSCRIPTION_STATE_IN_GRACE_PERIOD':
            linkedState = 'IN_GRACE_PERIOD';
            break;
          case 'SUBSCRIPTION_STATE_CANCELED':
            if (linkedPlayResult.expiryTimeMillis && Date.now() >= linkedPlayResult.expiryTimeMillis) {
              linkedState = 'EXPIRED';
            } else {
              linkedState = 'CANCELED_ACTIVE';
            }
            break;
          case 'SUBSCRIPTION_STATE_ON_HOLD':
            linkedState = 'ON_HOLD';
            break;
          case 'SUBSCRIPTION_STATE_PAUSED':
            linkedState = 'PAUSED';
            break;
          case 'SUBSCRIPTION_STATE_EXPIRED':
            linkedState = 'EXPIRED';
            break;
          default:
            return {
              status: 'UNRESOLVED_PERMANENT',
              purchaseToken: linkedToken,
              productId: resolvedProductId,
              reason: 'INVALID_TOKEN',
              message: `Unknown subscription state ${linkedPlayResult.subscriptionState}`
            };
        }
      } else {
        if (linkedPlayResult.paymentState === 0) {
          linkedState = 'PENDING_PAYMENT';
        } else if (Date.now() >= linkedPlayResult.expiryTimeMillis) {
          linkedState = 'EXPIRED';
        } else if (!linkedPlayResult.autoRenewing) {
          linkedState = 'CANCELED_ACTIVE';
        } else {
          linkedState = 'VERIFIED_ACTIVE';
        }
      }

      const linkedEntitlement: BillingEntitlement = {
        id: `GOOGLE_PLAY_SUBSCRIPTION_${linkedToken}`,
        ownerAppUserId: existingRecord ? existingRecord.ownerAppUserId : ownerAppUserId,
        productId: resolvedProductId,
        productType: 'subs',
        purchaseToken: linkedToken,
        orderId: linkedPlayResult.orderId,
        source: 'GOOGLE_PLAY_SUBSCRIPTION',
        state: linkedState,
        purchaseTimeMillis: linkedPlayResult.startTimeMillis || Date.now(),
        expiryTimeMillis: linkedPlayResult.expiryTimeMillis,
        autoRenewing: linkedPlayResult.autoRenewing,
        verifiedAtMillis: Date.now(),
        snapshotVersion: existingRecord ? existingRecord.latestSnapshotVersion + 1 : 1
      };

      const bindResult = await this.store.bindOrUpdate(
        linkedEntitlement.ownerAppUserId,
        linkedToken,
        linkedEntitlement,
        existingRecord?.obfuscatedAccountId || expectedHash,
        {
          expectedVersion,
          expectedAbsent
        }
      );

      if (!bindResult.success) {
        if (bindResult.casConflict) {
          if (attempt < MAX_LINKED_CAS_RETRIES) {
            this.logger.warn(`CAS conflict on linked token ${maskToken(linkedToken)}, retrying attempt ${attempt + 1}/${MAX_LINKED_CAS_RETRIES}...`);
            continue;
          }
          this.logger.error(`CAS conflict retry budget exhausted for linked token ${maskToken(linkedToken)}`);
          return {
            status: 'UNRESOLVED_TRANSIENT',
            purchaseToken: linkedToken,
            productId: resolvedProductId,
            message: 'CAS conflict retry budget exhausted'
          };
        }
        if (bindResult.conflictOwner) {
          this.logger.warn(`Ownership conflict on linked token ${maskToken(linkedToken)}: bound to ${bindResult.conflictOwner}`);
          return {
            status: 'UNRESOLVED_PERMANENT',
            purchaseToken: linkedToken,
            productId: resolvedProductId,
            reason: 'OWNERSHIP_CONFLICT',
            message: `Linked token already bound to ${bindResult.conflictOwner}`
          };
        }
        return {
          status: 'UNRESOLVED_TRANSIENT',
          purchaseToken: linkedToken,
          productId: resolvedProductId,
          message: 'Failed to bind linked entitlement'
        };
      }

      // Successfully bound
      return {
        status: 'RESOLVED',
        purchaseToken: linkedToken,
        productId: resolvedProductId,
        entitlementState: linkedState,
        entitlement: bindResult.record?.entitlement || linkedEntitlement
      };
    }

    return {
      status: 'UNRESOLVED_TRANSIENT',
      purchaseToken: linkedToken,
      productId: currentProductId,
      message: 'Linked token CAS retry loop terminated'
    };
  }
}
