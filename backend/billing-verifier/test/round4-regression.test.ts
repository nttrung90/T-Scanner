import test from 'node:test';
import assert from 'node:assert/strict';
import { generateKeyPairSync } from 'node:crypto';
import { AuthService, createTestRsaJwt } from '../src/auth.ts';
import { EntitlementStore } from '../src/store.ts';
import { BillingVerifierService } from '../src/verifier.ts';
import { MockGooglePlayBillingApi, ProductionGooglePlayBillingApi } from '../src/googlePlayClient.ts';

const logger = { info() {}, warn() {}, error() {} };
const req = {
    ownerAppUserId: 'audit-A',
    productId: 'tscanner_vip_yearly',
    productType: 'subs' as const,
    purchaseToken: 'synthetic-r4-token',
    clientPurchaseTimeMillis: 1
};
const sub = () => ({
    acknowledgementState: 1,
    expiryTimeMillis: Date.now() + 86400000,
    paymentState: 1,
    autoRenewing: true
});
const keys = generateKeyPairSync('rsa', { modulusLength: 2048 });
const token = (extra: any = {}) => createTestRsaJwt({
    sub: 'synthetic-principal',
    iss: 'https://accounts.google.com',
    aud: 'foreign-client',
    email: 'unrelated@example.test',
    email_verified: true,
    exp: Math.floor(Date.now() / 1000) + 1000,
    ...extra
}, keys.privateKey, { kid: 'audit-key' });

test('configured googleClientId must enforce matching audience', async () => {
    const auth = new AuthService({ googleClientId: 'expected-client' });
    auth.setJwksKeysForTesting([{ kid: 'audit-key', publicKey: keys.publicKey }]);
    await assert.rejects(() => auth.verifyUserTokenAsync(token()));
});

test('PubSub must fail closed without audience and service account configuration', async () => {
    const auth = new AuthService({ googleClientId: 'expected-client' });
    auth.setJwksKeysForTesting([{ kid: 'audit-key', publicKey: keys.publicKey }]);
    await assert.rejects(() => auth.authenticatePubSub({ headers: { authorization: `Bearer ${token()}` } } as any));
});

test('PubSub must validate issuer in addition to audience and email', async () => {
    const auth = new AuthService({
        pubsubExpectedAudience: 'push-endpoint',
        pubsubExpectedServiceAccount: 'push@example.test'
    });
    auth.setJwksKeysForTesting([{ kid: 'audit-key', publicKey: keys.publicKey }]);
    await assert.rejects(() => auth.authenticatePubSub({
        headers: {
            authorization: `Bearer ${token({
                iss: 'https://invalid.example',
                aud: 'push-endpoint',
                email: 'push@example.test'
            })}`
        }
    } as any));
});

test('control valid RSA user token with explicit audience is accepted', async () => {
    const auth = new AuthService({ expectedAudience: 'expected-client' });
    auth.setJwksKeysForTesting([{ kid: 'audit-key', publicKey: keys.publicKey }]);
    assert.equal((await auth.verifyUserTokenAsync(token({ aud: 'expected-client' }))).sub, 'synthetic-principal');
});

for (const state of ['SUBSCRIPTION_STATE_PAUSED', 'SUBSCRIPTION_STATE_UNKNOWN']) {
    test(`V2 ${state} must not become active paid entitlement`, async () => {
        const store = new EntitlementStore(':memory:');
        try {
            const api = new ProductionGooglePlayBillingApi({
                tokenProvider: async () => 'synthetic',
                fetchFn: async () => new Response(JSON.stringify({
                    subscriptionState: state,
                    acknowledgementState: 'ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED',
                    lineItems: [{
                        productId: req.productId,
                        expiryTime: new Date(Date.now() + 86400000).toISOString()
                    }]
                }), { status: 200 })
            });
            const result = await new BillingVerifierService(api, store, logger).verifyPurchase(req);
            assert.ok(result.status !== 'SUCCESS' || !['VERIFIED_ACTIVE', 'CANCELED_ACTIVE', 'IN_GRACE_PERIOD'].includes(result.entitlement!.state));
        } finally {
            store.close();
        }
    });
}

test('V2 product mismatch must not grant requested VIP', async () => {
    const store = new EntitlementStore(':memory:');
    try {
        const api = new ProductionGooglePlayBillingApi({
            tokenProvider: async () => 'synthetic',
            fetchFn: async () => new Response(JSON.stringify({
                subscriptionState: 'SUBSCRIPTION_STATE_ACTIVE',
                lineItems: [{
                    productId: 'different_product',
                    expiryTime: new Date(Date.now() + 86400000).toISOString()
                }]
            }), { status: 200 })
        });
        assert.notEqual((await new BillingVerifierService(api, store, logger).verifyPurchase(req)).status, 'SUCCESS');
    } finally {
        store.close();
    }
});

test('late ACTIVE verification must not resurrect newer EXPIRED snapshot', async () => {
    const store = new EntitlementStore(':memory:');
    const api = new MockGooglePlayBillingApi();
    const service = new BillingVerifierService(api, store, logger);
    try {
        api.registerSubscription(req.purchaseToken, async () => sub());
        await service.verifyPurchase(req);
        let release!: () => void;
        let entered!: () => void;
        const started = new Promise<void>(r => entered = r);
        const blocked = new Promise<void>(r => release = r);
        let calls = 0;
        api.registerSubscription(req.purchaseToken, async () => {
            if (++calls === 1) {
                entered();
                await blocked;
                return sub();
            }
            return { ...sub(), expiryTimeMillis: Date.now() - 1000 };
        });
        const old = service.verifyPurchase(req);
        await started;
        await service.verifyPurchase(req);
        release();
        await old;
        assert.equal((await store.getRecordByToken(req.purchaseToken))?.entitlement.state, 'EXPIRED');
    } finally {
        store.close();
    }
});

test('expired receipt owned by another Play hash must not bind to caller', async () => {
    const store = new EntitlementStore(':memory:');
    const api = new MockGooglePlayBillingApi();
    const service = new BillingVerifierService(api, store, logger);
    try {
        api.registerSubscription(req.purchaseToken, async () => ({
            ...sub(),
            expiryTimeMillis: Date.now() - 1000,
            obfuscatedExternalAccountId: service.computeObfuscatedAccountId('rightful-A')
        }));
        await service.verifyPurchase({ ...req, ownerAppUserId: 'wrong-B' });
        assert.equal(await store.getRecordByToken(req.purchaseToken), undefined);
    } finally {
        store.close();
    }
});

test('empty-candidate restore must refresh known lifetime receipt before success', async () => {
    const store = new EntitlementStore(':memory:');
    const api = new MockGooglePlayBillingApi();
    const service = new BillingVerifierService(api, store, logger);
    try {
        const r = { ...req, productId: 'tscanner_vip_lifetime', productType: 'inapp' as const };
        api.registerInApp(r.purchaseToken, async () => ({
            purchaseState: 0,
            consumptionState: 0,
            acknowledgementState: 1,
            purchaseTimeMillis: 1
        }));
        await service.verifyPurchase(r);
        api.registerInApp(r.purchaseToken, async () => ({
            purchaseState: 1,
            consumptionState: 0,
            acknowledgementState: 1,
            purchaseTimeMillis: 1
        }));
        const result = await service.restorePurchases({ ownerAppUserId: r.ownerAppUserId, purchases: [] });
        assert.equal(result.snapshot.entitlements[0].state, 'REVOKED');
    } finally {
        store.close();
    }
});
