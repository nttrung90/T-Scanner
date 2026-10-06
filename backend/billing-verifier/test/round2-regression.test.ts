import test from 'node:test';
import assert from 'node:assert/strict';
import { EntitlementStore } from '../src/store.ts';
import { MockGooglePlayBillingApi, GooglePlayApiError } from '../src/googlePlayClient.ts';
import { RtdnHandler } from '../src/rtdnHandler.ts';
import { BillingVerifierService } from '../src/verifier.ts';
import type { BillingEntitlement } from '../src/types.ts';

const logger = { info: () => {}, warn: () => {}, error: () => {} };
const active = (): BillingEntitlement => ({
    id: 'receipt',
    ownerAppUserId: 'A',
    productId: 'tscanner_vip_yearly',
    productType: 'subs',
    purchaseToken: 'synthetic-token',
    source: 'GOOGLE_PLAY_SUBSCRIPTION',
    state: 'VERIFIED_ACTIVE',
    purchaseTimeMillis: Date.now() - 1000,
    expiryTimeMillis: Date.now() + 86400000,
    autoRenewing: true,
    verifiedAtMillis: Date.now(),
    snapshotVersion: 1
});

test('probe RTDN retries same event after temporary Google API failure', async () => {
    const store = new EntitlementStore(), api = new MockGooglePlayBillingApi();
    await store.bindOrUpdate('A', 'synthetic-token', active());
    const handler = new RtdnHandler(api, store, logger);
    const payload = {
        version: '1',
        packageName: 'com.tscanner.app',
        eventTimeMillis: Date.now(),
        subscriptionNotification: {
            version: '1',
            notificationType: 12,
            purchaseToken: 'synthetic-token',
            subscriptionId: 'tscanner_vip_yearly'
        }
    };
    api.registerSubscription('synthetic-token', async () => { throw new GooglePlayApiError('temporary', 503, true); });
    assert.equal((await handler.processDeveloperNotification(payload)).status, 'ERROR');
    api.registerSubscription('synthetic-token', async () => ({
        acknowledgementState: 1,
        expiryTimeMillis: Date.now(),
        paymentState: 1,
        autoRenewing: false
    }));
    assert.equal((await handler.processDeveloperNotification(payload)).status, 'PROCESSED', 'Failed event must remain retryable');
});

test('probe authoritative expired verification must not restore stale active receipt', async () => {
    const store = new EntitlementStore(), api = new MockGooglePlayBillingApi();
    await store.bindOrUpdate('A', 'synthetic-token', active());
    api.registerSubscription('synthetic-token', async () => ({
        acknowledgementState: 1,
        expiryTimeMillis: Date.now() - 1000,
        paymentState: 1,
        autoRenewing: false
    }));
    const service = new BillingVerifierService(api, store, logger);
    const result = await service.restorePurchases({
        ownerAppUserId: 'A',
        purchases: [{ productId: 'tscanner_vip_yearly', productType: 'subs', purchaseToken: 'synthetic-token' }]
    });
    assert.equal(result.snapshot?.entitlements.some(e => e.state === 'VERIFIED_ACTIVE'), false, 'Restore returned stored active receipt after Google reported expiry');
});

test('probe HTTP restore endpoint must require authentication', async () => {
    process.env.NODE_ENV = 'test';
    const { server, store } = await import('../src/index.ts');
    await store.bindOrUpdate('A', 'synthetic-token', active());
    await new Promise<void>(resolve => server.listen(0, '127.0.0.1', resolve));
    try {
        const address = server.address();
        if (!address || typeof address === 'string') throw Error('No local address');
        const response = await fetch(`http://127.0.0.1:${address.port}/api/v1/billing/restore`, {
            method: 'POST',
            headers: { 'content-type': 'application/json' },
            body: JSON.stringify({ ownerAppUserId: 'A', purchases: [] })
        });
        assert.ok(response.status === 401 || response.status === 403, `Unauthenticated request returned ${response.status}`);
    } finally {
        await new Promise<void>((resolve, reject) => server.close(e => e ? reject(e) : resolve()));
    }
});

test('control ownership conflict rejected within one store instance', async () => {
    const store = new EntitlementStore();
    await store.bindOrUpdate('A', 'synthetic-token', active());
    assert.equal((await store.bindOrUpdate('B', 'synthetic-token', active())).success, false);
});
