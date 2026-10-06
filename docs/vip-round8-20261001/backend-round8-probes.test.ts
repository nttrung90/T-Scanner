import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { EntitlementStore } from '../../backend/billing-verifier/src/store.ts';
import { BillingVerifierService } from '../../backend/billing-verifier/src/verifier.ts';
import { GooglePlayApiError, MockGooglePlayBillingApi, ProductionGooglePlayBillingApi } from '../../backend/billing-verifier/src/googlePlayClient.ts';
import { RtdnHandler } from '../../backend/billing-verifier/src/rtdnHandler.ts';
import type { BillingEntitlement } from '../../backend/billing-verifier/src/types.ts';
import type { BindOptions, BindResult } from '../../backend/billing-verifier/src/storage/types.ts';

const logger = { info() {}, warn() {}, error() {} };
const owner = 'round8-synthetic-owner-A';
const monthly = 'tscanner_vip_monthly';
const yearly = 'tscanner_vip_yearly';
const request = (token: string, productId = yearly) => ({ ownerAppUserId: owner, productId, productType: 'subs' as const, purchaseToken: token, clientPurchaseTimeMillis: 1 });
const active = () => ({ acknowledgementState: 1, expiryTimeMillis: Date.now() + 86400000, paymentState: 1, autoRenewing: true, subscriptionState: 'SUBSCRIPTION_STATE_ACTIVE', startTimeMillis: 1 });
const expired = () => ({ ...active(), subscriptionState: 'SUBSCRIPTION_STATE_EXPIRED', expiryTimeMillis: Date.now() - 10000, autoRenewing: false });
const canceledNew = (linkedPurchaseToken: string) => ({ ...active(), subscriptionState: 'SUBSCRIPTION_STATE_PENDING_PURCHASE_CANCELED', expiryTimeMillis: 0, paymentState: 0, autoRenewing: false, linkedPurchaseToken });
const payload = (token: string, time = Date.now(), notificationType = 20, subscriptionId = yearly) => ({
  version: '1.0', packageName: 'com.tscanner.app', eventTimeMillis: time,
  subscriptionNotification: { version: '1.0', notificationType, purchaseToken: token, subscriptionId }
});
// Current Google SubscriptionNotification schema carries no subscriptionId.
const googleSubscriptionPush = (token: string, notificationType: number) => ({
  message: {
    messageId: `synthetic-${token}-${notificationType}`,
    publishTime: new Date().toISOString(),
    data: Buffer.from(JSON.stringify({
      version: '1.0', packageName: 'com.tscanner.app', eventTimeMillis: String(Date.now()),
      subscriptionNotification: { version: '1.0', notificationType, purchaseToken: token }
    })).toString('base64')
  }
});
const databasePath = (slug: string) => {
  const folder = 'build/vip-audit-round8-20261001';
  fs.mkdirSync(folder, { recursive: true });
  return path.join(folder, `backend-agent-${slug}-${process.pid}-${Date.now()}.sqlite`);
};

async function seedKnownCanceledPair(api: MockGooglePlayBillingApi, store: EntitlementStore) {
  api.registerSubscription('old-linked', async () => active());
  api.registerSubscription('new-canceled', async () => canceledNew('old-linked'));
  await new BillingVerifierService(api, store, logger).verifyPurchase(request('new-canceled'));
  assert.equal((await store.getRecordByToken('new-canceled'))?.entitlement.state, 'REVOKED');
  assert.equal((await store.getRecordByToken('old-linked'))?.entitlement.state, 'VERIFIED_ACTIVE');
}

test('B801 known RTDN linked 503 must remain retryable with the identical event in the same process', async () => {
  const store = new EntitlementStore(':memory:');
  const api = new MockGooglePlayBillingApi();
  try {
    await seedKnownCanceledPair(api, store);
    let oldQueries = 0;
    api.registerSubscription('old-linked', async () => {
      oldQueries++;
      if (oldQueries === 1) throw new GooglePlayApiError('Synthetic one-shot linked outage', 503, true);
      return expired();
    });
    const handler = new RtdnHandler(api, store, logger);
    const event = payload('new-canceled');
    const first = await handler.processDeveloperNotification(event);
    assert.equal(first.status, 'ERROR');
    const retry = await handler.processDeveloperNotification(event);
    assert.equal((await store.getRecordByToken('old-linked'))!.entitlement.state, 'EXPIRED', `Retry=${retry.status}; old queries=${oldQueries}; failed linked step was consumed by event watermark`);
    assert.equal(retry.status, 'PROCESSED');
  } finally { store.close(); }
});

test('B802 known RTDN failed linked step must resume after close/reopen and a new handler', async () => {
  const dbPath = databasePath('rtdn-restart');
  const api = new MockGooglePlayBillingApi();
  let store = new EntitlementStore(dbPath);
  let oldQueries = 0;
  const event = payload('new-canceled');
  try {
    await seedKnownCanceledPair(api, store);
    api.registerSubscription('old-linked', async () => {
      oldQueries++;
      if (oldQueries === 1) throw new GooglePlayApiError('Synthetic one-shot linked outage', 503, true);
      return expired();
    });
    assert.equal((await new RtdnHandler(api, store, logger).processDeveloperNotification(event)).status, 'ERROR');
    store.close();
    store = new EntitlementStore(dbPath);
    const retry = await new RtdnHandler(api, store, logger).processDeveloperNotification(event);
    assert.equal((await store.getRecordByToken('old-linked'))!.entitlement.state, 'EXPIRED', `After restart retry=${retry.status}; old queries=${oldQueries}; persisted new-token event time hides unfinished linked work`);
  } finally { store.close(); }
});

function productionUpgradeApi() {
  let ownerHash = '';
  const api = new ProductionGooglePlayBillingApi({
    tokenProvider: async () => 'synthetic-access-token',
    fetchFn: async url => {
      const token = decodeURIComponent(String(url).split('/').at(-1)!);
      const newPurchase = token === 'new-paid-upgrade';
      const data = {
        subscriptionState: newPurchase ? 'SUBSCRIPTION_STATE_ACTIVE' : 'SUBSCRIPTION_STATE_EXPIRED',
        startTime: new Date(Date.now() - 10000).toISOString(),
        acknowledgementState: newPurchase ? 'ACKNOWLEDGEMENT_STATE_PENDING' : 'ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED',
        externalAccountIdentifiers: { obfuscatedExternalAccountId: ownerHash },
        ...(newPurchase ? { linkedPurchaseToken: 'old-paid-monthly' } : {}),
        lineItems: [{ productId: newPurchase ? yearly : monthly, expiryTime: new Date(Date.now() + (newPurchase ? 86400000 : -10000)).toISOString(), autoRenewingPlan: { autoRenewEnabled: newPurchase } }]
      };
      return new Response(JSON.stringify(data), { status: 200 });
    }
  });
  return { api, setHash(hash: string) { ownerHash = hash; } };
}

async function processUnknownPaidUpgrade(store: EntitlementStore) {
  const fixture = productionUpgradeApi();
  const svc = new BillingVerifierService(fixture.api, store, logger);
  const hash = svc.computeObfuscatedAccountId(owner);
  fixture.setHash(hash);
  const oldEntitlement: BillingEntitlement = {
    id: 'GOOGLE_PLAY_SUBSCRIPTION_old-paid-monthly', ownerAppUserId: owner, productId: monthly,
    productType: 'subs', purchaseToken: 'old-paid-monthly', source: 'GOOGLE_PLAY_SUBSCRIPTION',
    state: 'VERIFIED_ACTIVE', purchaseTimeMillis: 1, expiryTimeMillis: Date.now() + 86400000,
    autoRenewing: true, verifiedAtMillis: Date.now() - 10000, snapshotVersion: 1
  };
  await store.bindOrUpdate(owner, 'old-paid-monthly', oldEntitlement, hash);
  return new RtdnHandler(fixture.api, store, logger).handlePushNotification(googleSubscriptionPush('new-paid-upgrade', 4));
}

test('B803 unknown paid yearly upgrade RTDN must preserve ACTIVE new entitlement after old monthly expires', async () => {
  const store = new EntitlementStore(':memory:');
  try {
    const result = await processUnknownPaidUpgrade(store);
    const entitlements = await store.getEntitlementsByOwner(owner);
    assert.equal(entitlements.find(e => e.purchaseToken === 'old-paid-monthly')?.state, 'EXPIRED');
    assert.equal(entitlements.find(e => e.purchaseToken === 'new-paid-upgrade')?.state, 'VERIFIED_ACTIVE', `Paid ACTIVE token remained absent while response claimed ${result.status}/${result.newState}; all stored states=${entitlements.map(e => e.state).join(',')}`);
  } finally { store.close(); }
});

test('B804 unknown ACTIVE unacknowledged upgrade RTDN must enqueue acknowledgement for the paid new token', async () => {
  const store = new EntitlementStore(':memory:');
  try {
    const result = await processUnknownPaidUpgrade(store);
    assert.equal(result.status, 'PROCESSED');
    const jobs = await store.getPendingAckRetries();
    assert.equal(jobs.some(j => j.purchaseToken === 'new-paid-upgrade' && j.productId === yearly), true, `Paid new purchase was unacknowledged; RTDN=${result.status}/${result.newState}; durable outbox=${JSON.stringify(jobs)}`);
  } finally { store.close(); }
});

test('B805 unknown canceled-token storage rejection must not be acknowledged as PROCESSED or consumed', async () => {
  class OneShotRejectStore extends EntitlementStore {
    rejectNew = true;
    override async bindOrUpdate(user: string, token: string, ent: BillingEntitlement, hash?: string, options?: BindOptions): Promise<BindResult> {
      if (token === 'unknown-canceled' && this.rejectNew) {
        this.rejectNew = false;
        return { success: false, casConflict: true };
      }
      return super.bindOrUpdate(user, token, ent, hash, options);
    }
  }
  const store = new OneShotRejectStore(':memory:');
  const api = new MockGooglePlayBillingApi();
  try {
    const svc = new BillingVerifierService(api, store, logger);
    api.registerSubscription('old-linked', async () => active());
    await svc.verifyPurchase(request('old-linked'));
    api.registerSubscription('unknown-canceled', async () => canceledNew('old-linked'));
    const handler = new RtdnHandler(api, store, logger);
    const event = payload('unknown-canceled');
    const first = await handler.processDeveloperNotification(event);
    const persisted = await store.getRecordByToken('unknown-canceled');
    const second = await handler.processDeveloperNotification(event);
    assert.equal((await store.getRecordByToken('unknown-canceled'))?.entitlement.state, 'REVOKED', `Failed bind was ignored: first=${first.status}; stored initially=${!!persisted}; redelivery=${second.status}`);
  } finally { store.close(); }
});

test('B806 restore known canceled new then known linked old must have one result per purchase token', async () => {
  const store = new EntitlementStore(':memory:');
  const api = new MockGooglePlayBillingApi();
  try {
    await seedKnownCanceledPair(api, store);
    const result = await new BillingVerifierService(api, store, logger).restorePurchases({ ownerAppUserId: owner, purchases: [] });
    const tokens = result.results!.map(r => r.purchaseToken);
    assert.equal(tokens.length, new Set(tokens).size, `Duplicated linked result inflates UI resolved receipt count: ${JSON.stringify(result.results)}`);
    assert.equal(tokens.length, 2);
  } finally { store.close(); }
});

test('B807 control unknown canceled pending query transient is retryable before any new bind', async () => {
  const store = new EntitlementStore(':memory:');
  const api = new MockGooglePlayBillingApi();
  try {
    const svc = new BillingVerifierService(api, store, logger);
    api.registerSubscription('old-linked', async () => active());
    await svc.verifyPurchase(request('old-linked'));
    let oldQueries = 0;
    api.registerSubscription('old-linked', async () => {
      oldQueries++;
      if (oldQueries === 1) throw new GooglePlayApiError('Synthetic outage', 503, true);
      return expired();
    });
    api.registerSubscription('unknown-canceled', async () => canceledNew('old-linked'));
    const handler = new RtdnHandler(api, store, logger);
    const event = payload('unknown-canceled');
    assert.equal((await handler.processDeveloperNotification(event)).status, 'ERROR');
    assert.equal((await handler.processDeveloperNotification(event)).status, 'PROCESSED');
    assert.equal((await store.getRecordByToken('old-linked'))?.entitlement.state, 'EXPIRED');
    assert.equal((await store.getRecordByToken('unknown-canceled'))?.entitlement.state, 'REVOKED');
  } finally { store.close(); }
});

test('B808 control ordinary authenticated paid upgrade verification binds ACTIVE and durable ack job', async () => {
  const store = new EntitlementStore(':memory:');
  try {
    await processUnknownPaidUpgrade(store);
    const fixture = productionUpgradeApi();
    const svc = new BillingVerifierService(fixture.api, store, logger);
    fixture.setHash(svc.computeObfuscatedAccountId(owner));
    const result = await svc.verifyPurchase(request('new-paid-upgrade'));
    assert.equal(result.status, 'SUCCESS');
    assert.equal(result.entitlement?.state, 'VERIFIED_ACTIVE');
    assert.equal((await store.getPendingAckRetries()).some(j => j.purchaseToken === 'new-paid-upgrade'), true);
  } finally { store.close(); }
});

test('B809 identical RTDN redelivery on a second SQLite connection must finish failed linked work', async () => {
  const dbPath = databasePath('rtdn-two-connections');
  const store1 = new EntitlementStore(dbPath);
  const store2 = new EntitlementStore(dbPath);
  const api = new MockGooglePlayBillingApi();
  try {
    await seedKnownCanceledPair(api, store1);
    let oldQueries = 0;
    api.registerSubscription('old-linked', async () => {
      oldQueries++;
      if (oldQueries === 1) throw new GooglePlayApiError('Synthetic first worker linked outage', 503, true);
      return expired();
    });
    const event = payload('new-canceled');
    assert.equal((await new RtdnHandler(api, store1, logger).processDeveloperNotification(event)).status, 'ERROR');
    const retry = await new RtdnHandler(api, store2, logger).processDeveloperNotification(event);
    assert.equal((await store2.getRecordByToken('old-linked'))!.entitlement.state, 'EXPIRED', `Different worker and physical DB connection retry=${retry.status}; old queries=${oldQueries}; durable stale guard suppressed required linked work`);
  } finally { store1.close(); store2.close(); }
});

function canceledUpgradeV2Fixture(store: EntitlementStore) {
  let oldExpired = false;
  let ownerHash = '';
  const api = new ProductionGooglePlayBillingApi({
    tokenProvider: async () => 'synthetic-google-access',
    fetchFn: async url => {
      const token = decodeURIComponent(String(url).split('/').at(-1)!);
      const canceled = token === 'schema-new-canceled';
      const data = canceled ? {
        subscriptionState: 'SUBSCRIPTION_STATE_PENDING_PURCHASE_CANCELED',
        linkedPurchaseToken: 'schema-old-monthly',
        externalAccountIdentifiers: { obfuscatedExternalAccountId: ownerHash },
        lineItems: [{ productId: yearly }]
      } : {
        subscriptionState: oldExpired ? 'SUBSCRIPTION_STATE_EXPIRED' : 'SUBSCRIPTION_STATE_ACTIVE',
        acknowledgementState: 'ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED',
        startTime: new Date(1).toISOString(),
        externalAccountIdentifiers: { obfuscatedExternalAccountId: ownerHash },
        lineItems: [{ productId: monthly, expiryTime: new Date(Date.now() + (oldExpired ? -10000 : 86400000)).toISOString(), autoRenewingPlan: { autoRenewEnabled: !oldExpired } }]
      };
      return new Response(JSON.stringify(data), { status: 200 });
    }
  });
  const svc = new BillingVerifierService(api, store, logger);
  ownerHash = svc.computeObfuscatedAccountId(owner);
  return { api, svc, expireOld() { oldExpired = true; } };
}

test('B810 current Google PubSub pending-canceled envelope without subscriptionId must persist unknown canceled receipt', async () => {
  const store = new EntitlementStore(':memory:');
  try {
    const fixture = canceledUpgradeV2Fixture(store);
    await fixture.svc.verifyPurchase(request('schema-old-monthly', monthly));
    fixture.expireOld();
    const handler = new RtdnHandler(fixture.api, store, logger);
    let result: Awaited<ReturnType<RtdnHandler['handlePushNotification']>> | undefined;
    let productionError: unknown;
    try {
      result = await handler.handlePushNotification(googleSubscriptionPush('schema-new-canceled', 20));
    } catch (err) { productionError = err; }
    assert.equal(productionError, undefined, `Current Google schema has no subscriptionId; production handler threw after valid V2 response: ${String(productionError)}`);
    assert.equal(result?.status, 'PROCESSED');
    assert.equal((await store.getRecordByToken('schema-new-canceled'))?.entitlement.productId, yearly);
    assert.equal((await store.getRecordByToken('schema-new-canceled'))?.entitlement.state, 'REVOKED');
  } finally { store.close(); }
});

test('B811 control same Google no-subscriptionId pending-canceled envelope works for an already-known receipt', async () => {
  const store = new EntitlementStore(':memory:');
  try {
    const fixture = canceledUpgradeV2Fixture(store);
    await fixture.svc.verifyPurchase(request('schema-new-canceled'));
    fixture.expireOld();
    const result = await new RtdnHandler(fixture.api, store, logger).handlePushNotification(googleSubscriptionPush('schema-new-canceled', 20));
    assert.equal(result.status, 'PROCESSED');
    assert.equal((await store.getRecordByToken('schema-new-canceled'))?.entitlement.productId, yearly);
    assert.equal((await store.getRecordByToken('schema-new-canceled'))?.entitlement.state, 'REVOKED');
    assert.equal((await store.getRecordByToken('schema-old-monthly'))?.entitlement.state, 'EXPIRED');
  } finally { store.close(); }
});

async function refundedLifetimeFixture(store: EntitlementStore) {
  const lifetime = 'tscanner_vip_lifetime';
  const token = 'synthetic-refunded-lifetime';
  const api = new MockGooglePlayBillingApi();
  api.registerInApp(token, async () => ({ purchaseState: 0, consumptionState: 0, acknowledgementState: 1, purchaseTimeMillis: Date.now() - 60000, orderId: 'synthetic-lifetime-order' }));
  const svc = new BillingVerifierService(api, store, logger);
  const purchase = { ...request(token, lifetime), productType: 'inapp' as const };
  const verified = await svc.verifyPurchase(purchase);
  assert.equal(verified.status, 'SUCCESS');
  assert.equal(verified.entitlement?.state, 'VERIFIED_ACTIVE');
  // Full paid refund: Google authoritative products.get now reports canceled.
  api.registerInApp(token, async () => ({ purchaseState: 1, consumptionState: 0, acknowledgementState: 1, purchaseTimeMillis: Date.now() - 60000, orderId: 'synthetic-lifetime-order' }));
  const push = {
    message: {
      messageId: 'synthetic-full-lifetime-refund', publishTime: new Date().toISOString(),
      data: Buffer.from(JSON.stringify({
        version: '1.0', packageName: 'com.tscanner.app', eventTimeMillis: String(Date.now()),
        voidedPurchaseNotification: { purchaseToken: token, orderId: 'synthetic-lifetime-order', productType: 2, refundType: 1 }
      })).toString('base64')
    }
  };
  return { api, svc, token, purchase, push };
}

test('B812 current Google full voided-purchase refund envelope must revoke verified lifetime VIP', async () => {
  const store = new EntitlementStore(':memory:');
  try {
    const fixture = await refundedLifetimeFixture(store);
    const result = await new RtdnHandler(fixture.api, store, logger).handlePushNotification(fixture.push);
    assert.equal((await store.getRecordByToken(fixture.token))?.entitlement.state, 'REVOKED', `Full paid lifetime refund was ignored as ${result.status}/${result.message}; stored lifetime remains active without expiry`);
  } finally { store.close(); }
});

test('B813 control ordinary account restore observes the same full lifetime refund and revokes it', async () => {
  const store = new EntitlementStore(':memory:');
  try {
    const fixture = await refundedLifetimeFixture(store);
    const result = await fixture.svc.restorePurchases({ ownerAppUserId: owner, purchases: [] });
    assert.equal(result.snapshot.entitlements.find(e => e.purchaseToken === fixture.token)?.state, 'REVOKED');
    assert.equal(result.results?.find(r => r.purchaseToken === fixture.token)?.status, 'REVOKED');
  } finally { store.close(); }
});
