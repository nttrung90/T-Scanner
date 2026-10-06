import test from 'node:test';
import assert from 'node:assert/strict';
import { AuthService, createTestJwt } from '../src/auth.ts';
import { EntitlementStore } from '../src/store.ts';
import { BillingVerifierService } from '../src/verifier.ts';
import { MockGooglePlayBillingApi, ProductionGooglePlayBillingApi } from '../src/googlePlayClient.ts';
import { RtdnHandler } from '../src/rtdnHandler.ts';

const logger = { info() {}, warn() {}, error() {} };
const req = {
    ownerAppUserId: 'audit-A',
    productId: 'tscanner_vip_yearly',
    productType: 'subs' as const,
    purchaseToken: 'synthetic-audit-token',
    clientPurchaseTimeMillis: 1
};
const sub = (expiry = Date.now() + 86400000) => ({
    acknowledgementState: 1,
    expiryTimeMillis: expiry,
    paymentState: 1,
    autoRenewing: true
});

test('auth must reject development-key JWT without required identity claims', () => {
    const auth = new AuthService({ jwtSecret: 'tscanner-dev-secret' });
    assert.throws(() => auth.verifyUserToken(createTestJwt({ sub: 'synthetic-user' })));
});

test('auth must reject unsupported algorithm even when HMAC matches', () => {
    const auth = new AuthService({ jwtSecret: 'audit-only' });
    assert.throws(() => auth.verifyUserToken(createTestJwt({ sub: 'A', exp: 4102444800 }, 'audit-only', { alg: 'RS256' })));
});

test('empty Play JSON must not grant lifetime VIP', async () => {
    const store = new EntitlementStore(':memory:');
    try {
        const api = new ProductionGooglePlayBillingApi({
            tokenProvider: async () => 'synthetic',
            fetchFn: async () => new Response('{}', { status: 200 })
        });
        const result = await new BillingVerifierService(api, store, logger).verifyPurchase({
            ...req,
            productId: 'tscanner_vip_lifetime',
            productType: 'inapp'
        });
        assert.notEqual(result.status, 'SUCCESS');
    } finally {
        store.close();
    }
});

test('entitlement ID must remain stable after authoritative expiry', async () => {
    const store = new EntitlementStore(':memory:');
    const api = new MockGooglePlayBillingApi();
    const service = new BillingVerifierService(api, store, logger);
    try {
        api.registerSubscription(req.purchaseToken, async () => sub());
        const active = await service.verifyPurchase(req);
        api.registerSubscription(req.purchaseToken, async () => sub(Date.now() - 1000));
        const expired = await service.verifyPurchase(req);
        assert.equal(expired.entitlement?.id, active.entitlement?.id);
    } finally {
        store.close();
    }
});

test('canceled active unacknowledged receipt must enter outbox', async () => {
    const store = new EntitlementStore(':memory:');
    const api = new MockGooglePlayBillingApi();
    try {
        api.registerSubscription(req.purchaseToken, async () => ({
            ...sub(),
            autoRenewing: false,
            acknowledgementState: 0
        }));
        await new BillingVerifierService(api, store, logger).verifyPurchase(req);
        assert.equal((await store.getPendingAckRetries()).length, 1);
    } finally {
        store.close();
    }
});

test('out-of-order RTDN completion must not override newer event', async () => {
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
            }
            return sub();
        });
        const handler = new RtdnHandler(api, store, logger);
        const event = (time: number, type: number) => ({
            version: '1',
            packageName: 'com.tscanner.app',
            eventTimeMillis: time,
            subscriptionNotification: {
                version: '1',
                notificationType: type,
                purchaseToken: req.purchaseToken,
                subscriptionId: req.productId
            }
        });
        const old = handler.processDeveloperNotification(event(100, 2));
        await started;
        await handler.processDeveloperNotification(event(200, 12));
        release();
        await old;
        assert.equal((await store.getRecordByToken(req.purchaseToken))?.entitlement.state, 'REVOKED');
    } finally {
        store.close();
    }
});

test('late expired verification cannot overwrite newer renewal', async () => {
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
                return sub(Date.now() - 1000);
            }
            return sub();
        });
        const old = service.verifyPurchase(req);
        await started;
        await service.verifyPurchase(req);
        release();
        await old;
        assert.equal((await store.getRecordByToken(req.purchaseToken))?.entitlement.state, 'VERIFIED_ACTIVE');
    } finally {
        store.close();
    }
});
