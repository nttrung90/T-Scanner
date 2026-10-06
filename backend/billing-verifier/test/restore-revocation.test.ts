process.env.NODE_ENV = 'test';
import test from 'node:test';
import assert from 'node:assert/strict';
import { BillingVerifierService, defaultLogger } from '../src/verifier.ts';
import { EntitlementStore } from '../src/store.ts';
import { GooglePlayApiError, type GooglePlayBillingApi } from '../src/googlePlayClient.ts';
import type { BillingEntitlement } from '../src/types.ts';

function createActiveEntitlement(owner: string, token: string, isLifetime: boolean = false): BillingEntitlement {
  return {
    id: token,
    ownerAppUserId: owner,
    productId: isLifetime ? 'tscanner_vip_lifetime' : 'tscanner_vip_yearly',
    productType: isLifetime ? 'inapp' : 'subs',
    purchaseToken: token,
    orderId: `GPA.TEST-${token}`,
    source: isLifetime ? 'GOOGLE_PLAY_INAPP' : 'GOOGLE_PLAY_SUBSCRIPTION',
    state: 'VERIFIED_ACTIVE',
    purchaseTimeMillis: Date.now() - 50000,
    expiryTimeMillis: isLifetime ? null : Date.now() + 86400000,
    autoRenewing: !isLifetime,
    verifiedAtMillis: Date.now() - 50000,
    snapshotVersion: 1
  };
}

class FakeGoogleApi implements GooglePlayBillingApi {
  subHandlers: Map<string, () => Promise<any>> = new Map();
  inAppHandlers: Map<string, () => Promise<any>> = new Map();

  async getSubscription(packageName: string, subscriptionId: string, token: string): Promise<any> {
    const handler = this.subHandlers.get(token);
    if (handler) return handler();
    throw new GooglePlayApiError('Subscription not found', 404);
  }

  async getInAppProduct(packageName: string, productId: string, token: string): Promise<any> {
    const handler = this.inAppHandlers.get(token);
    if (handler) return handler();
    throw new GooglePlayApiError('InApp not found', 404);
  }

  async acknowledgeSubscription(packageName: string, subscriptionId: string, token: string): Promise<void> {}
  async acknowledgeInAppProduct(packageName: string, productId: string, token: string): Promise<void> {}
}

test('Restore Revocation: Active cached subscription that Play reports expired is updated to EXPIRED in store and not returned active', async () => {
  const store = new EntitlementStore(':memory:');
  const api = new FakeGoogleApi();
  const service = new BillingVerifierService(api, store, defaultLogger);

  // 1. Seed store with previously active subscription (snapshotVersion 1)
  await store.bindOrUpdate('usr_alice', 'tok_sub_1', createActiveEntitlement('usr_alice', 'tok_sub_1'));
  const beforeRestore = await store.getEntitlementsByOwner('usr_alice');
  assert.equal(beforeRestore.length, 1);
  assert.equal(beforeRestore[0].state, 'VERIFIED_ACTIVE');

  // 2. Google Play reports that the subscription expired in the past
  const pastExpiry = Date.now() - 5000;
  api.subHandlers.set('tok_sub_1', async () => ({
    acknowledgementState: 1,
    expiryTimeMillis: pastExpiry,
    paymentState: 1,
    autoRenewing: false
  }));

  // 3. User calls restore
  const restoreRes = await service.restorePurchases({
    ownerAppUserId: 'usr_alice',
    purchases: [{ productId: 'tscanner_vip_yearly', productType: 'subs', purchaseToken: 'tok_sub_1' }]
  });

  // Verify response
  assert.equal(restoreRes.status, 'SUCCESS');
  assert.equal(restoreRes.snapshot.ownerAppUserId, 'usr_alice');
  assert.equal(restoreRes.snapshot.entitlements.length, 1);

  const returnedEntitlement = restoreRes.snapshot.entitlements[0];
  assert.equal(returnedEntitlement.state, 'EXPIRED', 'Must not return VERIFIED_ACTIVE for expired subscription');
  assert.equal(returnedEntitlement.expiryTimeMillis, pastExpiry);
  assert.equal(returnedEntitlement.snapshotVersion, 2, 'Snapshot version must monotonically increment on state change');

  // Verify stored record
  const storedRecord = await store.getRecordByToken('tok_sub_1');
  assert.equal(storedRecord?.entitlement.state, 'EXPIRED');
  assert.equal(storedRecord?.latestSnapshotVersion, 2);

  store.close();
});

test('Restore Revocation: Refunded/Canceled lifetime in-app is marked REVOKED in store and response', async () => {
  const store = new EntitlementStore(':memory:');
  const api = new FakeGoogleApi();
  const service = new BillingVerifierService(api, store, defaultLogger);

  // 1. Seed store with previously active Lifetime VIP
  await store.bindOrUpdate('usr_bob', 'tok_life_1', createActiveEntitlement('usr_bob', 'tok_life_1', true));
  assert.equal((await store.getEntitlementsByOwner('usr_bob'))[0].state, 'VERIFIED_ACTIVE');

  // 2. Google Play reports purchaseState = 1 (Canceled / Refunded)
  api.inAppHandlers.set('tok_life_1', async () => ({
    purchaseState: 1,
    consumptionState: 0,
    purchaseTimeMillis: Date.now() - 100000
  }));

  // 3. User calls restore
  const restoreRes = await service.restorePurchases({
    ownerAppUserId: 'usr_bob',
    purchases: [{ productId: 'tscanner_vip_lifetime', productType: 'inapp', purchaseToken: 'tok_life_1' }]
  });

  assert.equal(restoreRes.status, 'SUCCESS');
  const entitlement = restoreRes.snapshot.entitlements[0];
  assert.equal(entitlement.state, 'REVOKED', 'Must return REVOKED for refunded in-app purchase');
  assert.equal(entitlement.snapshotVersion, 2);

  // Verify store
  const storedRecord = await store.getRecordByToken('tok_life_1');
  assert.equal(storedRecord?.entitlement.state, 'REVOKED');
  assert.equal(storedRecord?.latestSnapshotVersion, 2);

  store.close();
});

test('Restore Revocation: Google Play 503 transient error preserves cached state and returns TRANSIENT_ERROR status', async () => {
  const store = new EntitlementStore(':memory:');
  const api = new FakeGoogleApi();
  const service = new BillingVerifierService(api, store, defaultLogger);

  // 1. Seed store with active subscription
  await store.bindOrUpdate('usr_charlie', 'tok_transient_1', createActiveEntitlement('usr_charlie', 'tok_transient_1'));

  // 2. Google Play throws 503
  api.subHandlers.set('tok_transient_1', async () => {
    throw new GooglePlayApiError('Play API 503', 503, true);
  });

  // 3. User calls restore
  const restoreRes = await service.restorePurchases({
    ownerAppUserId: 'usr_charlie',
    purchases: [{ productId: 'tscanner_vip_yearly', productType: 'subs', purchaseToken: 'tok_transient_1' }]
  });

  // Status must be TRANSIENT_ERROR because the single candidate encountered 503
  assert.equal(restoreRes.status, 'TRANSIENT_ERROR');
  // Existing cached state must be preserved (not deleted or marked expired)
  assert.equal(restoreRes.snapshot.entitlements.length, 1);
  assert.equal(restoreRes.snapshot.entitlements[0].state, 'VERIFIED_ACTIVE');
  assert.equal(restoreRes.snapshot.entitlements[0].snapshotVersion, 1, 'Version must not be mutated on transient error');

  store.close();
});

test('Restore Revocation: Partial failure reports PARTIAL status with itemized results', async () => {
  const store = new EntitlementStore(':memory:');
  const api = new FakeGoogleApi();
  const service = new BillingVerifierService(api, store, defaultLogger);

  // Token 1: Valid and active
  api.subHandlers.set('tok_good', async () => ({
    acknowledgementState: 1,
    expiryTimeMillis: Date.now() + 86400000,
    paymentState: 1,
    autoRenewing: true
  }));

  // Token 2: 503 error
  api.subHandlers.set('tok_failing', async () => {
    throw new GooglePlayApiError('Service unavailable', 503, true);
  });

  const restoreRes = await service.restorePurchases({
    ownerAppUserId: 'usr_david',
    purchases: [
      { productId: 'tscanner_vip_yearly', productType: 'subs', purchaseToken: 'tok_good' },
      { productId: 'tscanner_vip_yearly', productType: 'subs', purchaseToken: 'tok_failing' }
    ]
  });

  assert.equal(restoreRes.status, 'PARTIAL');
  assert.ok(restoreRes.results);
  assert.equal(restoreRes.results.length, 2);

  const goodResult = restoreRes.results.find(r => r.purchaseToken === 'tok_good');
  const failingResult = restoreRes.results.find(r => r.purchaseToken === 'tok_failing');
  assert.equal(goodResult?.status, 'SUCCESS');
  assert.equal(failingResult?.status, 'TRANSIENT_ERROR');

  store.close();
});

test('Restore Revocation: Ownership conflict rejects candidate belonging to another user', async () => {
  const store = new EntitlementStore(':memory:');
  const api = new FakeGoogleApi();
  const service = new BillingVerifierService(api, store, defaultLogger);

  // Token 1 was previously bound to Alice
  await store.bindOrUpdate('usr_alice', 'tok_alice_owned', createActiveEntitlement('usr_alice', 'tok_alice_owned'));

  // Eve attempts to restore Alice's token
  api.subHandlers.set('tok_alice_owned', async () => ({
    acknowledgementState: 1,
    expiryTimeMillis: Date.now() + 86400000,
    paymentState: 1,
    autoRenewing: true
  }));

  const restoreRes = await service.restorePurchases({
    ownerAppUserId: 'usr_eve',
    purchases: [{ productId: 'tscanner_vip_yearly', productType: 'subs', purchaseToken: 'tok_alice_owned' }]
  });

  assert.equal(restoreRes.status, 'SUCCESS');
  // Eve must NOT receive Alice's entitlement
  assert.equal(restoreRes.snapshot.entitlements.length, 0);

  const resultItem = restoreRes.results?.find(r => r.purchaseToken === 'tok_alice_owned');
  assert.equal(resultItem?.status, 'REJECTED');
  assert.equal(resultItem?.reason, 'OWNERSHIP_CONFLICT');

  store.close();
});

test('Restore Revocation: Empty candidate list does not leak other users entitlements', async () => {
  const store = new EntitlementStore(':memory:');
  const api = new FakeGoogleApi();
  const service = new BillingVerifierService(api, store, defaultLogger);

  // Store has Alice's entitlement
  await store.bindOrUpdate('usr_alice', 'tok_alice_private', createActiveEntitlement('usr_alice', 'tok_alice_private'));

  // Frank calls restore with empty purchases
  const restoreRes = await service.restorePurchases({
    ownerAppUserId: 'usr_frank',
    purchases: []
  });

  assert.equal(restoreRes.status, 'SUCCESS');
  assert.equal(restoreRes.snapshot.entitlements.length, 0, 'Must return empty entitlements for Frank');

  store.close();
});

test('M03: Active -> Expired -> Renewal lifecycle maintains stable canonical ID and monotonic versions', async () => {
  const store = new EntitlementStore(':memory:');
  const api = new FakeGoogleApi();
  const service = new BillingVerifierService(api, store, defaultLogger);

  const token = 'tok_lifecycle_chain';
  const req = {
    ownerAppUserId: 'usr_charlie',
    productId: 'tscanner_vip_yearly',
    productType: 'subs' as const,
    purchaseToken: token,
    clientPurchaseTimeMillis: Date.now()
  };

  // 1. Initial purchase -> VERIFIED_ACTIVE
  const expiry1 = Date.now() + 86400000;
  api.subHandlers.set(token, async () => ({
    acknowledgementState: 1,
    expiryTimeMillis: expiry1,
    paymentState: 1,
    autoRenewing: true
  }));
  const res1 = await service.verifyPurchase(req);
  assert.equal(res1.status, 'SUCCESS');
  assert.equal(res1.entitlement?.id, `GOOGLE_PLAY_SUBSCRIPTION_${token}`);
  assert.equal(res1.entitlement?.state, 'VERIFIED_ACTIVE');
  assert.equal(res1.entitlement?.snapshotVersion, 1);

  // 2. Authoritative Expiry -> EXPIRED with stable canonical ID
  const pastExpiry = Date.now() - 10000;
  api.subHandlers.set(token, async () => ({
    acknowledgementState: 1,
    expiryTimeMillis: pastExpiry,
    paymentState: 1,
    autoRenewing: false
  }));
  const res2 = await service.verifyPurchase(req);
  assert.equal(res2.status, 'REJECTED');
  assert.equal(res2.reason, 'PURCHASE_EXPIRED');
  assert.equal(res2.entitlement?.id, `GOOGLE_PLAY_SUBSCRIPTION_${token}`);
  assert.equal(res2.entitlement?.state, 'EXPIRED');
  assert.equal(res2.entitlement?.snapshotVersion, 2);

  // Verify store state
  const storedAfterExpiry = await store.getRecordByToken(token);
  assert.equal(storedAfterExpiry?.entitlement.id, `GOOGLE_PLAY_SUBSCRIPTION_${token}`);
  assert.equal(storedAfterExpiry?.entitlement.state, 'EXPIRED');
  assert.equal(storedAfterExpiry?.latestSnapshotVersion, 2);

  // 3. User renews subscription -> VERIFIED_ACTIVE with stable canonical ID and version 3
  const expiry3 = Date.now() + 86400000 * 30;
  api.subHandlers.set(token, async () => ({
    acknowledgementState: 1,
    expiryTimeMillis: expiry3,
    paymentState: 1,
    autoRenewing: true
  }));
  const res3 = await service.verifyPurchase(req);
  assert.equal(res3.status, 'SUCCESS');
  assert.equal(res3.entitlement?.id, `GOOGLE_PLAY_SUBSCRIPTION_${token}`);
  assert.equal(res3.entitlement?.state, 'VERIFIED_ACTIVE');
  assert.equal(res3.entitlement?.snapshotVersion, 3);

  // Verify store has only 1 record under this token
  const records = await store.getEntitlementsByOwner('usr_charlie');
  assert.equal(records.length, 1);
  assert.equal(records[0].id, `GOOGLE_PLAY_SUBSCRIPTION_${token}`);
  assert.equal(records[0].state, 'VERIFIED_ACTIVE');
  assert.equal(records[0].snapshotVersion, 3);

  store.close();
});

test('M03: Lifetime active -> Revoked maintains stable canonical ID and rejects unauthorized owner', async () => {
  const store = new EntitlementStore(':memory:');
  const api = new FakeGoogleApi();
  const service = new BillingVerifierService(api, store, defaultLogger);

  const token = 'tok_life_revocation';
  const reqAlice = {
    ownerAppUserId: 'usr_alice',
    productId: 'tscanner_vip_lifetime',
    productType: 'inapp' as const,
    purchaseToken: token,
    clientPurchaseTimeMillis: Date.now()
  };

  // 1. Initial purchase -> VERIFIED_ACTIVE
  api.inAppHandlers.set(token, async () => ({
    purchaseState: 0,
    consumptionState: 0,
    acknowledgementState: 1,
    purchaseTimeMillis: Date.now() - 1000
  }));
  const res1 = await service.verifyPurchase(reqAlice);
  assert.equal(res1.status, 'SUCCESS');
  assert.equal(res1.entitlement?.id, `GOOGLE_PLAY_INAPP_${token}`);
  assert.equal(res1.entitlement?.state, 'VERIFIED_ACTIVE');

  // 2. Play reports revoked (purchaseState = 1) -> REJECTED with committed tombstone
  api.inAppHandlers.set(token, async () => ({
    purchaseState: 1,
    consumptionState: 0,
    acknowledgementState: 1,
    purchaseTimeMillis: Date.now() - 1000
  }));
  const res2 = await service.verifyPurchase(reqAlice);
  assert.equal(res2.status, 'REJECTED');
  assert.equal(res2.reason, 'PURCHASE_REVOKED');
  assert.equal(res2.entitlement?.id, `GOOGLE_PLAY_INAPP_${token}`);
  assert.equal(res2.entitlement?.state, 'REVOKED');
  assert.equal(res2.entitlement?.snapshotVersion, 2);

  // 3. Foreign user Bob tries to claim Alice's revoked token -> OWNERSHIP_CONFLICT
  const reqBob = {
    ...reqAlice,
    ownerAppUserId: 'usr_bob'
  };
  const resBob = await service.verifyPurchase(reqBob);
  assert.equal(resBob.status, 'REJECTED');
  assert.equal(resBob.reason, 'OWNERSHIP_CONFLICT');

  // Verify stored record remains bound to Alice as REVOKED
  const stored = await store.getRecordByToken(token);
  assert.equal(stored?.ownerAppUserId, 'usr_alice');
  assert.equal(stored?.entitlement.state, 'REVOKED');

  store.close();
});
