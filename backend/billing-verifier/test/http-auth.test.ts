process.env.NODE_ENV = 'test';
import test from 'node:test';
import assert from 'node:assert/strict';
import { generateKeyPairSync } from 'node:crypto';
import { createHttpServer } from '../src/index.ts';
import { EntitlementStore } from '../src/store.ts';
import { AuthService, createTestJwt, createTestRsaJwt } from '../src/auth.ts';
import type { BillingEntitlement, GooglePlayBillingApi, ProductType, SubscriptionPurchase, InAppPurchase } from '../src/types.ts';

const SECRET = 'test-jwt-secret-key-12345';

class MockGoogleApi implements GooglePlayBillingApi {
  calls: Array<{ type: string; token: string }> = [];

  async getSubscription(packageName: string, subscriptionId: string, token: string) {
    this.calls.push({ type: 'getSubscription', token });
    return {
      acknowledgementState: 1,
      expiryTimeMillis: Date.now() + 3600000,
      paymentState: 1,
      autoRenewing: true,
      orderId: `GPA.TEST-${token}`
    };
  }

  async getInAppProduct(packageName: string, productId: string, token: string) {
    this.calls.push({ type: 'getInAppProduct', token });
    return {
      purchaseState: 0,
      consumptionState: 0,
      purchaseTimeMillis: Date.now() - 10000,
      orderId: `GPA.TEST-${token}`
    };
  }

  async acknowledgeSubscription(packageName: string, subscriptionId: string, token: string) {
    this.calls.push({ type: 'acknowledgeSubscription', token });
  }

  async acknowledgeInAppProduct(packageName: string, productId: string, token: string) {
    this.calls.push({ type: 'acknowledgeInAppProduct', token });
  }
}

function testEntitlement(owner: string, token: string): BillingEntitlement {
  return {
    id: token,
    ownerAppUserId: owner,
    productId: 'tscanner_vip_yearly',
    productType: 'subs',
    purchaseToken: token,
    orderId: `GPA.TEST-${token}`,
    source: 'GOOGLE_PLAY_SUBSCRIPTION',
    state: 'VERIFIED_ACTIVE',
    purchaseTimeMillis: Date.now() - 10000,
    expiryTimeMillis: Date.now() + 3600000,
    autoRenewing: true,
    verifiedAtMillis: Date.now(),
    snapshotVersion: 1
  };
}

async function withServer(
  options: {
    authConfig?: Record<string, any>;
    seedStore?: (store: EntitlementStore) => Promise<void>;
  },
  fn: (baseUrl: string, store: EntitlementStore, api: MockGoogleApi) => Promise<void>
) {
  const store = new EntitlementStore();
  const api = new MockGoogleApi();
  if (options.seedStore) {
    await options.seedStore(store);
  }

  const authService = new AuthService({
    jwtSecret: SECRET,
    ...options.authConfig
  });

  const server = createHttpServer({
    store,
    googleApi: api,
    authService
  });

  await new Promise<void>(resolve => server.listen(0, '127.0.0.1', resolve));
  const address = server.address();
  if (!address || typeof address === 'string') {
    throw new Error('Failed to obtain server address');
  }

  const baseUrl = `http://127.0.0.1:${address.port}`;
  try {
    await fn(baseUrl, store, api);
  } finally {
    await new Promise<void>((resolve, reject) => server.close(err => err ? reject(err) : resolve()));
  }
}

test('HTTP Auth: Missing Authorization header returns 401 on /verify, /restore, /acknowledge', async () => {
  await withServer({}, async (baseUrl, store, api) => {
    // 1. /verify
    const resVerify = await fetch(`${baseUrl}/api/v1/billing/verify`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ productId: 'tscanner_vip_yearly', purchaseToken: 'tok_1' })
    });
    assert.equal(resVerify.status, 401);
    const dataVerify = await resVerify.json();
    assert.equal(dataVerify.status, 'UNAUTHORIZED');

    // 2. /restore
    const resRestore = await fetch(`${baseUrl}/api/v1/billing/restore`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ purchases: [] })
    });
    assert.equal(resRestore.status, 401);

    // 3. /acknowledge
    const resAck = await fetch(`${baseUrl}/api/v1/billing/acknowledge`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ purchaseToken: 'tok_1', productId: 'tscanner_vip_yearly', productType: 'subs' })
    });
    assert.equal(resAck.status, 401);

    // Assert zero store mutations and zero Google API calls
    assert.equal(api.calls.length, 0);
  });
});

test('HTTP Auth: Malformed header and malformed token format return 401', async () => {
  await withServer({}, async (baseUrl) => {
    // Basic scheme instead of Bearer
    const resBasic = await fetch(`${baseUrl}/api/v1/billing/verify`, {
      method: 'POST',
      headers: { 'authorization': 'Basic user:pass', 'content-type': 'application/json' },
      body: JSON.stringify({ productId: 'tscanner_vip_yearly', purchaseToken: 'tok_1' })
    });
    assert.equal(resBasic.status, 401);

    // Empty Bearer token
    const resEmptyBearer = await fetch(`${baseUrl}/api/v1/billing/verify`, {
      method: 'POST',
      headers: { 'authorization': 'Bearer   ', 'content-type': 'application/json' },
      body: JSON.stringify({ productId: 'tscanner_vip_yearly', purchaseToken: 'tok_1' })
    });
    assert.equal(resEmptyBearer.status, 401);

    // Not 3 dot-separated segments
    const resMalformed = await fetch(`${baseUrl}/api/v1/billing/verify`, {
      method: 'POST',
      headers: { 'authorization': 'Bearer invalid.token', 'content-type': 'application/json' },
      body: JSON.stringify({ productId: 'tscanner_vip_yearly', purchaseToken: 'tok_1' })
    });
    assert.equal(resMalformed.status, 401);
  });
});

test('HTTP Auth: Disallowed alg=none returns 401', async () => {
  await withServer({}, async (baseUrl) => {
    const noneToken = createTestJwt({ sub: 'user_1' }, SECRET, { alg: 'none', overrideSignature: '' });
    const res = await fetch(`${baseUrl}/api/v1/billing/verify`, {
      method: 'POST',
      headers: { 'authorization': `Bearer ${noneToken}`, 'content-type': 'application/json' },
      body: JSON.stringify({ productId: 'tscanner_vip_yearly', purchaseToken: 'tok_1' })
    });
    assert.equal(res.status, 401);
  });
});

test('HTTP Auth: Expired token returns 401', async () => {
  await withServer({}, async (baseUrl) => {
    const expiredToken = createTestJwt({ sub: 'user_1' }, SECRET, { expiresInSeconds: -60 });
    const res = await fetch(`${baseUrl}/api/v1/billing/restore`, {
      method: 'POST',
      headers: { 'authorization': `Bearer ${expiredToken}`, 'content-type': 'application/json' },
      body: JSON.stringify({ purchases: [] })
    });
    assert.equal(res.status, 401);
    const data = await res.json();
    assert.match(data.message, /expired/i);
  });
});

test('HTTP Auth: Tampered or invalid signature returns 401', async () => {
  await withServer({}, async (baseUrl) => {
    const validToken = createTestJwt({ sub: 'user_1' }, SECRET);
    // Tamper the payload segment
    const parts = validToken.split('.');
    const tamperedToken = `${parts[0]}.eyJzdWIiOiJoYWNrZXIifQ.${parts[2]}`;

    const res = await fetch(`${baseUrl}/api/v1/billing/restore`, {
      method: 'POST',
      headers: { 'authorization': `Bearer ${tamperedToken}`, 'content-type': 'application/json' },
      body: JSON.stringify({ purchases: [] })
    });
    assert.equal(res.status, 401);
    const data = await res.json();
    assert.match(data.message, /signature/i);
  });
});

test('HTTP Auth: Audience and Issuer mismatch return 401', async () => {
  await withServer({
    authConfig: {
      allowedIssuers: ['https://accounts.google.com'],
      expectedAudience: 'tscanner-client-id.apps.googleusercontent.com'
    }
  }, async (baseUrl) => {
    // Wrong issuer
    const tokenWrongIss = createTestJwt({
      sub: 'user_1',
      iss: 'https://evil.issuer.test',
      aud: 'tscanner-client-id.apps.googleusercontent.com'
    }, SECRET);
    const resIss = await fetch(`${baseUrl}/api/v1/billing/restore`, {
      method: 'POST',
      headers: { 'authorization': `Bearer ${tokenWrongIss}`, 'content-type': 'application/json' },
      body: JSON.stringify({ purchases: [] })
    });
    assert.equal(resIss.status, 401);

    // Wrong audience
    const tokenWrongAud = createTestJwt({
      sub: 'user_1',
      iss: 'https://accounts.google.com',
      aud: 'other-app-client-id'
    }, SECRET);
    const resAud = await fetch(`${baseUrl}/api/v1/billing/restore`, {
      method: 'POST',
      headers: { 'authorization': `Bearer ${tokenWrongAud}`, 'content-type': 'application/json' },
      body: JSON.stringify({ purchases: [] })
    });
    assert.equal(resAud.status, 401);
  });
});

test('HTTP Auth: Ownership spoofing (User B claiming User A) returns 403 Forbidden', async () => {
  await withServer({}, async (baseUrl, store, api) => {
    const bobToken = createTestJwt({ sub: 'usr_bob' }, SECRET);

    // 1. Bob attempts /verify with ownerAppUserId = usr_alice in body
    const resVerify = await fetch(`${baseUrl}/api/v1/billing/verify`, {
      method: 'POST',
      headers: { 'authorization': `Bearer ${bobToken}`, 'content-type': 'application/json' },
      body: JSON.stringify({
        ownerAppUserId: 'usr_alice',
        productId: 'tscanner_vip_yearly',
        purchaseToken: 'tok_alice_1'
      })
    });
    assert.equal(resVerify.status, 403);
    const dataVerify = await resVerify.json();
    assert.equal(dataVerify.status, 'FORBIDDEN');
    assert.match(dataVerify.message, /Caller identity 'usr_bob' does not match requested ownerAppUserId 'usr_alice'/);

    // 2. Bob attempts /restore with ownerAppUserId = usr_alice in body
    const resRestore = await fetch(`${baseUrl}/api/v1/billing/restore`, {
      method: 'POST',
      headers: { 'authorization': `Bearer ${bobToken}`, 'content-type': 'application/json' },
      body: JSON.stringify({
        ownerAppUserId: 'usr_alice',
        purchases: []
      })
    });
    assert.equal(resRestore.status, 403);
    const dataRestore = await resRestore.json();
    assert.equal(dataRestore.status, 'FORBIDDEN');

    // Store and Google API must remain untouched
    assert.equal(api.calls.length, 0);
  });
});

test('HTTP Auth: Canonical owner derived from principal when ownerAppUserId omitted', async () => {
  await withServer({}, async (baseUrl, store) => {
    const aliceToken = createTestJwt({ sub: 'usr_alice' }, SECRET);

    // Verify without ownerAppUserId in body
    const res = await fetch(`${baseUrl}/api/v1/billing/verify`, {
      method: 'POST',
      headers: { 'authorization': `Bearer ${aliceToken}`, 'content-type': 'application/json' },
      body: JSON.stringify({
        productId: 'tscanner_vip_yearly',
        productType: 'subs',
        purchaseToken: 'tok_alice_clean',
        clientPurchaseTimeMillis: Date.now()
      })
    });
    assert.equal(res.status, 200);
    const data = await res.json();
    assert.equal(data.status, 'SUCCESS');
    assert.equal(data.entitlement.ownerAppUserId, 'usr_alice');

    // Check store record
    const record = await store.getRecordByToken('tok_alice_clean');
    assert.ok(record);
    assert.equal(record.ownerAppUserId, 'usr_alice');
  });
});

test('HTTP Auth: Acknowledge endpoint enforces token ownership and rejects unverified or foreign tokens', async () => {
  await withServer({
    seedStore: async (store) => {
      await store.bindOrUpdate('usr_alice', 'tok_alice_1', testEntitlement('usr_alice', 'tok_alice_1'));
    }
  }, async (baseUrl, store, api) => {
    const bobToken = createTestJwt({ sub: 'usr_bob' }, SECRET);
    const aliceToken = createTestJwt({ sub: 'usr_alice' }, SECRET);

    // 1. Bob attempts to acknowledge Alice's token -> 403 Forbidden
    const resBobOnAlice = await fetch(`${baseUrl}/api/v1/billing/acknowledge`, {
      method: 'POST',
      headers: { 'authorization': `Bearer ${bobToken}`, 'content-type': 'application/json' },
      body: JSON.stringify({
        purchaseToken: 'tok_alice_1',
        productId: 'tscanner_vip_yearly',
        productType: 'subs'
      })
    });
    assert.equal(resBobOnAlice.status, 403);
    const dataBob = await resBobOnAlice.json();
    assert.equal(dataBob.status, 'FORBIDDEN');
    assert.match(dataBob.message, /Purchase token belongs to user 'usr_alice', not caller 'usr_bob'/);

    // 2. Bob attempts to acknowledge an unverified token not in store -> 403/400
    const resUnverified = await fetch(`${baseUrl}/api/v1/billing/acknowledge`, {
      method: 'POST',
      headers: { 'authorization': `Bearer ${bobToken}`, 'content-type': 'application/json' },
      body: JSON.stringify({
        purchaseToken: 'tok_never_verified',
        productId: 'tscanner_vip_yearly',
        productType: 'subs'
      })
    });
    assert.equal(resUnverified.status, 403);

    // 3. Alice acknowledges her own token -> 200 OK
    const resAlice = await fetch(`${baseUrl}/api/v1/billing/acknowledge`, {
      method: 'POST',
      headers: { 'authorization': `Bearer ${aliceToken}`, 'content-type': 'application/json' },
      body: JSON.stringify({
        purchaseToken: 'tok_alice_1',
        productId: 'tscanner_vip_yearly',
        productType: 'subs'
      })
    });
    assert.equal(resAlice.status, 200);
    const dataAlice = await resAlice.json();
    assert.equal(dataAlice.status, 'ACKNOWLEDGED');
  });
});

test('HTTP Auth: RTDN Pub/Sub webhook authentication', async () => {
  await withServer({
    authConfig: {
      pubsubExpectedAudience: 'https://verifier.tscanner.test/api/v1/billing/rtdn',
      pubsubExpectedServiceAccount: 'pubsub-push@tscanner-gcp.iam.gserviceaccount.com',
      pubsubSecretToken: 'rtdn-shared-secret-key-xyz'
    }
  }, async (baseUrl) => {
    // 1. Missing auth -> 401
    const resMissing = await fetch(`${baseUrl}/api/v1/billing/rtdn`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ message: {} })
    });
    assert.equal(resMissing.status, 401);

    // 2. Shared secret auth -> 200
    const resSecret = await fetch(`${baseUrl}/api/v1/billing/rtdn`, {
      method: 'POST',
      headers: {
        'content-type': 'application/json',
        'x-pubsub-secret': 'rtdn-shared-secret-key-xyz'
      },
      body: JSON.stringify({
        message: {
          data: Buffer.from(JSON.stringify({
            version: '1.0',
            packageName: 'com.tscanner.app',
            eventTimeMillis: `${Date.now()}`,
            subscriptionNotification: {
              version: '1.0',
              notificationType: 2,
              purchaseToken: 'tok_rtdn_1',
              subscriptionId: 'tscanner_vip_yearly'
            }
          })).toString('base64')
        }
      })
    });
    assert.equal(resSecret.status, 200);

    // 3. OIDC token with wrong audience -> 403
    const tokenWrongAud = createTestJwt({
      sub: 'pubsub-agent',
      email: 'pubsub-push@tscanner-gcp.iam.gserviceaccount.com',
      aud: 'https://wrong-endpoint.test/api/v1/billing/rtdn'
    }, SECRET);
    const resWrongAud = await fetch(`${baseUrl}/api/v1/billing/rtdn`, {
      method: 'POST',
      headers: {
        'authorization': `Bearer ${tokenWrongAud}`,
        'content-type': 'application/json'
      },
      body: JSON.stringify({ message: {} })
    });
    assert.equal(resWrongAud.status, 403);

    // 4. OIDC token with wrong service account -> 403
    const tokenWrongSa = createTestJwt({
      sub: 'pubsub-agent',
      email: 'hacker-service-account@malicious.iam.gserviceaccount.com',
      aud: 'https://verifier.tscanner.test/api/v1/billing/rtdn'
    }, SECRET);
    const resWrongSa = await fetch(`${baseUrl}/api/v1/billing/rtdn`, {
      method: 'POST',
      headers: {
        'authorization': `Bearer ${tokenWrongSa}`,
        'content-type': 'application/json'
      },
      body: JSON.stringify({ message: {} })
    });
    assert.equal(resWrongSa.status, 403);

    // 5. Valid OIDC token -> 200
    const tokenValid = createTestJwt({
      sub: 'pubsub-agent',
      email: 'pubsub-push@tscanner-gcp.iam.gserviceaccount.com',
      aud: 'https://verifier.tscanner.test/api/v1/billing/rtdn'
    }, SECRET);
    const resValid = await fetch(`${baseUrl}/api/v1/billing/rtdn`, {
      method: 'POST',
      headers: {
        'authorization': `Bearer ${tokenValid}`,
        'content-type': 'application/json'
      },
      body: JSON.stringify({
        message: {
          data: Buffer.from(JSON.stringify({
            version: '1.0',
            packageName: 'com.tscanner.app',
            eventTimeMillis: `${Date.now()}`,
            subscriptionNotification: {
              version: '1.0',
              notificationType: 2,
              purchaseToken: 'tok_rtdn_2',
              subscriptionId: 'tscanner_vip_yearly'
            }
          })).toString('base64')
        }
      })
    });
      assert.equal(resValid.status, 200);
    });
  });

test('HTTP Auth: Google Identity RS256 with JWKS verification and error conditions', async () => {
  const { publicKey, privateKey } = generateKeyPairSync('rsa', { modulusLength: 2048 });
  const jwk = publicKey.export({ format: 'jwk' }) as Record<string, unknown>;
  jwk.kid = 'google-key-alpha';
  jwk.alg = 'RS256';
  jwk.use = 'sig';

  let failJwks = false;
  const CLIENT_ID = 'tscanner-client-id.apps.googleusercontent.com';

  await withServer({
    authConfig: {
      jwtSecret: undefined,
      allowHmacFallback: false,
      googleClientId: CLIENT_ID,
      expectedAudience: CLIENT_ID,
      jwksFetchFn: async () => {
        if (failJwks) throw new Error('JWKS endpoint unavailable');
        return { keys: [jwk] };
      },
      pubsubExpectedAudience: 'https://verifier.tscanner.test/api/v1/billing/rtdn',
      pubsubExpectedServiceAccount: 'pubsub-push@tscanner-gcp.iam.gserviceaccount.com'
    }
  }, async (baseUrl, store, api) => {
    // 1. Synthetic positive RS256 verification
    const validGoogleToken = createTestRsaJwt({
      sub: 'usr_google_123',
      email: 'user@gmail.test',
      email_verified: true,
      aud: CLIENT_ID,
      iss: 'https://accounts.google.com'
    }, privateKey, { kid: 'google-key-alpha', expiresInSeconds: 3600 });

    const resVerify = await fetch(`${baseUrl}/api/v1/billing/verify`, {
      method: 'POST',
      headers: {
        'authorization': `Bearer ${validGoogleToken}`,
        'content-type': 'application/json'
      },
      body: JSON.stringify({
        productId: 'tscanner_vip_yearly',
        productType: 'subs',
        purchaseToken: 'tok_google_rsa_1',
        clientPurchaseTimeMillis: Date.now()
      })
    });
    assert.equal(resVerify.status, 200);
    const dataVerify = await resVerify.json();
    assert.equal(dataVerify.status, 'SUCCESS');
    assert.equal(dataVerify.entitlement.ownerAppUserId, 'usr_google_123');

    // 2. Invalid RSA signature -> 401
    const invalidSigToken = createTestRsaJwt({
      sub: 'usr_google_123',
      aud: CLIENT_ID,
      iss: 'https://accounts.google.com'
    }, privateKey, { kid: 'google-key-alpha', overrideSignature: 'invalid-sig-bytes' });

    const resInvalidSig = await fetch(`${baseUrl}/api/v1/billing/verify`, {
      method: 'POST',
      headers: { 'authorization': `Bearer ${invalidSigToken}`, 'content-type': 'application/json' },
      body: JSON.stringify({ productId: 'tscanner_vip_yearly', purchaseToken: 'tok_1' })
    });
    assert.equal(resInvalidSig.status, 401);

    // 3. Algorithm mismatch (HMAC token against Google RS256 endpoint) -> 401
    const hmacToken = createTestJwt({
      sub: 'usr_google_123',
      aud: CLIENT_ID,
      iss: 'https://accounts.google.com'
    }, 'some-hmac-secret');
    const resAlgMismatch = await fetch(`${baseUrl}/api/v1/billing/verify`, {
      method: 'POST',
      headers: { 'authorization': `Bearer ${hmacToken}`, 'content-type': 'application/json' },
      body: JSON.stringify({ productId: 'tscanner_vip_yearly', purchaseToken: 'tok_1' })
    });
    assert.equal(resAlgMismatch.status, 401);

    // 4. Missing / expired exp -> 401
    const expiredRsaToken = createTestRsaJwt({
      sub: 'usr_google_123',
      aud: CLIENT_ID,
      iss: 'https://accounts.google.com'
    }, privateKey, { kid: 'google-key-alpha', expiresInSeconds: -60 });
    const resExpired = await fetch(`${baseUrl}/api/v1/billing/verify`, {
      method: 'POST',
      headers: { 'authorization': `Bearer ${expiredRsaToken}`, 'content-type': 'application/json' },
      body: JSON.stringify({ productId: 'tscanner_vip_yearly', purchaseToken: 'tok_1' })
    });
    assert.equal(resExpired.status, 401);

    // 5. Wrong issuer / audience -> 401
    const wrongIssToken = createTestRsaJwt({
      sub: 'usr_google_123',
      aud: CLIENT_ID,
      iss: 'https://evil-issuer.test'
    }, privateKey, { kid: 'google-key-alpha', expiresInSeconds: 3600 });
    const resWrongIss = await fetch(`${baseUrl}/api/v1/billing/verify`, {
      method: 'POST',
      headers: { 'authorization': `Bearer ${wrongIssToken}`, 'content-type': 'application/json' },
      body: JSON.stringify({ productId: 'tscanner_vip_yearly', purchaseToken: 'tok_1' })
    });
    assert.equal(resWrongIss.status, 401);

    const wrongAudToken = createTestRsaJwt({
      sub: 'usr_google_123',
      aud: 'other-client-id.apps.googleusercontent.com',
      iss: 'https://accounts.google.com'
    }, privateKey, { kid: 'google-key-alpha', expiresInSeconds: 3600 });
    const resWrongAud = await fetch(`${baseUrl}/api/v1/billing/verify`, {
      method: 'POST',
      headers: { 'authorization': `Bearer ${wrongAudToken}`, 'content-type': 'application/json' },
      body: JSON.stringify({ productId: 'tscanner_vip_yearly', purchaseToken: 'tok_1' })
    });
    assert.equal(resWrongAud.status, 401);

    // 6. Unknown key ID (kid) -> 401
    const unknownKidToken = createTestRsaJwt({
      sub: 'usr_google_123',
      aud: CLIENT_ID,
      iss: 'https://accounts.google.com'
    }, privateKey, { kid: 'non-existent-kid', expiresInSeconds: 3600 });
    const resUnknownKid = await fetch(`${baseUrl}/api/v1/billing/verify`, {
      method: 'POST',
      headers: { 'authorization': `Bearer ${unknownKidToken}`, 'content-type': 'application/json' },
      body: JSON.stringify({ productId: 'tscanner_vip_yearly', purchaseToken: 'tok_1' })
    });
    assert.equal(resUnknownKid.status, 401);

    // 7. Key refresh error -> 401
    failJwks = true;
    const refreshFailToken = createTestRsaJwt({
      sub: 'usr_google_123',
      aud: CLIENT_ID,
      iss: 'https://accounts.google.com'
    }, privateKey, { kid: 'unknown-trigger-refresh', expiresInSeconds: 3600 });
    const resRefreshFail = await fetch(`${baseUrl}/api/v1/billing/verify`, {
      method: 'POST',
      headers: { 'authorization': `Bearer ${refreshFailToken}`, 'content-type': 'application/json' },
      body: JSON.stringify({ productId: 'tscanner_vip_yearly', purchaseToken: 'tok_1' })
    });
    assert.equal(resRefreshFail.status, 401);
    failJwks = false;

    // 8. Pub/Sub Google OIDC RS256 token positive -> 200
    const validPubsubToken = createTestRsaJwt({
      sub: 'pubsub-agent',
      email: 'pubsub-push@tscanner-gcp.iam.gserviceaccount.com',
      email_verified: true,
      aud: 'https://verifier.tscanner.test/api/v1/billing/rtdn',
      iss: 'https://accounts.google.com'
    }, privateKey, { kid: 'google-key-alpha', expiresInSeconds: 3600 });
    const resPubsub = await fetch(`${baseUrl}/api/v1/billing/rtdn`, {
      method: 'POST',
      headers: { 'authorization': `Bearer ${validPubsubToken}`, 'content-type': 'application/json' },
      body: JSON.stringify({
        message: {
          data: Buffer.from(JSON.stringify({
            version: '1.0',
            packageName: 'com.tscanner.app',
            eventTimeMillis: `${Date.now()}`,
            subscriptionNotification: {
              version: '1.0',
              notificationType: 2,
              purchaseToken: 'tok_rtdn_rsa',
              subscriptionId: 'tscanner_vip_yearly'
            }
          })).toString('base64')
        }
      })
    });
    assert.equal(resPubsub.status, 200);

    // 9. Pub/Sub wrong service account -> 403
    const wrongSaToken = createTestRsaJwt({
      sub: 'pubsub-agent',
      email: 'malicious@evil.iam.gserviceaccount.com',
      email_verified: true,
      aud: 'https://verifier.tscanner.test/api/v1/billing/rtdn',
      iss: 'https://accounts.google.com'
    }, privateKey, { kid: 'google-key-alpha', expiresInSeconds: 3600 });
    const resWrongSa = await fetch(`${baseUrl}/api/v1/billing/rtdn`, {
      method: 'POST',
      headers: { 'authorization': `Bearer ${wrongSaToken}`, 'content-type': 'application/json' },
      body: JSON.stringify({ message: {} })
    });
    assert.equal(resWrongSa.status, 403);

    // 10. Pub/Sub unverified email -> 403
    const unverifiedEmailToken = createTestRsaJwt({
      sub: 'pubsub-agent',
      email: 'pubsub-push@tscanner-gcp.iam.gserviceaccount.com',
      email_verified: false,
      aud: 'https://verifier.tscanner.test/api/v1/billing/rtdn',
      iss: 'https://accounts.google.com'
    }, privateKey, { kid: 'google-key-alpha', expiresInSeconds: 3600 });
    const resUnverifiedEmail = await fetch(`${baseUrl}/api/v1/billing/rtdn`, {
      method: 'POST',
      headers: { 'authorization': `Bearer ${unverifiedEmailToken}`, 'content-type': 'application/json' },
      body: JSON.stringify({ message: {} })
    });
    assert.equal(resUnverifiedEmail.status, 403);
  });
});

test('HTTP Auth: Readiness differentiates user auth vs push auth capability', async () => {
  // Case A: Only user auth configured -> 503 NOT_READY because pushAuth is unconfigured
  await withServer({
    authConfig: {
      expectedAudience: 'some-user-client-id',
      pubsubExpectedAudience: undefined,
      pubsubExpectedServiceAccount: undefined
    }
  }, async (baseUrl) => {
    const res = await fetch(`${baseUrl}/readiness`);
    assert.equal(res.status, 503);
    const data = await res.json() as any;
    assert.equal(data.status, 'NOT_READY');
    assert.equal(data.checks.userAuth, 'OK');
    assert.equal(data.checks.pushAuth, 'FAIL_UNCONFIGURED');
  });

  // Case B: Both user auth and push auth configured -> 200 READY
  await withServer({
    authConfig: {
      expectedAudience: 'some-user-client-id',
      pubsubExpectedAudience: 'https://verifier.test/rtdn',
      pubsubExpectedServiceAccount: 'push@gcp.test'
    }
  }, async (baseUrl) => {
    const res = await fetch(`${baseUrl}/readiness`);
    assert.equal(res.status, 200);
    const data = await res.json() as any;
    assert.equal(data.status, 'READY');
    assert.equal(data.checks.userAuth, 'OK');
    assert.equal(data.checks.pushAuth, 'OK');
  });
});
