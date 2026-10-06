import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer, type Server } from 'node:http';
import {
  ProductionGooglePlayBillingApi,
  GooglePlayApiError,
  MissingCredentialsError
} from '../src/googlePlayClient.ts';
import {
  ALLOWED_SUBSCRIPTION_IDS,
  ALLOWED_INAPP_IDS,
  PRODUCT_ALIASES
} from '../src/verifier.ts';

test('Q10: Production client without credentials throws MissingCredentialsError', async () => {
  const client = new ProductionGooglePlayBillingApi({});
  await assert.rejects(
    async () => client.getSubscription('com.tscanner.app', 'tscanner_vip_yearly', 'fake-token'),
    MissingCredentialsError
  );
  await assert.rejects(
    async () => client.getInAppProduct('com.tscanner.app', 'tscanner_vip_lifetime', 'fake-token'),
    MissingCredentialsError
  );
});

test('Q10: HTTP Transport - Request path, package, token, and Bearer auth', async () => {
  let capturedAuth = '';
  let capturedPath = '';
  let capturedMethod = '';

  const server: Server = createServer((req, res) => {
    capturedAuth = req.headers.authorization || '';
    capturedPath = req.url || '';
    capturedMethod = req.method || '';

    if (capturedPath.includes('/purchases/subscriptionsv2/tokens/')) {
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({
        subscriptionState: 'SUBSCRIPTION_STATE_ACTIVE',
        startTime: '2024-03-09T18:40:00Z',
        acknowledgementState: 'ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED',
        latestOrderId: 'GPA.1111-2222-3333-4444',
        lineItems: [{
          productId: 'tscanner_vip_yearly',
          expiryTime: '2024-07-03T18:40:00Z',
          autoRenewingPlan: { autoRenewEnabled: true }
        }]
      }));
      return;
    }

    res.writeHead(404);
    res.end();
  });

  await new Promise<void>(resolve => server.listen(0, '127.0.0.1', resolve));
  const address = server.address() as any;
  const baseUrl = `http://127.0.0.1:${address.port}`;

  try {
    const client = new ProductionGooglePlayBillingApi({
      baseUrl,
      tokenProvider: async () => 'mock-oauth2-access-token'
    });

    const result = await client.getSubscription(
      'com.tscanner.app',
      'tscanner_vip_yearly',
      'token-special/chars==123'
    );

    assert.equal(capturedMethod, 'GET');
    assert.equal(capturedAuth, 'Bearer mock-oauth2-access-token');
    assert.ok(
      capturedPath.includes('/androidpublisher/v3/applications/com.tscanner.app/purchases/subscriptionsv2/tokens/token-special%2Fchars%3D%3D123'),
      `Path must encode parameters properly using subscriptionsv2: ${capturedPath}`
    );

    assert.equal(result.acknowledgementState, 1);
    assert.equal(result.expiryTimeMillis, Date.parse('2024-07-03T18:40:00Z'));
    assert.equal(result.startTimeMillis, Date.parse('2024-03-09T18:40:00Z'));
    assert.equal(result.autoRenewing, true);
    assert.equal(result.paymentState, 1);
    assert.equal(result.orderId, 'GPA.1111-2222-3333-4444');
    assert.equal(result.subscriptionState, 'SUBSCRIPTION_STATE_ACTIVE');
  } finally {
    await new Promise<void>(resolve => server.close(() => resolve()));
  }
});

test('Q10: HTTP Transport - In-app product mapping', async () => {
  const server: Server = createServer((req, res) => {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({
      purchaseState: 0,
      consumptionState: 0,
      acknowledgementState: 0,
      purchaseTimeMillis: '1710000050000',
      orderId: 'GPA.5555-6666-7777-8888',
      obfuscatedExternalAccountId: 'hash-abc-123'
    }));
  });

  await new Promise<void>(resolve => server.listen(0, '127.0.0.1', resolve));
  const address = server.address() as any;
  const baseUrl = `http://127.0.0.1:${address.port}`;

  try {
    const client = new ProductionGooglePlayBillingApi({
      baseUrl,
      tokenProvider: async () => 'mock-token'
    });

    const result = await client.getInAppProduct('com.tscanner.app', 'tscanner_vip_lifetime', 'token-inapp-test');
    assert.equal(result.purchaseState, 0);
    assert.equal(result.consumptionState, 0);
    assert.equal(result.acknowledgementState, 0);
    assert.equal(result.purchaseTimeMillis, 1710000050000);
    assert.equal(result.orderId, 'GPA.5555-6666-7777-8888');
    assert.equal(result.obfuscatedExternalAccountId, 'hash-abc-123');
  } finally {
    await new Promise<void>(resolve => server.close(() => resolve()));
  }
});

test('Q10: HTTP Transport - Error mapping (401, 403, 404, 429, 503, malformed)', async () => {
  let responseStatus = 200;
  let responseBody = '{}';

  const server: Server = createServer((_req, res) => {
    res.writeHead(responseStatus, { 'Content-Type': 'application/json' });
    res.end(responseBody);
  });

  await new Promise<void>(resolve => server.listen(0, '127.0.0.1', resolve));
  const address = server.address() as any;
  const baseUrl = `http://127.0.0.1:${address.port}`;

  try {
    const client = new ProductionGooglePlayBillingApi({
      baseUrl,
      tokenProvider: async () => 'mock-token'
    });

    // 401 Unauthorized
    responseStatus = 401;
    responseBody = '{"error":{"message":"Invalid Credentials"}}';
    await assert.rejects(
      async () => client.getSubscription('com.tscanner.app', 'tscanner_vip_yearly', 'tok-1'),
      (err: GooglePlayApiError) => err.statusCode === 401 && !err.isTransient
    );

    // 404 Not Found
    responseStatus = 404;
    responseBody = '{"error":{"message":"The purchase token was not found"}}';
    await assert.rejects(
      async () => client.getSubscription('com.tscanner.app', 'tscanner_vip_yearly', 'tok-1'),
      (err: GooglePlayApiError) => err.statusCode === 404 && !err.isTransient
    );

    // 429 Rate Limited (Transient)
    responseStatus = 429;
    responseBody = '{"error":{"message":"Rate limit exceeded"}}';
    await assert.rejects(
      async () => client.getSubscription('com.tscanner.app', 'tscanner_vip_yearly', 'tok-1'),
      (err: GooglePlayApiError) => err.statusCode === 429 && err.isTransient
    );

    // 503 Service Unavailable (Transient)
    responseStatus = 503;
    responseBody = '{"error":{"message":"Backend unavailable"}}';
    await assert.rejects(
      async () => client.getSubscription('com.tscanner.app', 'tscanner_vip_yearly', 'tok-1'),
      (err: GooglePlayApiError) => err.statusCode === 503 && err.isTransient
    );

    // Malformed JSON (Transient)
    responseStatus = 200;
    responseBody = '{malformed json...';
    await assert.rejects(
      async () => client.getSubscription('com.tscanner.app', 'tscanner_vip_yearly', 'tok-1'),
      (err: GooglePlayApiError) => err.isTransient
    );
  } finally {
    await new Promise<void>(resolve => server.close(() => resolve()));
  }
});

test('Q10: HTTP Transport - Acknowledge endpoints and idempotent already-acknowledged', async () => {
  let acknowledgeCount = 0;

  const server: Server = createServer((req, res) => {
    if (req.url?.includes(':acknowledge')) {
      acknowledgeCount++;
      if (acknowledgeCount === 1) {
        // First call succeeds with 204 No Content
        res.writeHead(204);
        res.end();
        return;
      } else {
        // Subsequent call returns 400 with already acknowledged message
        res.writeHead(400, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({
          error: {
            code: 400,
            message: 'The purchase has already been acknowledged.'
          }
        }));
        return;
      }
    }
    res.writeHead(404);
    res.end();
  });

  await new Promise<void>(resolve => server.listen(0, '127.0.0.1', resolve));
  const address = server.address() as any;
  const baseUrl = `http://127.0.0.1:${address.port}`;

  try {
    const client = new ProductionGooglePlayBillingApi({
      baseUrl,
      tokenProvider: async () => 'mock-token'
    });

    // Call 1: Success
    await client.acknowledgeSubscription('com.tscanner.app', 'tscanner_vip_yearly', 'tok-ack-test');
    assert.equal(acknowledgeCount, 1);

    // Call 2: Already acknowledged -> throws mapped GooglePlayApiError with 400
    await assert.rejects(
      async () => client.acknowledgeSubscription('com.tscanner.app', 'tscanner_vip_yearly', 'tok-ack-test'),
      (err: GooglePlayApiError) => err.statusCode === 400 && /already been acknowledged/i.test(err.message)
    );
    assert.equal(acknowledgeCount, 2);
  } finally {
    await new Promise<void>(resolve => server.close(() => resolve()));
  }
});

test('Q10: Catalog aliases consistency with Android catalog', () => {
  // Ensure legacy product IDs are supported identically to Android BillingManager
  assert.ok(ALLOWED_SUBSCRIPTION_IDS.has('tscanner_vip_yearly'));
  assert.ok(ALLOWED_SUBSCRIPTION_IDS.has('tscanner_vip_monthly'));
  assert.ok(ALLOWED_SUBSCRIPTION_IDS.has('vip_yearly'), 'Legacy alias vip_yearly must be allowed');
  assert.ok(ALLOWED_SUBSCRIPTION_IDS.has('vip_monthly'), 'Legacy alias vip_monthly must be allowed');

  assert.ok(ALLOWED_INAPP_IDS.has('tscanner_vip_lifetime'));
  assert.ok(ALLOWED_INAPP_IDS.has('vip_lifetime'), 'Legacy alias vip_lifetime must be allowed');

  assert.equal(PRODUCT_ALIASES['vip_yearly'], 'tscanner_vip_yearly');
  assert.equal(PRODUCT_ALIASES['vip_monthly'], 'tscanner_vip_monthly');
  assert.equal(PRODUCT_ALIASES['vip_lifetime'], 'tscanner_vip_lifetime');
});

test('M02: Google Play Transport - Schema strictness and subscriptionsv2 lifecycle mapping', async () => {
  let responseData: any = {};
  let responseStatus = 200;

  const server: Server = createServer((_req, res) => {
    res.writeHead(responseStatus, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(responseData));
  });

  await new Promise<void>(resolve => server.listen(0, '127.0.0.1', resolve));
  const address = server.address() as any;
  const baseUrl = `http://127.0.0.1:${address.port}`;

  try {
    const client = new ProductionGooglePlayBillingApi({
      baseUrl,
      tokenProvider: async () => 'mock-token'
    });

    // 1. Empty JSON on in-app -> rejected with transient error
    responseData = {};
    await assert.rejects(
      async () => client.getInAppProduct('com.tscanner.app', 'tscanner_vip_lifetime', 'sensitive_token_123456789'),
      (err: GooglePlayApiError) => err.isTransient && !err.message.includes('sensitive_token_123456789')
    );

    // 2. Empty JSON on subscription -> rejected with transient error
    responseData = {};
    await assert.rejects(
      async () => client.getSubscription('com.tscanner.app', 'tscanner_vip_yearly', 'sensitive_token_987654321'),
      (err: GooglePlayApiError) => err.isTransient && !err.message.includes('sensitive_token_987654321')
    );

    // 3. Subscriptionsv2 parsing - ACTIVE
    const futureExpiry = new Date(Date.now() + 86400000).toISOString();
    responseData = {
      subscriptionState: 'SUBSCRIPTION_STATE_ACTIVE',
      latestOrderId: 'GPA.V2-1111',
      acknowledgementState: 'ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED',
      lineItems: [{
        productId: 'tscanner_vip_yearly',
        expiryTime: futureExpiry,
        autoRenewingPlan: { autoRenewEnabled: true }
      }],
      externalAccountIdentifiers: { obfuscatedExternalAccountId: 'hash_v2_owner' }
    };
    const activeSub = await client.getSubscription('com.tscanner.app', 'tscanner_vip_yearly', 'tok_v2_active');
    assert.equal(activeSub.autoRenewing, true);
    assert.equal(activeSub.paymentState, 1);
    assert.equal(activeSub.orderId, 'GPA.V2-1111');
    assert.equal(activeSub.obfuscatedExternalAccountId, 'hash_v2_owner');
    assert.equal(activeSub.subscriptionState, 'SUBSCRIPTION_STATE_ACTIVE');

    // 4. Subscriptionsv2 parsing - IN_GRACE_PERIOD
    responseData.subscriptionState = 'SUBSCRIPTION_STATE_IN_GRACE_PERIOD';
    const graceSub = await client.getSubscription('com.tscanner.app', 'tscanner_vip_yearly', 'tok_v2_grace');
    assert.equal(graceSub.subscriptionState, 'SUBSCRIPTION_STATE_IN_GRACE_PERIOD');

    // 5. Subscriptionsv2 parsing - CANCELED
    responseData.subscriptionState = 'SUBSCRIPTION_STATE_CANCELED';
    const canceledSub = await client.getSubscription('com.tscanner.app', 'tscanner_vip_yearly', 'tok_v2_cancel');
    assert.equal(canceledSub.autoRenewing, false);
    assert.equal(canceledSub.cancelReason, 1);

    // 6. Subscriptionsv2 parsing - ON_HOLD and PAUSED
    responseData.subscriptionState = 'SUBSCRIPTION_STATE_ON_HOLD';
    const onHoldSub = await client.getSubscription('com.tscanner.app', 'tscanner_vip_yearly', 'tok_v2_hold');
    assert.equal(onHoldSub.subscriptionState, 'SUBSCRIPTION_STATE_ON_HOLD');
    assert.notEqual(onHoldSub.paymentState, 0, 'ON_HOLD must not be conflated with pending payment');

    responseData.subscriptionState = 'SUBSCRIPTION_STATE_PAUSED';
    const pausedSub = await client.getSubscription('com.tscanner.app', 'tscanner_vip_yearly', 'tok_v2_pause');
    assert.equal(pausedSub.autoRenewing, false);

    // 7. Malformed subscriptionsv2 - empty lineItems
    responseData = { subscriptionState: 'SUBSCRIPTION_STATE_ACTIVE', lineItems: [] };
    await assert.rejects(
      async () => client.getSubscription('com.tscanner.app', 'tscanner_vip_yearly', 'tok_bad'),
      (err: GooglePlayApiError) => err.isTransient && /lineItems/i.test(err.message)
    );

    // 8. 404 masks raw token in error message
    responseStatus = 404;
    responseData = { error: { message: 'Not found' } };
    await assert.rejects(
      async () => client.getSubscription('com.tscanner.app', 'tscanner_vip_yearly', 'raw_secret_token_123456789'),
      (err: GooglePlayApiError) => {
        assert.equal(err.statusCode, 404);
        assert.ok(!err.message.includes('raw_secret_token_123456789'), 'Token must be masked');
        assert.ok(err.message.includes('raw_...6789'), 'Masked pattern must appear');
        return true;
      }
    );
  } finally {
    await new Promise<void>(resolve => server.close(() => resolve()));
  }
});
