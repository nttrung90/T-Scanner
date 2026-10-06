import test from 'node:test';
import assert from 'node:assert/strict';
import { EntitlementStore } from '../src/store.ts';
import { MockGooglePlayBillingApi, GooglePlayApiError } from '../src/googlePlayClient.ts';
import { RtdnHandler } from '../src/rtdnHandler.ts';
import { AuthService } from '../src/auth.ts';
import { createHttpServer } from '../src/index.ts';
import { BillingVerifierService } from '../src/verifier.ts';
import { AcknowledgeService } from '../src/ackService.ts';
import type { BillingEntitlement } from '../src/types.ts';

const logger = { info: () => {}, warn: () => {}, error: () => {} };

function createSampleEntitlement(overrides: Partial<BillingEntitlement> = {}): BillingEntitlement {
  return {
    id: 'receipt-1',
    ownerAppUserId: 'user-rtdn-1',
    productId: 'tscanner_vip_yearly',
    productType: 'subs',
    purchaseToken: 'token-rtdn-test',
    source: 'GOOGLE_PLAY_SUBSCRIPTION',
    state: 'VERIFIED_ACTIVE',
    purchaseTimeMillis: Date.now() - 10000,
    expiryTimeMillis: Date.now() + 86400000,
    autoRenewing: true,
    verifiedAtMillis: Date.now(),
    snapshotVersion: 1,
    ...overrides
  };
}

test('RTDN retry: transient Google Play API failure leaves event retryable', async () => {
  const store = new EntitlementStore();
  const api = new MockGooglePlayBillingApi();
  await store.bindOrUpdate('user-rtdn-1', 'token-rtdn-test', createSampleEntitlement());

  const handler = new RtdnHandler(api, store, logger);
  const now = Date.now();
  const payload = {
    version: '1',
    packageName: 'com.tscanner.app',
    eventTimeMillis: now,
    subscriptionNotification: {
      version: '1',
      notificationType: 12, // REVOKED
      purchaseToken: 'token-rtdn-test',
      subscriptionId: 'tscanner_vip_yearly'
    }
  };

  // Step 1: Simulate Google API transient failure (503)
  api.registerSubscription('token-rtdn-test', async () => {
    throw new GooglePlayApiError('Play backend transient 503', 503, true);
  });

  const res1 = await handler.processDeveloperNotification(payload);
  assert.equal(res1.status, 'ERROR', 'First attempt should fail with ERROR status');

  // Verify entitlement state is untouched
  const recordBefore = await store.getRecordByToken('token-rtdn-test');
  assert.equal(recordBefore?.entitlement.state, 'VERIFIED_ACTIVE');

  // Step 2: Google API recovers
  api.registerSubscription('token-rtdn-test', async () => ({
    acknowledgementState: 1,
    expiryTimeMillis: Date.now() - 1000,
    paymentState: 1,
    autoRenewing: false
  }));

  const res2 = await handler.processDeveloperNotification(payload);
  assert.equal(res2.status, 'PROCESSED', 'Retrying the exact same event must succeed');

  // Verify store was updated to REVOKED
  const recordAfter = await store.getRecordByToken('token-rtdn-test');
  assert.equal(recordAfter?.entitlement.state, 'REVOKED');
  assert.equal(recordAfter?.entitlement.snapshotVersion, 2);

  // Step 3: Repeated delivery of the same event should be skipped
  const res3 = await handler.processDeveloperNotification(payload);
  assert.equal(res3.status, 'SKIPPED_STALE', 'Immediate duplicate delivery must be skipped as stale/duplicate');
});

test('RTDN deduplication: stale older event arriving after newer event is rejected', async () => {
  const store = new EntitlementStore();
  const api = new MockGooglePlayBillingApi();
  await store.bindOrUpdate('user-rtdn-1', 'token-rtdn-test', createSampleEntitlement());
  const handler = new RtdnHandler(api, store, logger);

  api.registerSubscription('token-rtdn-test', async () => ({
    acknowledgementState: 1,
    expiryTimeMillis: Date.now() + 100000,
    paymentState: 1,
    autoRenewing: true
  }));

  const t2 = Date.now();
  const t1 = t2 - 5000;

  // Event at t2 arrives first
  const resNewer = await handler.processDeveloperNotification({
    version: '1',
    packageName: 'com.tscanner.app',
    eventTimeMillis: t2,
    subscriptionNotification: {
      version: '1',
      notificationType: 2, // RENEWED
      purchaseToken: 'token-rtdn-test',
      subscriptionId: 'tscanner_vip_yearly'
    }
  });
  assert.equal(resNewer.status, 'PROCESSED');

  // Event at t1 arrives later (out-of-order)
  const resOlder = await handler.processDeveloperNotification({
    version: '1',
    packageName: 'com.tscanner.app',
    eventTimeMillis: t1,
    subscriptionNotification: {
      version: '1',
      notificationType: 1, // RECOVERED
      purchaseToken: 'token-rtdn-test',
      subscriptionId: 'tscanner_vip_yearly'
    }
  });
  assert.equal(resOlder.status, 'SKIPPED_STALE', 'Older event arriving out of order must be skipped');
});

test('RTDN one-time product notification: revocation transitions state to REVOKED', async () => {
  const store = new EntitlementStore();
  const api = new MockGooglePlayBillingApi();
  await store.bindOrUpdate('user-rtdn-1', 'token-inapp-1', createSampleEntitlement({
    productId: 'tscanner_vip_lifetime',
    productType: 'inapp',
    purchaseToken: 'token-inapp-1',
    source: 'GOOGLE_PLAY_ONE_TIME',
    autoRenewing: false,
    expiryTimeMillis: null
  }));
  const handler = new RtdnHandler(api, store, logger);

  api.registerInApp('token-inapp-1', async () => ({
    acknowledgementState: 1,
    purchaseState: 0,
    consumptionState: 0,
    purchaseTimeMillis: Date.now()
  }));

  const res = await handler.processDeveloperNotification({
    version: '1',
    packageName: 'com.tscanner.app',
    eventTimeMillis: Date.now(),
    oneTimeProductNotification: {
      version: '1',
      notificationType: 2, // ONE_TIME_PRODUCT_CANCELED
      purchaseToken: 'token-inapp-1',
      sku: 'tscanner_vip_lifetime'
    }
  });

  assert.equal(res.status, 'PROCESSED');
  const record = await store.getRecordByToken('token-inapp-1');
  assert.equal(record?.entitlement.state, 'REVOKED');
  assert.equal(record?.entitlement.snapshotVersion, 2);
});

test('RTDN unknown token skips gracefully and processes once bound', async () => {
  const store = new EntitlementStore();
  const api = new MockGooglePlayBillingApi();
  const handler = new RtdnHandler(api, store, logger);

  const payload = {
    version: '1',
    packageName: 'com.tscanner.app',
    eventTimeMillis: Date.now(),
    subscriptionNotification: {
      version: '1',
      notificationType: 3,
      purchaseToken: 'unbound-token-999',
      subscriptionId: 'tscanner_vip_yearly'
    }
  };

  const resUnbound = await handler.processDeveloperNotification(payload);
  assert.equal(resUnbound.status, 'TOKEN_UNKNOWN');

  // Now client restores/binds the token
  await store.bindOrUpdate('user-late', 'unbound-token-999', createSampleEntitlement({
    ownerAppUserId: 'user-late',
    purchaseToken: 'unbound-token-999'
  }));

  api.registerSubscription('unbound-token-999', async () => ({
    acknowledgementState: 1,
    expiryTimeMillis: Date.now() + 500000,
    paymentState: 1,
    autoRenewing: true
  }));

  // Next RTDN event for this token succeeds
  const resBound = await handler.processDeveloperNotification({
    ...payload,
    eventTimeMillis: payload.eventTimeMillis + 1000
  });
  assert.equal(resBound.status, 'PROCESSED');
});

test('RTDN HTTP endpoint: returns 503 on ERROR and 200 on PROCESSED/SKIPPED', async () => {
  const store = new EntitlementStore();
  const api = new MockGooglePlayBillingApi();
  const verifierService = new BillingVerifierService(api, store, logger);
  const ackService = new AcknowledgeService(api, logger);
  const rtdnHandler = new RtdnHandler(api, store, logger);
  const authService = new AuthService({ pubsubSecretToken: 'test-pubsub-secret' });

  const server = createHttpServer({
    store,
    googleApi: api,
    verifierService,
    ackService,
    rtdnHandler,
    authService,
    logger
  });

  await store.bindOrUpdate('user-http-rtdn', 'token-http-rtdn', createSampleEntitlement({
    ownerAppUserId: 'user-http-rtdn',
    purchaseToken: 'token-http-rtdn'
  }));

  await new Promise<void>(resolve => server.listen(0, '127.0.0.1', resolve));
  try {
    const address = server.address();
    if (!address || typeof address === 'string') throw new Error('No local address');
    const url = `http://127.0.0.1:${address.port}/api/v1/billing/rtdn`;

    const rtdnData = {
      version: '1',
      packageName: 'com.tscanner.app',
      eventTimeMillis: Date.now(),
      subscriptionNotification: {
        version: '1',
        notificationType: 12,
        purchaseToken: 'token-http-rtdn',
        subscriptionId: 'tscanner_vip_yearly'
      }
    };
    const pubSubBody = {
      message: {
        data: Buffer.from(JSON.stringify(rtdnData)).toString('base64'),
        messageId: 'msg-test-123'
      }
    };

    // Case 1: Google API transient error -> HTTP 503
    api.registerSubscription('token-http-rtdn', async () => {
      throw new GooglePlayApiError('Play unavailable', 503, true);
    });

    const res503 = await fetch(url, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'x-pubsub-secret': 'test-pubsub-secret'
      },
      body: JSON.stringify(pubSubBody)
    });
    assert.equal(res503.status, 503, 'Should return HTTP 503 on internal processing error to prompt Pub/Sub retry');
    const data503 = await res503.json() as any;
    assert.equal(data503.status, 'ERROR');

    // Case 2: Google API succeeds -> HTTP 200
    api.registerSubscription('token-http-rtdn', async () => ({
      acknowledgementState: 1,
      expiryTimeMillis: Date.now() - 5000,
      paymentState: 1,
      autoRenewing: false
    }));

    const res200 = await fetch(url, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'x-pubsub-secret': 'test-pubsub-secret'
      },
      body: JSON.stringify(pubSubBody)
    });
    assert.equal(res200.status, 200, 'Should return HTTP 200 on success');
    const data200 = await res200.json() as any;
    assert.equal(data200.status, 'PROCESSED');
  } finally {
    await new Promise<void>((resolve, reject) => server.close(e => e ? reject(e) : resolve()));
  }
});

test('M04: Durable event watermark survives store restart and rejects older events', async () => {
  const tmpDir = `db_rtdn_test_${Date.now()}_${Math.random().toString(36).slice(2)}.db`;
  const store1 = new EntitlementStore(tmpDir);
  const api = new MockGooglePlayBillingApi();
  const token = 'token-restart-rtdn';

  await store1.bindOrUpdate('user-rtdn-durable', token, createSampleEntitlement({ purchaseToken: token }));
  const handler1 = new RtdnHandler(api, store1, logger);

  api.registerSubscription(token, async () => ({
    acknowledgementState: 1,
    expiryTimeMillis: Date.now() + 86400000,
    paymentState: 1,
    autoRenewing: true
  }));

  // Event at t = 50000
  const res1 = await handler1.processDeveloperNotification({
    version: '1',
    packageName: 'com.tscanner.app',
    eventTimeMillis: 50000,
    subscriptionNotification: {
      version: '1',
      notificationType: 2,
      purchaseToken: token,
      subscriptionId: 'tscanner_vip_yearly'
    }
  });
  assert.equal(res1.status, 'PROCESSED');
  store1.close();

  // Reopen store from same DB file to simulate restart
  const store2 = new EntitlementStore(tmpDir);
  const handler2 = new RtdnHandler(api, store2, logger);

  // Stale event at t = 40000 delivered after restart
  const resStale = await handler2.processDeveloperNotification({
    version: '1',
    packageName: 'com.tscanner.app',
    eventTimeMillis: 40000,
    subscriptionNotification: {
      version: '1',
      notificationType: 12, // REVOKED
      purchaseToken: token,
      subscriptionId: 'tscanner_vip_yearly'
    }
  });
  assert.equal(resStale.status, 'SKIPPED_STALE');

  // Verify entitlement state is still ACTIVE and was not revoked by stale event
  const rec = await store2.getRecordByToken(token);
  assert.equal(rec?.entitlement.state, 'VERIFIED_ACTIVE');

  store2.close();
  try {
    const fs = await import('node:fs');
    if (fs.existsSync(tmpDir)) fs.unlinkSync(tmpDir);
  } catch {}
});
