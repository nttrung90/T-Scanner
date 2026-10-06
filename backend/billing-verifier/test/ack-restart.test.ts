import test from 'node:test';
import assert from 'node:assert/strict';
import { rmSync } from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { EntitlementStore } from '../src/store.ts';
import { MockGooglePlayBillingApi, GooglePlayApiError } from '../src/googlePlayClient.ts';
import { BillingVerifierService } from '../src/verifier.ts';
import { AcknowledgeService } from '../src/ackService.ts';
import { AckWorker } from '../src/ackWorker.ts';
import { AuthService } from '../src/auth.ts';
import { createHttpServer } from '../src/index.ts';

const logger = { info: () => {}, warn: () => {}, error: () => {} };

function getUniqueDbPath(): string {
  return join(tmpdir(), `tscanner_ack_test_${Date.now()}_${Math.random().toString(36).substring(2)}.db`);
}

test('Q09: Crash after grant before ack - restart preserves job and worker recovers', async () => {
  const dbPath = getUniqueDbPath();

  try {
    const api = new MockGooglePlayBillingApi();
    const token = 'token-grant-crash-test';

    // Step 1: Register unacknowledged active subscription in Google Play
    api.registerSubscription(token, async () => ({
      acknowledgementState: 0, // Not yet acknowledged!
      expiryTimeMillis: Date.now() + 86400000,
      paymentState: 1,
      autoRenewing: true,
      orderId: 'GPA.1234-5678-0001'
    }));

    // Step 2: Client verifies purchase and entitlement is granted
    let store: EntitlementStore | null = new EntitlementStore(dbPath);
    let verifier: BillingVerifierService | null = new BillingVerifierService(api, store, logger);

    const verifyResult = await verifier.verifyPurchase({
      ownerAppUserId: 'user-crash-1',
      productId: 'tscanner_vip_yearly',
      productType: 'subs',
      purchaseToken: token,
      clientPurchaseTimeMillis: Date.now()
    });

    assert.equal(verifyResult.status, 'SUCCESS', 'Verification must succeed');
    assert.equal(verifyResult.entitlement?.state, 'VERIFIED_ACTIVE');

    // Confirm ack job was enqueued into durable store
    const initialPending = await store.getPendingAckRetries(10);
    assert.equal(initialPending.length, 1, 'Ack job must be enqueued in outbox');
    assert.equal(initialPending[0].purchaseToken, token);
    assert.equal(initialPending[0].status, 'PENDING');

    // Step 3: Simulate sudden process crash before acknowledge is completed
    store.close();
    store = null;
    verifier = null;

    // Step 4: Process restarts -> New store instance connects to same SQLite database
    const restartedStore = new EntitlementStore(dbPath);
    const pendingOnRestart = await restartedStore.getPendingAckRetries(10);
    assert.equal(pendingOnRestart.length, 1, 'Pending ack job must survive process restart');
    assert.equal(pendingOnRestart[0].purchaseToken, token);

    // Step 5: Background worker starts and processes the pending outbox job
    const restartedAckService = new AcknowledgeService(api, restartedStore, logger);
    const worker = new AckWorker(restartedStore, restartedAckService, logger, {
      pollIntervalMs: 50,
      maxAttempts: 3
    });

    const processedCount = await worker.processPendingBatch();
    assert.equal(processedCount, 1, 'Worker should process the pending job from outbox');
    assert.equal(api.acknowledgedTokens.has(token), true, 'Token must be acknowledged on Google Play');

    // Verify job is now COMPLETED and not returned in pending queue
    const remainingPending = await restartedStore.getPendingAckRetries(10);
    assert.equal(remainingPending.length, 0, 'Completed job must no longer be pending');

    restartedStore.close();
  } finally {
    try { rmSync(dbPath, { force: true }); } catch {}
  }
});

test('Q09: Acknowledge idempotence - Play returns already acknowledged', async () => {
  const dbPath = getUniqueDbPath();

  try {
    const store = new EntitlementStore(dbPath);
    const api = new MockGooglePlayBillingApi();
    const token = 'token-already-ack-test';

    // Seed token ownership
    await store.bindOrUpdate('user-ack-2', token, {
      id: 'receipt-2',
      ownerAppUserId: 'user-ack-2',
      productId: 'tscanner_vip_yearly',
      productType: 'subs',
      purchaseToken: token,
      source: 'GOOGLE_PLAY_SUBSCRIPTION',
      state: 'VERIFIED_ACTIVE',
      purchaseTimeMillis: Date.now(),
      expiryTimeMillis: Date.now() + 86400000,
      autoRenewing: true,
      verifiedAtMillis: Date.now(),
      snapshotVersion: 1
    });

    // Simulate Google Play responding with "already acknowledged" error (HTTP 400)
    api.ackFailureHandler = async () => {
      throw new GooglePlayApiError('The purchase token has already been acknowledged.', 400, false);
    };

    const ackService = new AcknowledgeService(api, store, logger);
    const result = await ackService.acknowledge({
      purchaseToken: token,
      productId: 'tscanner_vip_yearly',
      productType: 'subs',
      ownerAppUserId: 'user-ack-2'
    });

    assert.equal(result.status, 'ALREADY_ACKNOWLEDGED', 'Should recognize already acknowledged idempotently');

    // Verify job is marked as success/completed in store
    const pending = await store.getPendingAckRetries(10);
    assert.equal(pending.length, 0, 'Already acknowledged job must be completed, not retried');

    store.close();
  } finally {
    try { rmSync(dbPath, { force: true }); } catch {}
  }
});

test('Q09: Permanent acknowledge error transitions job to FAILED without endless retry', async () => {
  const dbPath = getUniqueDbPath();

  try {
    const store = new EntitlementStore(dbPath);
    const api = new MockGooglePlayBillingApi();
    const token = 'token-permanent-error-test';

    // Seed token ownership
    await store.bindOrUpdate('user-ack-3', token, {
      id: 'receipt-3',
      ownerAppUserId: 'user-ack-3',
      productId: 'tscanner_vip_yearly',
      productType: 'subs',
      purchaseToken: token,
      source: 'GOOGLE_PLAY_SUBSCRIPTION',
      state: 'VERIFIED_ACTIVE',
      purchaseTimeMillis: Date.now(),
      expiryTimeMillis: Date.now() + 86400000,
      autoRenewing: true,
      verifiedAtMillis: Date.now(),
      snapshotVersion: 1
    });

    // Simulate Google Play returning permanent 404 (token unrecognized or revoked)
    api.ackFailureHandler = async () => {
      throw new GooglePlayApiError('Purchase token not found or invalid.', 404, false);
    };

    const ackService = new AcknowledgeService(api, store, logger);
    const result = await ackService.acknowledge({
      purchaseToken: token,
      productId: 'tscanner_vip_yearly',
      productType: 'subs',
      ownerAppUserId: 'user-ack-3'
    });

    assert.equal(result.status, 'FAILED', 'Permanent error must return FAILED');

    // Confirm job is marked FAILED and will NOT be retried by worker
    const pending = await store.getPendingAckRetries(10);
    assert.equal(pending.length, 0, 'Permanent failed job must not remain in pending queue');

    store.close();
  } finally {
    try { rmSync(dbPath, { force: true }); } catch {}
  }
});

test('Q09: Worker bounded retry stops retrying after maxAttempts is reached', async () => {
  const dbPath = getUniqueDbPath();

  try {
    const store = new EntitlementStore(dbPath);
    const api = new MockGooglePlayBillingApi();
    const token = 'token-bounded-retry-test';

    // Seed token ownership
    await store.bindOrUpdate('user-ack-4', token, {
      id: 'receipt-4',
      ownerAppUserId: 'user-ack-4',
      productId: 'tscanner_vip_yearly',
      productType: 'subs',
      purchaseToken: token,
      source: 'GOOGLE_PLAY_SUBSCRIPTION',
      state: 'VERIFIED_ACTIVE',
      purchaseTimeMillis: Date.now(),
      expiryTimeMillis: Date.now() + 86400000,
      autoRenewing: true,
      verifiedAtMillis: Date.now(),
      snapshotVersion: 1
    });

    await store.enqueueAckRetry({
      purchaseToken: token,
      productId: 'tscanner_vip_yearly',
      productType: 'subs',
      ownerAppUserId: 'user-ack-4'
    });

    // Always fail with transient 503
    api.ackFailureHandler = async () => {
      throw new GooglePlayApiError('Play 503 transient error', 503, true);
    };

    const ackService = new AcknowledgeService(api, store, logger);
    const worker = new AckWorker(store, ackService, logger, {
      maxAttempts: 2,
      initialBackoffMs: 0 // zero delay for immediate testing
    });

    // Attempt 1: fails, attempt_count becomes 1
    await worker.processPendingBatch();
    let pending = await store.getPendingAckRetries(10);
    assert.equal(pending.length, 1);
    assert.equal(pending[0].attemptCount, 1);

    // Attempt 2: fails, attempt_count becomes 2 (reaches maxAttempts)
    await worker.processPendingBatch();

    // Attempt 3: worker detects item.attemptCount >= maxAttempts -> marks FAILED
    await worker.processPendingBatch();

    pending = await store.getPendingAckRetries(10);
    assert.equal(pending.length, 0, 'After exceeding maxAttempts, job must be marked FAILED and cleared from pending');

    store.close();
  } finally {
    try { rmSync(dbPath, { force: true }); } catch {}
  }
});

test('Q09: Do not acknowledge token that is not verified in store', async () => {
  const store = new EntitlementStore();
  const api = new MockGooglePlayBillingApi();
  const ackService = new AcknowledgeService(api, store, logger);

  // Attempting to acknowledge unverified token directly
  const result = await ackService.acknowledge({
    purchaseToken: 'unverified-unknown-token',
    productId: 'tscanner_vip_yearly',
    productType: 'subs'
  });

  assert.equal(result.status, 'FAILED', 'Must reject acknowledge for unverified token');
  assert.equal(api.acknowledgedTokens.has('unverified-unknown-token'), false, 'Network call must not occur');
});

test('Q09: Worker autonomously runs in background without manual drain', async () => {
  const dbPath = getUniqueDbPath();

  try {
    const store = new EntitlementStore(dbPath);
    const api = new MockGooglePlayBillingApi();
    const token = 'token-bg-worker-test';

    // Seed token ownership
    await store.bindOrUpdate('user-bg-1', token, {
      id: 'receipt-bg',
      ownerAppUserId: 'user-bg-1',
      productId: 'tscanner_vip_yearly',
      productType: 'subs',
      purchaseToken: token,
      source: 'GOOGLE_PLAY_SUBSCRIPTION',
      state: 'VERIFIED_ACTIVE',
      purchaseTimeMillis: Date.now(),
      expiryTimeMillis: Date.now() + 86400000,
      autoRenewing: true,
      verifiedAtMillis: Date.now(),
      snapshotVersion: 1
    });

    await store.enqueueAckRetry({
      purchaseToken: token,
      productId: 'tscanner_vip_yearly',
      productType: 'subs',
      ownerAppUserId: 'user-bg-1'
    });

    const ackService = new AcknowledgeService(api, store, logger);
    const worker = new AckWorker(store, ackService, logger, {
      pollIntervalMs: 50,
      maxAttempts: 3
    });

    // Start background worker
    worker.start();
    assert.equal(worker.isRunning(), true);

    // Wait for worker to run autonomously on background timer
    const deadline = Date.now() + 2000;
    while (!api.acknowledgedTokens.has(token) && Date.now() < deadline) {
      await new Promise(resolve => setTimeout(resolve, 50));
    }

    assert.equal(api.acknowledgedTokens.has(token), true, 'Worker must autonomously acknowledge token via background timer');

    // Gracefully stop worker
    await worker.stop();
    assert.equal(worker.isRunning(), false);

    store.close();
  } finally {
    try { rmSync(dbPath, { force: true }); } catch {}
  }
});

test('M05: Atomic grant + outbox for CANCELED_ACTIVE unacknowledged receipt and crash recovery', async () => {
  const dbPath = getUniqueDbPath();

  try {
    const api = new MockGooglePlayBillingApi();
    const token = 'token-atomic-canceled-active';

    api.registerSubscription(token, async () => ({
      acknowledgementState: 0,
      expiryTimeMillis: Date.now() + 86400000,
      paymentState: 1,
      autoRenewing: false
    }));

    let store: EntitlementStore | null = new EntitlementStore(dbPath);
    let verifier: BillingVerifierService | null = new BillingVerifierService(api, store, logger);

    const result = await verifier.verifyPurchase({
      ownerAppUserId: 'usr_canceled_atomic',
      productId: 'tscanner_vip_yearly',
      productType: 'subs',
      purchaseToken: token,
      clientPurchaseTimeMillis: Date.now()
    });

    assert.equal(result.status, 'SUCCESS');
    assert.equal(result.entitlement?.state, 'CANCELED_ACTIVE');

    // Verify outbox was populated atomically in same transaction
    const pending = await store.getPendingAckRetries();
    assert.equal(pending.length, 1);
    assert.equal(pending[0].purchaseToken, token);

    // Simulate crash and restart
    store.close();
    store = null;
    verifier = null;

    const restartedStore = new EntitlementStore(dbPath);
    const pendingAfterRestart = await restartedStore.getPendingAckRetries();
    assert.equal(pendingAfterRestart.length, 1);

    const ackService = new AcknowledgeService(api, restartedStore, logger);
    const worker = new AckWorker(restartedStore, ackService, logger);
    const count = await worker.processPendingBatch();
    assert.equal(count, 1);
    assert.equal(api.acknowledgedTokens.has(token), true);

    restartedStore.close();
  } finally {
    try { rmSync(dbPath, { force: true }); } catch {}
  }
});

test('M05: HTTP Server /readiness endpoint checks durability and auth readiness', async () => {
  const dbPath = getUniqueDbPath();

  try {
    const store = new EntitlementStore(dbPath);
    const authService = new AuthService({
      googleClientId: 'tscanner-ready-client',
      pubsubSecretToken: 'ready-pubsub-token'
    });
    const server = createHttpServer({ store, authService });

    await new Promise<void>(resolve => server.listen(0, '127.0.0.1', resolve));
    const address = server.address() as any;
    const baseUrl = `http://127.0.0.1:${address.port}`;

    try {
      const res = await fetch(`${baseUrl}/readiness`);
      assert.equal(res.status, 200);
      const data = await res.json() as any;
      assert.equal(data.status, 'READY');
      assert.equal(data.checks.storage, 'OK');
      assert.equal(data.checks.userAuth, 'OK');
      assert.equal(data.checks.pushAuth, 'OK');
    } finally {
      await new Promise<void>((resolve, reject) => server.close(err => err ? reject(err) : resolve()));
      store.close();
    }
  } finally {
    try { rmSync(dbPath, { force: true }); } catch {}
  }
});
