import test from 'node:test';
import assert from 'node:assert';
import { BillingVerifierService, type Logger } from '../src/verifier.ts';
import { EntitlementStore } from '../src/store.ts';
import {
  GooglePlayApiError,
  MissingCredentialsError,
  MockGooglePlayBillingApi,
  ProductionGooglePlayBillingApi
} from '../src/googlePlayClient.ts';
import type { VerificationRequest } from '../src/types.ts';

class TestLogger implements Logger {
  logs: string[] = [];
  info(msg: string) { this.logs.push(`INFO: ${msg}`); }
  warn(msg: string) { this.logs.push(`WARN: ${msg}`); }
  error(msg: string, err?: unknown) { this.logs.push(`ERROR: ${msg} ${err || ''}`); }
}

test('B04a Verifier: Disallowed product ID is rejected', async () => {
  const mockApi = new MockGooglePlayBillingApi();
  const store = new EntitlementStore();
  const service = new BillingVerifierService(mockApi, store);

  const req: VerificationRequest = {
    ownerAppUserId: 'usr_1',
    productId: 'hacked_super_vip',
    productType: 'subs',
    purchaseToken: 'tok_disallowed_123',
    clientPurchaseTimeMillis: Date.now()
  };

  const res = await service.verifyPurchase(req);
  assert.strictEqual(res.status, 'REJECTED');
  assert.strictEqual(res.reason, 'PRODUCT_NOT_ALLOWED');
});

test('B04a Verifier: Package name mismatch is rejected', async () => {
  const mockApi = new MockGooglePlayBillingApi();
  const store = new EntitlementStore();
  const service = new BillingVerifierService(mockApi, store);

  const req: VerificationRequest = {
    ownerAppUserId: 'usr_1',
    productId: 'tscanner_vip_yearly',
    productType: 'subs',
    purchaseToken: 'tok_pkg_123',
    packageName: 'com.malicious.fakeapp',
    clientPurchaseTimeMillis: Date.now()
  };

  const res = await service.verifyPurchase(req);
  assert.strictEqual(res.status, 'REJECTED');
  assert.strictEqual(res.reason, 'PACKAGE_NAME_MISMATCH');
});

test('B04a Verifier: Invalid token not found in Google Play is rejected', async () => {
  const mockApi = new MockGooglePlayBillingApi();
  const store = new EntitlementStore();
  const service = new BillingVerifierService(mockApi, store);

  const req: VerificationRequest = {
    ownerAppUserId: 'usr_1',
    productId: 'tscanner_vip_yearly',
    productType: 'subs',
    purchaseToken: 'tok_not_in_google',
    clientPurchaseTimeMillis: Date.now()
  };

  const res = await service.verifyPurchase(req);
  assert.strictEqual(res.status, 'REJECTED');
  assert.strictEqual(res.reason, 'INVALID_SIGNATURE_OR_TOKEN');
});

test('B04a Verifier: Valid subscription returns VERIFIED_ACTIVE with exact Play expiry', async () => {
  const mockApi = new MockGooglePlayBillingApi();
  const store = new EntitlementStore();
  const service = new BillingVerifierService(mockApi, store);

  const token = 'tok_valid_sub_yearly';
  const expectedExpiry = Date.now() + 365 * 24 * 3600 * 1000;

  mockApi.registerSubscription(token, async () => ({
    acknowledgementState: 1,
    paymentState: 1,
    autoRenewing: true,
    expiryTimeMillis: expectedExpiry,
    orderId: 'GPA.1111-2222-3333-44444'
  }));

  const req: VerificationRequest = {
    ownerAppUserId: 'usr_alice',
    productId: 'tscanner_vip_yearly',
    productType: 'subs',
    purchaseToken: token,
    clientPurchaseTimeMillis: Date.now()
  };

  const res = await service.verifyPurchase(req);
  assert.strictEqual(res.status, 'SUCCESS');
  assert.ok(res.entitlement);
  assert.strictEqual(res.entitlement.state, 'VERIFIED_ACTIVE');
  assert.strictEqual(res.entitlement.expiryTimeMillis, expectedExpiry);
  assert.strictEqual(res.entitlement.ownerAppUserId, 'usr_alice');
  assert.strictEqual(res.entitlement.orderId, 'GPA.1111-2222-3333-44444');
});

test('B04a Verifier: Valid Lifetime in-app product returns null expiryTimeMillis', async () => {
  const mockApi = new MockGooglePlayBillingApi();
  const store = new EntitlementStore();
  const service = new BillingVerifierService(mockApi, store);

  const token = 'tok_valid_lifetime';

  mockApi.registerInApp(token, async () => ({
    purchaseState: 0, // Purchased
    consumptionState: 0,
    purchaseTimeMillis: Date.now(),
    orderId: 'GPA.LIFETIME-9999'
  }));

  const req: VerificationRequest = {
    ownerAppUserId: 'usr_bob',
    productId: 'tscanner_vip_lifetime',
    productType: 'inapp',
    purchaseToken: token,
    clientPurchaseTimeMillis: Date.now()
  };

  const res = await service.verifyPurchase(req);
  assert.strictEqual(res.status, 'SUCCESS');
  assert.ok(res.entitlement);
  assert.strictEqual(res.entitlement.state, 'VERIFIED_ACTIVE');
  assert.strictEqual(res.entitlement.expiryTimeMillis, null); // Lifetime has no expiry
  assert.strictEqual(res.entitlement.ownerAppUserId, 'usr_bob');
});

test('B04a Verifier: Ownership conflict rejects User B claiming User A token', async () => {
  const mockApi = new MockGooglePlayBillingApi();
  const store = new EntitlementStore();
  const service = new BillingVerifierService(mockApi, store);

  const token = 'shared_token_123';
  mockApi.registerSubscription(token, async () => ({
    acknowledgementState: 1,
    paymentState: 1,
    autoRenewing: true,
    expiryTimeMillis: Date.now() + 1000000
  }));

  // User A binds token first
  const resA = await service.verifyPurchase({
    ownerAppUserId: 'usr_alice',
    productId: 'tscanner_vip_yearly',
    productType: 'subs',
    purchaseToken: token,
    clientPurchaseTimeMillis: Date.now()
  });
  assert.strictEqual(resA.status, 'SUCCESS');

  // User B attempts to claim same token
  const resB = await service.verifyPurchase({
    ownerAppUserId: 'usr_charlie',
    productId: 'tscanner_vip_yearly',
    productType: 'subs',
    purchaseToken: token,
    clientPurchaseTimeMillis: Date.now()
  });
  assert.strictEqual(resB.status, 'REJECTED');
  assert.strictEqual(resB.reason, 'OWNERSHIP_CONFLICT');
  assert.ok(resB.message?.includes('permanently linked to another user'));
});

test('B04a Verifier: ObfuscatedAccountId mismatch from Play is rejected', async () => {
  const mockApi = new MockGooglePlayBillingApi();
  const store = new EntitlementStore();
  const service = new BillingVerifierService(mockApi, store);

  const token = 'tok_hash_mismatch';
  // Play Console records hash of user_other
  const otherHash = service.computeObfuscatedAccountId('usr_other');

  mockApi.registerSubscription(token, async () => ({
    acknowledgementState: 1,
    paymentState: 1,
    autoRenewing: true,
    expiryTimeMillis: Date.now() + 1000000,
    obfuscatedExternalAccountId: otherHash
  }));

  // Current request is for user_alice
  const res = await service.verifyPurchase({
    ownerAppUserId: 'usr_alice',
    productId: 'tscanner_vip_yearly',
    productType: 'subs',
    purchaseToken: token,
    clientPurchaseTimeMillis: Date.now()
  });

  assert.strictEqual(res.status, 'REJECTED');
  assert.strictEqual(res.reason, 'ACCOUNT_HASH_MISMATCH');
});

test('B04a Verifier: Concurrent replay is idempotent without race condition', async () => {
  const mockApi = new MockGooglePlayBillingApi();
  const store = new EntitlementStore();
  const service = new BillingVerifierService(mockApi, store);

  const token = 'tok_concurrent_replay';
  const expiry = Date.now() + 5000000;
  mockApi.registerSubscription(token, async () => ({
    acknowledgementState: 1,
    paymentState: 1,
    autoRenewing: true,
    expiryTimeMillis: expiry
  }));

  const req: VerificationRequest = {
    ownerAppUserId: 'usr_david',
    productId: 'tscanner_vip_yearly',
    productType: 'subs',
    purchaseToken: token,
    clientPurchaseTimeMillis: Date.now()
  };

  // Launch 5 parallel requests
  const results = await Promise.all([
    service.verifyPurchase(req),
    service.verifyPurchase(req),
    service.verifyPurchase(req),
    service.verifyPurchase(req),
    service.verifyPurchase(req)
  ]);

  for (const res of results) {
    assert.strictEqual(res.status, 'SUCCESS');
    assert.strictEqual(res.entitlement?.ownerAppUserId, 'usr_david');
    assert.strictEqual(res.entitlement?.expiryTimeMillis, expiry);
  }
});

test('B04a Verifier: Expired subscription returns PURCHASE_EXPIRED', async () => {
  const mockApi = new MockGooglePlayBillingApi();
  const store = new EntitlementStore();
  const service = new BillingVerifierService(mockApi, store);

  const token = 'tok_expired_sub';
  mockApi.registerSubscription(token, async () => ({
    acknowledgementState: 1,
    paymentState: 1,
    autoRenewing: false,
    expiryTimeMillis: Date.now() - 10000 // 10s in the past
  }));

  const res = await service.verifyPurchase({
    ownerAppUserId: 'usr_eve',
    productId: 'tscanner_vip_monthly',
    productType: 'subs',
    purchaseToken: token,
    clientPurchaseTimeMillis: Date.now()
  });

  assert.strictEqual(res.status, 'REJECTED');
  assert.strictEqual(res.reason, 'PURCHASE_EXPIRED');
});

test('B04a Verifier: Revoked/refunded in-app purchase returns PURCHASE_REVOKED', async () => {
  const mockApi = new MockGooglePlayBillingApi();
  const store = new EntitlementStore();
  const service = new BillingVerifierService(mockApi, store);

  const token = 'tok_revoked_life';
  mockApi.registerInApp(token, async () => ({
    purchaseState: 1, // 1 = Canceled / Refunded
    consumptionState: 0,
    purchaseTimeMillis: Date.now() - 500000
  }));

  const res = await service.verifyPurchase({
    ownerAppUserId: 'usr_frank',
    productId: 'tscanner_vip_lifetime',
    productType: 'inapp',
    purchaseToken: token,
    clientPurchaseTimeMillis: Date.now()
  });

  assert.strictEqual(res.status, 'REJECTED');
  assert.strictEqual(res.reason, 'PURCHASE_REVOKED');
});

test('B04a Verifier: Pending payment returns status PENDING', async () => {
  const mockApi = new MockGooglePlayBillingApi();
  const store = new EntitlementStore();
  const service = new BillingVerifierService(mockApi, store);

  const token = 'tok_pending_payment';
  mockApi.registerSubscription(token, async () => ({
    acknowledgementState: 0,
    paymentState: 0, // 0 = Pending
    autoRenewing: true,
    expiryTimeMillis: Date.now() + 5000000
  }));

  const res = await service.verifyPurchase({
    ownerAppUserId: 'usr_grace',
    productId: 'tscanner_vip_yearly',
    productType: 'subs',
    purchaseToken: token,
    clientPurchaseTimeMillis: Date.now()
  });

  assert.strictEqual(res.status, 'PENDING');
  assert.strictEqual(res.entitlement, undefined);
});

test('B04a Verifier: Google Play transient error returns TRANSIENT_ERROR', async () => {
  const mockApi = new MockGooglePlayBillingApi();
  const store = new EntitlementStore();
  const service = new BillingVerifierService(mockApi, store);

  const token = 'tok_503_error';
  mockApi.registerSubscription(token, async () => {
    throw new GooglePlayApiError('Play backend 503', 503, true);
  });

  const res = await service.verifyPurchase({
    ownerAppUserId: 'usr_heidi',
    productId: 'tscanner_vip_yearly',
    productType: 'subs',
    purchaseToken: token,
    clientPurchaseTimeMillis: Date.now()
  });

  assert.strictEqual(res.status, 'TRANSIENT_ERROR');
});

test('B04a Verifier: Sensitive token is masked in logs', async () => {
  const testLogger = new TestLogger();
  const mockApi = new MockGooglePlayBillingApi();
  const store = new EntitlementStore();
  const service = new BillingVerifierService(mockApi, store, testLogger);

  const fullToken = 'secret_google_play_purchase_token_full_1234567890';
  mockApi.registerSubscription(fullToken, async () => ({
    acknowledgementState: 1,
    paymentState: 1,
    autoRenewing: true,
    expiryTimeMillis: Date.now() + 1000000
  }));

  await service.verifyPurchase({
    ownerAppUserId: 'usr_ivan',
    productId: 'tscanner_vip_yearly',
    productType: 'subs',
    purchaseToken: fullToken,
    clientPurchaseTimeMillis: Date.now()
  });

  // Verify that the full raw token is NEVER printed in plain text
  for (const log of testLogger.logs) {
    assert.strictEqual(
      log.includes(fullToken),
      false,
      `Full token was leaked in log: ${log}`
    );
    assert.ok(log.includes('secret...7890'), `Masked token should appear in log: ${log}`);
  }
});

test('B04a Verifier: Restore aggregates active entitlements for owner', async () => {
  const mockApi = new MockGooglePlayBillingApi();
  const store = new EntitlementStore();
  const service = new BillingVerifierService(mockApi, store);

  const token1 = 'tok_restore_sub';
  const token2 = 'tok_restore_life';

  mockApi.registerSubscription(token1, async () => ({
    acknowledgementState: 1,
    paymentState: 1,
    autoRenewing: true,
    expiryTimeMillis: Date.now() + 1000000
  }));

  mockApi.registerInApp(token2, async () => ({
    purchaseState: 0,
    consumptionState: 0,
    purchaseTimeMillis: Date.now()
  }));

  const restoreRes = await service.restorePurchases({
    ownerAppUserId: 'usr_judy',
    purchases: [
      { productId: 'tscanner_vip_yearly', productType: 'subs', purchaseToken: token1 },
      { productId: 'tscanner_vip_lifetime', productType: 'inapp', purchaseToken: token2 }
    ]
  });

  assert.strictEqual(restoreRes.status, 'SUCCESS');
  assert.strictEqual(restoreRes.snapshot.ownerAppUserId, 'usr_judy');
  assert.strictEqual(restoreRes.snapshot.entitlements.length, 2);
});

test('B04a Verifier Gate: Production client without credentials throws MissingCredentialsError', async () => {
  delete process.env.GOOGLE_APPLICATION_CREDENTIALS;
  const prodClient = new ProductionGooglePlayBillingApi();

  await assert.rejects(
    async () => prodClient.getSubscription('com.tscanner.app', 'tscanner_vip_yearly', 'tok_test'),
    MissingCredentialsError
  );
});
