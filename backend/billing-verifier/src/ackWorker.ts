import type { EntitlementStore } from './store.ts';
import type { AcknowledgeService } from './ackService.ts';
import { defaultLogger, type Logger } from './verifier.ts';

export interface AckWorkerOptions {
  pollIntervalMs?: number;
  maxAttempts?: number;
  initialBackoffMs?: number;
  maxBackoffMs?: number;
  batchSize?: number;
}

/**
 * Background outbox worker responsible for draining and retrying
 * unacknowledged Google Play purchases stored durably in SQLite.
 *
 * Provides startup crash recovery, bounded retry, exponential backoff,
 * and graceful shutdown.
 */
export class AckWorker {
  private store: EntitlementStore;
  private ackService: AcknowledgeService;
  private logger: Logger;
  private pollIntervalMs: number;
  private maxAttempts: number;
  private initialBackoffMs: number;
  private maxBackoffMs: number;
  private batchSize: number;

  private timer: NodeJS.Timeout | null = null;
  private active = false;
  private isProcessing = false;

  constructor(
    store: EntitlementStore,
    ackService: AcknowledgeService,
    logger: Logger = defaultLogger,
    options: AckWorkerOptions = {}
  ) {
    this.store = store;
    this.ackService = ackService;
    this.logger = logger;
    this.pollIntervalMs = options.pollIntervalMs ?? 5000;
    this.maxAttempts = options.maxAttempts ?? 5;
    this.initialBackoffMs = options.initialBackoffMs ?? 1000;
    this.maxBackoffMs = options.maxBackoffMs ?? 60000;
    this.batchSize = options.batchSize ?? 20;
  }

  /**
   * Starts the background outbox worker.
   * Performs an immediate recovery poll for jobs pending across process restarts.
   */
  start(): void {
    if (this.active) return;
    this.active = true;
    this.logger.info(`Starting AckWorker (pollInterval=${this.pollIntervalMs}ms, maxAttempts=${this.maxAttempts})`);

    // Immediate recovery run
    void this.processPendingBatch();

    // Periodic polling timer
    this.timer = setInterval(() => {
      void this.processPendingBatch();
    }, this.pollIntervalMs);

    // Unref timer so it doesn't block Node process exit in tests
    if (typeof this.timer?.unref === 'function') {
      this.timer.unref();
    }
  }

  /**
   * Stops the background worker and releases timers.
   */
  async stop(): Promise<void> {
    if (!this.active) return;
    this.active = false;
    if (this.timer) {
      clearInterval(this.timer);
      this.timer = null;
    }

    // Wait for in-flight batch if currently processing
    while (this.isProcessing) {
      await new Promise(resolve => setTimeout(resolve, 50));
    }
    this.logger.info('AckWorker stopped gracefully');
  }

  isRunning(): boolean {
    return this.active;
  }

  /**
   * Processes a single batch of pending ack retries from durable storage.
   * Returns the number of items successfully processed.
   */
  async processPendingBatch(): Promise<number> {
    if (this.isProcessing) return 0;
    this.isProcessing = true;

    let processedCount = 0;
    try {
      const items = await this.store.getPendingAckRetries(this.batchSize);
      if (!items || items.length === 0) {
        return 0;
      }

      this.logger.info(`AckWorker picked up ${items.length} pending ack item(s)`);

      for (const item of items) {
        // 1. Check bounded retry limit
        if (item.attemptCount >= this.maxAttempts) {
          const errMsg = `Exceeded max retry attempts (${this.maxAttempts})`;
          this.logger.error(`Ack item permanently failed for token ${item.purchaseToken.slice(0, 6)}: ${errMsg}`);
          await this.store.markAckFailure(item.purchaseToken, errMsg, -1);
          continue;
        }

        // 2. Compute backoff delay for this attempt if it fails
        const attempt = item.attemptCount + 1;
        const delay = Math.min(this.initialBackoffMs * Math.pow(2, attempt - 1), this.maxBackoffMs);

        // 3. Call AcknowledgeService with exponential backoff delay
        const result = await this.ackService.acknowledge({
          purchaseToken: item.purchaseToken,
          productId: item.productId,
          productType: item.productType,
          ownerAppUserId: item.ownerAppUserId,
          retryDelayMs: delay
        });

        if (result.status === 'ACKNOWLEDGED' || result.status === 'ALREADY_ACKNOWLEDGED') {
          processedCount++;
        }
      }
    } catch (err: unknown) {
      this.logger.error('Error in AckWorker batch execution:', err);
    } finally {
      this.isProcessing = false;
    }

    return processedCount;
  }
}
