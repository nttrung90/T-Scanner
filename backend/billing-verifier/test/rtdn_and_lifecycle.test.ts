import test from 'node:test';
import assert from 'node:assert';
import { EntitlementStore } from '../src/store.ts';
import { MockGooglePlayBillingApi } from '../src/googlePlayClient.ts';
import { RtdnHandler, RTDN_SUBSCRIPTION_TYPES, type PubSubPushBody } from '../src/rtdnHandler.ts';
import { AcknowledgeService } from '../src/ackService.ts';
import { BillingVerifierService } from '../src/verifier.ts';
import type { BillingEntitlement } from '../src/types.ts';

test('B04b RTDN: Renewal updates expiration without accumulating relative days', async () => {
  const mockApi = new MockGooglePlayBillingApi();
  const store = new EntitlementStore();
  const rtdn = new RtdnHandler(mockApi, store);
  const verifier = new BillingVerifierService(mockApi, store);

  const token = 'tok_sub_renewal_1';
  const initialExpiry = Date.now() + 30 * 24 * 3600 * 1000;
  const renewalExpiry = Date.now() + 60 * 24 * 3600 * 1000; // Exact Play timestamp

  // Initial purchase verification
  mockApi.registerSubscription(token, async () => ({
    acknowledgementState: 1,
    paymentState: 1,
    autoRenewing: true,
    expiryTimeMillis: initialExpiry
  }));

  await verifier.verifyPurchase({
    ownerAppUserId: 'usr_renew_1',
    productId: 'tscanner_vip_monthly',
    productType: 'subs',
    purchaseToken: token,
    clientPurchaseTimeMillis: Date.now()
  });

  // Now Play renews to renewalExpiry
  mockApi.registerSubscription(token, async () => ({
    acknowledgementState: 1,
    paymentState: 1,
    autoRenewing: true,
    expiryTimeMillis: renewalExpiry
  }));

  // RTDN pushes RENEWED notification
  const rtdnPayload = {
    version: '1.0',
    packageName: 'com.tscanner.app',
    eventTimeMillis: Date.now() + 1000,
    subscriptionNotification: {
      version: '1.0',
      notificationType: RTDN_SUBSCRIPTION_TYPES.RENEWED,
      purchaseToken: token,
      subscriptionId: 'tscanner_vip_monthly'
    }
  };

  const pushBody: PubSubPushBody = {
    message: {
      data: Buffer.from(JSON.stringify(rtdnPayload)).toString('base64'),
      messageId: 'msg_renew_1',
      publishTime: new Date().toISOString()
    }
  };

  const res = await rtdn.handlePushNotification(pushBody);
  assert.strictEqual(res.status, 'PROCESSED');
  assert.strictEqual(res.newState, 'VERIFIED_ACTIVE');

  const updatedRecord = await store.getRecordByToken(token);
  assert.ok(updatedRecord);
  assert.strictEqual(updatedRecord.entitlement.expiryTimeMillis, renewalExpiry);
  assert.strictEqual(updatedRecord.entitlement.state, 'VERIFIED_ACTIVE');
});

test('B04b RTDN: Cancellation before term expiry transitions to CANCELED_ACTIVE', async () => {
  const mockApi = new MockGooglePlayBillingApi();
  const store = new EntitlementStore();
  const rtdn = new RtdnHandler(mockApi, store);
  const verifier = new BillingVerifierService(mockApi, store);

  const token = 'tok_sub_cancel_1';
  const termExpiry = Date.now() + 20 * 24 * 3600 * 1000;

  mockApi.registerSubscription(token, async () => ({
    acknowledgementState: 1,
    paymentState: 1,
    autoRenewing: true,
    expiryTimeMillis: termExpiry
  }));

  await verifier.verifyPurchase({
    ownerAppUserId: 'usr_cancel_1',
    productId: 'tscanner_vip_monthly',
    productType: 'subs',
    purchaseToken: token,
    clientPurchaseTimeMillis: Date.now()
  });

  // User canceled auto-renew on Play Store
  mockApi.registerSubscription(token, async () => ({
    acknowledgementState: 1,
    paymentState: 1,
    autoRenewing: false,
    expiryTimeMillis: termExpiry
  }));

  const rtdnPayload = {
    version: '1.0',
    packageName: 'com.tscanner.app',
    eventTimeMillis: Date.now() + 2000,
    subscriptionNotification: {
      version: '1.0',
      notificationType: RTDN_SUBSCRIPTION_TYPES.CANCELED,
      purchaseToken: token,
      subscriptionId: 'tscanner_vip_monthly'
    }
  };

  const pushBody: PubSubPushBody = {
    message: {
      data: Buffer.from(JSON.stringify(rtdnPayload)).toString('base64'),
      messageId: 'msg_cancel_1',
      publishTime: new Date().toISOString()
    }
  };

  const res = await rtdn.handlePushNotification(pushBody);
  assert.strictEqual(res.status, 'PROCESSED');
  assert.strictEqual(res.newState, 'CANCELED_ACTIVE');

  const updatedRecord = await store.getRecordByToken(token);
  assert.ok(updatedRecord);
  assert.strictEqual(updatedRecord.entitlement.state, 'CANCELED_ACTIVE');
  assert.strictEqual(updatedRecord.entitlement.autoRenewing, false);
  assert.strictEqual(updatedRecord.entitlement.expiryTimeMillis, termExpiry);
});

test('B04b RTDN: Out-of-order or duplicate notification is safely skipped', async () => {
  const mockApi = new MockGooglePlayBillingApi();
  const store = new EntitlementStore();
  const rtdn = new RtdnHandler(mockApi, store);

  const token = 'tok_stale_1';
  const initialEntitlement: BillingEntitlement = {
    id: `GOOGLE_PLAY_SUBS_${token}`,
    ownerAppUserId: 'usr_stale',
    productId: 'tscanner_vip_monthly',
    productType: 'subs',
    purchaseToken: token,
    source: 'GOOGLE_PLAY_SUBSCRIPTION',
    state: 'VERIFIED_ACTIVE',
    purchaseTimeMillis: Date.now(),
    expiryTimeMillis: Date.now() + 1000000,
    autoRenewing: true,
    verifiedAtMillis: Date.now(),
    snapshotVersion: 2
  };
  await store.bindOrUpdate('usr_stale', token, initialEntitlement);

  mockApi.registerSubscription(token, async () => ({
    acknowledgementState: 1,
    paymentState: 1,
    autoRenewing: true,
    expiryTimeMillis: Date.now() + 1000000
  }));

  // First event at T = 5000
  await rtdn.processDeveloperNotification({
    version: '1.0',
    packageName: 'com.tscanner.app',
    eventTimeMillis: 5000,
    subscriptionNotification: {
      version: '1.0',
      notificationType: RTDN_SUBSCRIPTION_TYPES.RENEWED,
      purchaseToken: token,
      subscriptionId: 'tscanner_vip_monthly'
    }
  });

  // Second event arrived out-of-order with older timestamp T = 4000
  const staleRes = await rtdn.processDeveloperNotification({
    version: '1.0',
    packageName: 'com.tscanner.app',
    eventTimeMillis: 4000,
    subscriptionNotification: {
      version: '1.0',
      notificationType: RTDN_SUBSCRIPTION_TYPES.CANCELED,
      purchaseToken: token,
      subscriptionId: 'tscanner_vip_monthly'
    }
  });

  assert.strictEqual(staleRes.status, 'SKIPPED_STALE');
});

test('B04b RTDN: Blind revocation guard preserves user entitlement on Play API error', async () => {
  const mockApi = new MockGooglePlayBillingApi();
  const store = new EntitlementStore();
  const rtdn = new RtdnHandler(mockApi, store);

  const token = 'tok_guard_error';
  const initialEntitlement: BillingEntitlement = {
    id: `GOOGLE_PLAY_SUBS_${token}`,
    ownerAppUserId: 'usr_guard',
    productId: 'tscanner_vip_monthly',
    productType: 'subs',
    purchaseToken: token,
    source: 'GOOGLE_PLAY_SUBSCRIPTION',
    state: 'VERIFIED_ACTIVE',
    purchaseTimeMillis: Date.now(),
    expiryTimeMillis: Date.now() + 1000000,
    autoRenewing: true,
    verifiedAtMillis: Date.now(),
    snapshotVersion: 1
  };
  await store.bindOrUpdate('usr_guard', token, initialEntitlement);

  // Play API fails with transient error during RTDN lookup
  mockApi.registerSubscription(token, async () => {
    throw new Error('Google Play 500 internal error');
  });

  const res = await rtdn.processDeveloperNotification({
    version: '1.0',
    packageName: 'com.tscanner.app',
    eventTimeMillis: Date.now() + 1000,
    subscriptionNotification: {
      version: '1.0',
      notificationType: RTDN_SUBSCRIPTION_TYPES.REVOKED,
      purchaseToken: token,
      subscriptionId: 'tscanner_vip_monthly'
    }
  });

  assert.strictEqual(res.status, 'ERROR');

  // Verify entitlement was NOT blindly revoked
  const record = await store.getRecordByToken(token);
  assert.ok(record);
  assert.strictEqual(record.entitlement.state, 'VERIFIED_ACTIVE');
});

test('B04b Multi-Entitlement: Revoking subscription does not revoke user lifetime entitlement', async () => {
  const mockApi = new MockGooglePlayBillingApi();
  const store = new EntitlementStore();
  const rtdn = new RtdnHandler(mockApi, store);
  const verifier = new BillingVerifierService(mockApi, store);

  const subToken = 'tok_sub_revoked';
  const lifeToken = 'tok_life_preserved';

  // 1. User has verified lifetime purchase
  mockApi.registerInApp(lifeToken, async () => ({
    purchaseState: 0,
    consumptionState: 0,
    purchaseTimeMillis: Date.now()
  }));
  await verifier.verifyPurchase({
    ownerAppUserId: 'usr_multi_vip',
    productId: 'tscanner_vip_lifetime',
    productType: 'inapp',
    purchaseToken: lifeToken,
    clientPurchaseTimeMillis: Date.now()
  });

  // 2. User also had a subscription
  mockApi.registerSubscription(subToken, async () => ({
    acknowledgementState: 1,
    paymentState: 1,
    autoRenewing: true,
    expiryTimeMillis: Date.now() + 1000000
  }));
  await verifier.verifyPurchase({
    ownerAppUserId: 'usr_multi_vip',
    productId: 'tscanner_vip_monthly',
    productType: 'subs',
    purchaseToken: subToken,
    clientPurchaseTimeMillis: Date.now()
  });

  // 3. Subscription gets revoked via RTDN
  mockApi.registerSubscription(subToken, async () => ({
    acknowledgementState: 1,
    paymentState: 1,
    autoRenewing: false,
    expiryTimeMillis: Date.now()
  }));

  await rtdn.processDeveloperNotification({
    version: '1.0',
    packageName: 'com.tscanner.app',
    eventTimeMillis: Date.now() + 3000,
    subscriptionNotification: {
      version: '1.0',
      notificationType: RTDN_SUBSCRIPTION_TYPES.REVOKED,
      purchaseToken: subToken,
      subscriptionId: 'tscanner_vip_monthly'
    }
  });

  // 4. Verify all user entitlements: sub is REVOKED, lifetime is still VERIFIED_ACTIVE
  const userEntitlements = await store.getEntitlementsByOwner('usr_multi_vip');
  assert.strictEqual(userEntitlements.length, 2);

  const subEnt = userEntitlements.find(e => e.purchaseToken === subToken);
  const lifeEnt = userEntitlements.find(e => e.purchaseToken === lifeToken);

  assert.strictEqual(subEnt?.state, 'REVOKED');
  assert.strictEqual(lifeEnt?.state, 'VERIFIED_ACTIVE');
  assert.strictEqual(lifeEnt?.expiryTimeMillis, null);
});

test('B04b Acknowledge: Persist-before-ack queues token on external failure and drains on retry', async () => {
  const mockApi = new MockGooglePlayBillingApi();
  const ackService = new AcknowledgeService(mockApi);

  const token = 'tok_ack_retry_test';

  // First attempt fails
  mockApi.ackFailureHandler = async () => {
    throw new Error('Play API acknowledge timeout');
  };

  const initialRes = await ackService.acknowledge({
    purchaseToken: token,
    productId: 'tscanner_vip_yearly',
    productType: 'subs'
  });

  assert.strictEqual(initialRes.status, 'RETRY_QUEUED');
  assert.strictEqual(ackService.isAcknowledged(token), false);
  assert.strictEqual(ackService.getPendingQueueSize(), 1);

  // Play API recovers
  mockApi.ackFailureHandler = undefined;

  // Drain retry queue
  const successCount = await ackService.drainPendingQueue();
  assert.strictEqual(successCount, 1);
  assert.strictEqual(ackService.isAcknowledged(token), true);
  assert.strictEqual(ackService.getPendingQueueSize(), 0);

  // Re-acknowledging already acknowledged token
  const dupRes = await ackService.acknowledge({
    purchaseToken: token,
    productId: 'tscanner_vip_yearly',
    productType: 'subs'
  });
  assert.strictEqual(dupRes.status, 'ALREADY_ACKNOWLEDGED');
});
