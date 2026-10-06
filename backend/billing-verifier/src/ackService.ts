import { GooglePlayApiError, type GooglePlayBillingApi } from './googlePlayClient.ts';
import { defaultLogger, type Logger } from './verifier.ts';
import type { EntitlementStore } from './store.ts';

export interface AcknowledgeRequest {
  purchaseToken: string;
  productId: string;
  productType: 'subs' | 'inapp';
  packageName?: string;
  ownerAppUserId?: string;
  retryDelayMs?: number;
}

export interface AcknowledgeResult {
  status: 'ACKNOWLEDGED' | 'ALREADY_ACKNOWLEDGED' | 'RETRY_QUEUED' | 'FAILED';
  message?: string;
}

/**
 * Service managing resilient acknowledge calls to Google Play.
 * Enforces the 'persist-before-ack' pattern with an in-memory or persisted retry queue.
 */
export class AcknowledgeService {
  private acknowledgedTokens = new Set<string>();
  private pendingAckQueue: Map<string, AcknowledgeRequest> = new Map();
  private maxRetries = 5;
  private googlePlayApi: GooglePlayBillingApi;
  private store?: EntitlementStore;
  private logger: Logger;

  constructor(
    googlePlayApi: GooglePlayBillingApi,
    storeOrLogger?: EntitlementStore | Logger,
    logger?: Logger
  ) {
    this.googlePlayApi = googlePlayApi;
    if (storeOrLogger && typeof (storeOrLogger as any).getRecordByToken === 'function') {
      this.store = storeOrLogger as EntitlementStore;
      this.logger = logger || defaultLogger;
    } else {
      this.store = undefined;
      this.logger = (storeOrLogger as Logger) || defaultLogger;
    }
  }

  /**
   * Returns true if the token is already acknowledged.
   */
  isAcknowledged(token: string): boolean {
    return this.acknowledgedTokens.has(token);
  }

  /**
   * Number of items currently awaiting acknowledge retry.
   */
  getPendingQueueSize(): number {
    return this.pendingAckQueue.size;
  }

  /**
   * Persists to pending queue and attempts acknowledge with Google Play.
   */
  async acknowledge(req: AcknowledgeRequest): Promise<AcknowledgeResult> {
    const pkg = req.packageName || 'com.tscanner.app';

    if (this.acknowledgedTokens.has(req.purchaseToken)) {
      return { status: 'ALREADY_ACKNOWLEDGED', message: 'Token already acknowledged.' };
    }

    // If backed by durable store, verify token exists in store before attempting ack
    if (this.store) {
      const record = await this.store.getRecordByToken(req.purchaseToken);
      if (!record) {
        return {
          status: 'FAILED',
          message: 'Purchase token must be verified and persisted in store before acknowledge'
        };
      }

      // Persist in durable outbox queue BEFORE calling external network API
      await this.store.enqueueAckRetry({
        purchaseToken: req.purchaseToken,
        productId: req.productId,
        productType: req.productType,
        ownerAppUserId: req.ownerAppUserId || record.ownerAppUserId
      });
    }

    // Also track in in-memory queue
    this.pendingAckQueue.set(req.purchaseToken, req);

    // 2. Execute call to Google Play Developer API
    try {
      if (req.productType === 'subs') {
        await this.googlePlayApi.acknowledgeSubscription(pkg, req.productId, req.purchaseToken);
      } else {
        await this.googlePlayApi.acknowledgeInAppProduct(pkg, req.productId, req.purchaseToken);
      }

      // 3. Mark acknowledged and remove from pending queue
      this.acknowledgedTokens.add(req.purchaseToken);
      this.pendingAckQueue.delete(req.purchaseToken);

      if (this.store) {
        await this.store.markAckSuccess(req.purchaseToken);
      }

      this.logger.info(`Successfully acknowledged purchase: token=${req.purchaseToken.slice(0, 6)}...`);
      return { status: 'ACKNOWLEDGED' };
    } catch (err: unknown) {
      const errStr = err instanceof Error ? err.message : String(err);

      // Handle duplicate/idempotent already-acknowledged responses from Play
      const isPlayAlreadyAck = /already acknowledged|already been acknowledged/i.test(errStr) ||
        (err instanceof GooglePlayApiError && err.statusCode === 400 && /already/i.test(errStr));

      if (isPlayAlreadyAck) {
        this.acknowledgedTokens.add(req.purchaseToken);
        this.pendingAckQueue.delete(req.purchaseToken);
        if (this.store) {
          await this.store.markAckSuccess(req.purchaseToken);
        }
        this.logger.info(`Token was already acknowledged on Google Play: ${req.purchaseToken.slice(0, 6)}...`);
        return {
          status: 'ALREADY_ACKNOWLEDGED',
          message: 'Purchase token already acknowledged on Google Play.'
        };
      }

      // Handle permanent failures (e.g. 404 Not Found or permanent 400 bad token)
      const isPermanent = err instanceof GooglePlayApiError && (err.statusCode === 404 || (!err.isTransient && err.statusCode === 400));
      if (isPermanent) {
        this.pendingAckQueue.delete(req.purchaseToken);
        if (this.store) {
          await this.store.markAckFailure(req.purchaseToken, errStr, -1);
        }
        this.logger.error(`Permanent acknowledge failure: ${errStr}`);
        return {
          status: 'FAILED',
          message: `Permanent acknowledge error: ${errStr}`
        };
      }

      // Transient failure -> remains queued for background retry
      if (this.store) {
        await this.store.markAckFailure(req.purchaseToken, errStr, req.retryDelayMs);
      }

      this.logger.warn(`Failed to acknowledge purchase; token queued for retry: ${errStr}`);
      return {
        status: 'RETRY_QUEUED',
        message: 'Acknowledge failed temporarily; queued for background retry.'
      };
    }
  }

  /**
   * Drains and retries all pending acknowledge tasks in the queue.
   */
  async drainPendingQueue(): Promise<number> {
    const pending = Array.from(this.pendingAckQueue.values());
    let successCount = 0;

    for (const req of pending) {
      const res = await this.acknowledge(req);
      if (res.status === 'ACKNOWLEDGED' || res.status === 'ALREADY_ACKNOWLEDGED') {
        successCount++;
      }
    }

    if (this.store) {
      const storedPending = await this.store.getPendingAckRetries(50);
      for (const item of storedPending) {
        const res = await this.acknowledge({
          purchaseToken: item.purchaseToken,
          productId: item.productId,
          productType: item.productType,
          ownerAppUserId: item.ownerAppUserId
        });
        if (res.status === 'ACKNOWLEDGED' || res.status === 'ALREADY_ACKNOWLEDGED') {
          successCount++;
        }
      }
    }

    return successCount;
  }

  clear(): void {
    this.acknowledgedTokens.clear();
    this.pendingAckQueue.clear();
  }
}
