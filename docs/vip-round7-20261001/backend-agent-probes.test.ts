import test from 'node:test';
import assert from 'node:assert/strict';
import { EntitlementStore } from '../../backend/billing-verifier/src/store.ts';
import { BillingVerifierService } from '../../backend/billing-verifier/src/verifier.ts';
import { GooglePlayApiError, MockGooglePlayBillingApi, ProductionGooglePlayBillingApi } from '../../backend/billing-verifier/src/googlePlayClient.ts';
import { RtdnHandler } from '../../backend/billing-verifier/src/rtdnHandler.ts';

const logger = { info() {}, warn() {}, error() {} };
const owner = 'synthetic-round7-A';
const yearly = 'tscanner_vip_yearly';
const monthly = 'tscanner_vip_monthly';
const request = (token: string, productId = yearly) => ({ ownerAppUserId: owner, productId, productType: 'subs' as const, purchaseToken: token, clientPurchaseTimeMillis: 1 });
const active = () => ({ acknowledgementState: 1, expiryTimeMillis: Date.now() + 86400000, paymentState: 1, autoRenewing: true, subscriptionState: 'SUBSCRIPTION_STATE_ACTIVE', startTimeMillis: 1 });
const canceledNew = (linkedPurchaseToken: string) => ({ ...active(), subscriptionState: 'SUBSCRIPTION_STATE_PENDING_PURCHASE_CANCELED', expiryTimeMillis: 0, paymentState: 0, autoRenewing: false, linkedPurchaseToken });
const expiry = () => ({ ...active(), subscriptionState: 'SUBSCRIPTION_STATE_EXPIRED', expiryTimeMillis: Date.now() - 10000, autoRenewing: false });
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>(r => { resolve = r; });
  return { promise, resolve };
}

test('B701 first-bind linked query must not overwrite a newer authoritative EXPIRED receipt', async () => {
  const store = new EntitlementStore(':memory:');
  const api = new MockGooglePlayBillingApi();
  const svc = new BillingVerifierService(api, store, logger);
  const started = deferred<void>();
  const delayed = deferred<ReturnType<typeof active>>();
  try {
    api.registerSubscription('new-canceled', async () => canceledNew('old-linked'));
    let oldQueries = 0;
    api.registerSubscription('old-linked', async () => {
      oldQueries++;
      if (oldQueries === 1) { started.resolve(); return delayed.promise; }
      return expiry();
    });
    const resolving = svc.verifyPurchase(request('new-canceled'));
    await started.promise;
    const newer = await svc.verifyPurchase(request('old-linked'));
    assert.equal(newer.entitlement?.state, 'EXPIRED');
    delayed.resolve(active());
    await resolving;
    const actual = (await store.getRecordByToken('old-linked'))!.entitlement;
    assert.equal(actual.state, 'EXPIRED', `Late linked query resurrected ${actual.state} at v${actual.snapshotVersion}`);
  } finally { store.close(); }
});

test('B702 canceled yearly upgrade must recover authoritative linked monthly receipt', async () => {
  const store = new EntitlementStore(':memory:');
  const queriedTokens: string[] = [];
  const api = new ProductionGooglePlayBillingApi({
    tokenProvider: async () => 'synthetic-access',
    fetchFn: async url => {
      const token = decodeURIComponent(String(url).split('/').at(-1)!);
      queriedTokens.push(token);
      const payload = token === 'new-yearly'
        ? { subscriptionState: 'SUBSCRIPTION_STATE_PENDING_PURCHASE_CANCELED', linkedPurchaseToken: 'old-monthly', lineItems: [{ productId: yearly }] }
        : { subscriptionState: 'SUBSCRIPTION_STATE_ACTIVE', startTime: new Date(1).toISOString(), acknowledgementState: 'ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED', lineItems: [{ productId: monthly, expiryTime: new Date(Date.now() + 86400000).toISOString(), autoRenewingPlan: { autoRenewEnabled: true } }] };
      return new Response(JSON.stringify(payload), { status: 200 });
    }
  });
  try {
    const svc = new BillingVerifierService(api, store, logger);
    const result = await svc.restorePurchases({ ownerAppUserId: owner, purchases: [request('new-yearly')] });
    assert.deepEqual(queriedTokens, ['new-yearly', 'old-monthly']);
    const old = result.snapshot.entitlements.find(e => e.purchaseToken === 'old-monthly');
    assert.equal(old?.productId, monthly, 'Linked monthly receipt was queried using yearly SKU and silently discarded');
    assert.equal(old.state, 'VERIFIED_ACTIVE');
  } finally { store.close(); }
});

test('B703 unresolved linked upstream failure must not be reported as complete restore SUCCESS', async () => {
  const store = new EntitlementStore(':memory:');
  const api = new MockGooglePlayBillingApi();
  try {
    api.registerSubscription('new-canceled', async () => canceledNew('old-linked'));
    api.registerSubscription('old-linked', async () => { throw new GooglePlayApiError('Synthetic upstream offline', 503, true); });
    const result = await new BillingVerifierService(api, store, logger).restorePurchases({ ownerAppUserId: owner, purchases: [request('new-canceled')] });
    assert.notEqual(result.status, 'SUCCESS', `Unresolved linked receipt was swallowed: ${JSON.stringify(result.results)}`);
  } finally { store.close(); }
});

test('B704 RTDN after a real pending verification must refresh the canceled purchases linked old receipt', async () => {
  const store = new EntitlementStore(':memory:');
  const api = new MockGooglePlayBillingApi();
  try {
    const svc = new BillingVerifierService(api, store, logger);
    api.registerSubscription('old-linked', async () => active());
    api.registerSubscription('new-canceled', async () => ({ ...active(), subscriptionState: 'SUBSCRIPTION_STATE_PENDING', paymentState: 0, expiryTimeMillis: 0, autoRenewing: false, linkedPurchaseToken: 'old-linked' }));
    await svc.verifyPurchase(request('old-linked'));
    const pending = await svc.verifyPurchase(request('new-canceled'));
    assert.equal(pending.status, 'PENDING');
    assert.equal(await store.getRecordByToken('new-canceled'), undefined, 'Production verifier currently does not persist a pending token');
    let oldRefreshQueries = 0;
    let canceledNewQueries = 0;
    api.registerSubscription('old-linked', async () => { oldRefreshQueries++; return expiry(); });
    api.registerSubscription('new-canceled', async () => { canceledNewQueries++; return canceledNew('old-linked'); });
    const result = await new RtdnHandler(api, store, logger).processDeveloperNotification({
      version: '1.0', packageName: 'com.tscanner.app', eventTimeMillis: Date.now(),
      subscriptionNotification: { version: '1.0', notificationType: 3, purchaseToken: 'new-canceled', subscriptionId: yearly }
    });
    const old = (await store.getRecordByToken('old-linked'))!.entitlement;
    assert.equal(old.state, 'EXPIRED', `Real pending token remained unbound; RTDN returned ${result.status}; canceledNewQueries=${canceledNewQueries}, oldRefreshQueries=${oldRefreshQueries}; linked old ACTIVE remained stale`);
  } finally { store.close(); }
});

test('B705 all-pending restore must preserve per-token PENDING semantics', async () => {
  const store = new EntitlementStore(':memory:');
  const api = new MockGooglePlayBillingApi();
  try {
    api.registerSubscription('pending-new', async () => ({ ...active(), subscriptionState: 'SUBSCRIPTION_STATE_PENDING', paymentState: 0, expiryTimeMillis: 0 }));
    const result = await new BillingVerifierService(api, store, logger).restorePurchases({ ownerAppUserId: owner, purchases: [request('pending-new')] });
    assert.equal(result.results?.[0].status, 'PENDING', `Payment PENDING was recategorized as ${JSON.stringify(result.results)}`);
  } finally { store.close(); }
});

test('B706 all unknown rejected restore candidates must not be reported as full SUCCESS', async () => {
  const store = new EntitlementStore(':memory:');
  const api = new MockGooglePlayBillingApi();
  try {
    api.registerSubscription('unknown-new', async () => { throw new GooglePlayApiError('Synthetic token not found', 404, false); });
    const result = await new BillingVerifierService(api, store, logger).restorePurchases({ ownerAppUserId: owner, purchases: [request('unknown-new')] });
    assert.equal(result.results?.[0].status, 'REJECTED');
    assert.notEqual(result.status, 'SUCCESS', 'Every candidate failed verification but aggregate status was SUCCESS');
  } finally { store.close(); }
});

test('B709 control same-SKU linked ACTIVE receipt is resolved and owned correctly', async () => {
  const store = new EntitlementStore(':memory:');
  const api = new MockGooglePlayBillingApi();
  try {
    api.registerSubscription('new-canceled', async () => canceledNew('old-linked'));
    api.registerSubscription('old-linked', async () => active());
    const result = await new BillingVerifierService(api, store, logger).restorePurchases({ ownerAppUserId: owner, purchases: [request('new-canceled')] });
    assert.equal(result.snapshot.entitlements.find(e => e.purchaseToken === 'old-linked')?.state, 'VERIFIED_ACTIVE');
    assert.equal(result.snapshot.entitlements.find(e => e.purchaseToken === 'old-linked')?.ownerAppUserId, owner);
    assert.equal(result.snapshot.entitlements.find(e => e.purchaseToken === 'new-canceled')?.state, 'REVOKED');
  } finally { store.close(); }
});

test('B710 control linked receipt already owned by another account is preserved', async () => {
  const store = new EntitlementStore(':memory:');
  const api = new MockGooglePlayBillingApi();
  try {
    const svc = new BillingVerifierService(api, store, logger);
    api.registerSubscription('old-linked', async () => active());
    await svc.verifyPurchase({ ...request('old-linked'), ownerAppUserId: 'synthetic-B' });
    api.registerSubscription('new-canceled', async () => canceledNew('old-linked'));
    await svc.verifyPurchase(request('new-canceled'));
    assert.equal((await store.getRecordByToken('old-linked'))?.ownerAppUserId, 'synthetic-B');
  } finally { store.close(); }
});

test('B711 linked first-bind race must remain protected across two SQLite connections and restart', async () => {
  const dbPath = `build/vip-audit-round7-20261001/backend-agent-race-${process.pid}.sqlite`;
  const store1 = new EntitlementStore(dbPath);
  const store2 = new EntitlementStore(dbPath);
  const api1 = new MockGooglePlayBillingApi();
  const api2 = new MockGooglePlayBillingApi();
  const started = deferred<void>();
  const delayed = deferred<ReturnType<typeof active>>();
  try {
    api1.registerSubscription('durable-new-canceled', async () => canceledNew('durable-old-linked'));
    let oldQueries = 0;
    api1.registerSubscription('durable-old-linked', async () => {
      oldQueries++;
      if (oldQueries === 1) { started.resolve(); return delayed.promise; }
      return expiry();
    });
    api2.registerSubscription('durable-old-linked', async () => expiry());
    const resolving = new BillingVerifierService(api1, store1, logger).verifyPurchase(request('durable-new-canceled'));
    await started.promise;
    const newer = await new BillingVerifierService(api2, store2, logger).verifyPurchase(request('durable-old-linked'));
    assert.equal(newer.entitlement?.state, 'EXPIRED');
    delayed.resolve(active());
    await resolving;
  } finally { store1.close(); store2.close(); }
  const reopened = new EntitlementStore(dbPath);
  try {
    const ent = (await reopened.getRecordByToken('durable-old-linked'))!.entitlement;
    assert.equal(ent.state, 'EXPIRED', `Wrong durable grant survived restart: ${ent.state} at v${ent.snapshotVersion}`);
  } finally { reopened.close(); }
});
