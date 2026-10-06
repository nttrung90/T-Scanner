process.env.NODE_ENV = 'test';
import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { EntitlementStore } from '../src/store.ts';
import type { BillingEntitlement } from '../src/types.ts';

function createSampleEntitlement(owner: string, token: string, version: number = 1): BillingEntitlement {
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
    snapshotVersion: version
  };
}

function withTempDb(fn: (dbPath: string) => Promise<void>): Promise<void> {
  const dir = mkdtempSync(join(tmpdir(), 'tscanner-storage-test-'));
  const dbPath = join(dir, 'test-storage.db');
  return (async () => {
    try {
      await fn(dbPath);
    } finally {
      try {
        rmSync(dir, { recursive: true, force: true });
      } catch {}
    }
  })();
}

test('Storage Integration: Token ownership and snapshot version survive process restart', async () => {
  await withTempDb(async (dbPath) => {
    // 1. Initial process / store instance writes record to disk DB
    const store1 = new EntitlementStore(dbPath);
    const initialEntitlement = createSampleEntitlement('usr_alice', 'tok_disk_123', 3);
    const bindResult1 = await store1.bindOrUpdate('usr_alice', 'tok_disk_123', initialEntitlement, 'obfuscated_hash_abc');
    assert.equal(bindResult1.success, true);
    assert.equal(bindResult1.record?.latestSnapshotVersion, 3);
    assert.equal(await store1.getUserVersion('usr_alice'), 3);

    // Simulate process shutdown / crash
    store1.close();

    // 2. New process / store instance opens the same disk DB
    const store2 = new EntitlementStore(dbPath);

    // Verify record survived restart
    const record = await store2.getRecordByToken('tok_disk_123');
    assert.ok(record, 'Record must exist after restart');
    assert.equal(record.ownerAppUserId, 'usr_alice');
    assert.equal(record.productId, 'tscanner_vip_yearly');
    assert.equal(record.obfuscatedAccountId, 'obfuscated_hash_abc');
    assert.equal(record.latestSnapshotVersion, 3);
    assert.equal(record.entitlement.state, 'VERIFIED_ACTIVE');

    // Verify owner entitlements query
    const userEntitlements = await store2.getEntitlementsByOwner('usr_alice');
    assert.equal(userEntitlements.length, 1);
    assert.equal(userEntitlements[0].purchaseToken, 'tok_disk_123');

    // Verify user version sequence survived restart
    const versionAfterRestart = await store2.getUserVersion('usr_alice');
    assert.equal(versionAfterRestart, 3, 'User version sequence must survive restart');

    store2.close();
  });
});

test('Storage Integration: Two independent connections enforce unique token ownership', async () => {
  await withTempDb(async (dbPath) => {
    // Connection A (Process 1)
    const connA = new EntitlementStore(dbPath);
    // Connection B (Process 2)
    const connB = new EntitlementStore(dbPath);

    // Process A claims the token for Alice
    const entitlementAlice = createSampleEntitlement('usr_alice', 'tok_shared_pool', 1);
    const resultA = await connA.bindOrUpdate('usr_alice', 'tok_shared_pool', entitlementAlice);
    assert.equal(resultA.success, true);

    // Process B attempts to claim the same token for Bob
    const entitlementBob = createSampleEntitlement('usr_bob', 'tok_shared_pool', 1);
    const resultB = await connB.bindOrUpdate('usr_bob', 'tok_shared_pool', entitlementBob);

    // Must be rejected with conflict
    assert.equal(resultB.success, false);
    assert.equal(resultB.conflictOwner, 'usr_alice');

    // Verify in both connections that Alice remains the sole owner
    const recordA = await connA.getRecordByToken('tok_shared_pool');
    const recordB = await connB.getRecordByToken('tok_shared_pool');
    assert.equal(recordA?.ownerAppUserId, 'usr_alice');
    assert.equal(recordB?.ownerAppUserId, 'usr_alice');

    // Bob must have 0 entitlements
    const bobEntitlements = await connB.getEntitlementsByOwner('usr_bob');
    assert.equal(bobEntitlements.length, 0);

    connA.close();
    connB.close();
  });
});

test('Storage Integration: Monotonic version sequence does not reset across updates and restarts', async () => {
  await withTempDb(async (dbPath) => {
    // Session 1: version increments monotonically v1 -> v2 -> v3
    const store1 = new EntitlementStore(dbPath);
    const e1 = createSampleEntitlement('usr_charlie', 'tok_charlie_1', 1);
    await store1.bindOrUpdate('usr_charlie', 'tok_charlie_1', e1);
    assert.equal(await store1.getUserVersion('usr_charlie'), 1);

    const e2 = createSampleEntitlement('usr_charlie', 'tok_charlie_1', 2);
    await store1.bindOrUpdate('usr_charlie', 'tok_charlie_1', e2);
    assert.equal(await store1.getUserVersion('usr_charlie'), 2);

    const e3 = createSampleEntitlement('usr_charlie', 'tok_charlie_1', 3);
    const res3 = await store1.bindOrUpdate('usr_charlie', 'tok_charlie_1', e3);
    assert.equal(res3.record?.latestSnapshotVersion, 3);
    assert.equal(await store1.getUserVersion('usr_charlie'), 3);

    store1.close();

    // Session 2: new store instance after restart
    const store2 = new EntitlementStore(dbPath);
    assert.equal(await store2.getUserVersion('usr_charlie'), 3);

    // Replay or incoming update with equal/older version does NOT downgrade version
    const eOld = createSampleEntitlement('usr_charlie', 'tok_charlie_1', 1);
    const resOld = await store2.bindOrUpdate('usr_charlie', 'tok_charlie_1', eOld);
    // Monotonic logic increments past previous version: Math.max(3 + 1, 1) = 4
    assert.equal(resOld.record?.latestSnapshotVersion, 4);
    assert.equal(await store2.getUserVersion('usr_charlie'), 4);

    store2.close();
  });
});

test('Storage Integration: Transaction rollbacks on write failure without corrupting state', async () => {
  await withTempDb(async (dbPath) => {
    const store = new EntitlementStore(dbPath);
    const driver = store.getDriver() as any;

    // Seed valid record
    await store.bindOrUpdate('usr_david', 'tok_david_1', createSampleEntitlement('usr_david', 'tok_david_1', 1));
    assert.equal((await store.getEntitlementsByOwner('usr_david')).length, 1);

    // Attempt invalid transaction by breaking SQL statement
    assert.throws(() => {
      driver.db.exec('BEGIN IMMEDIATE');
      driver.db.exec('INSERT INTO token_records (purchase_token) VALUES (NULL)'); // Violates NOT NULL primary key
      driver.db.exec('COMMIT');
    });

    // Check that DB is still intact and previous record is unaffected
    const record = await store.getRecordByToken('tok_david_1');
    assert.ok(record);
    assert.equal(record.ownerAppUserId, 'usr_david');

    store.close();
  });
});

test('Storage Integration: Multiple entitlements under same owner are aggregated', async () => {
  await withTempDb(async (dbPath) => {
    const store = new EntitlementStore(dbPath);

    const sub = createSampleEntitlement('usr_eve', 'tok_eve_sub', 1);
    const inapp: BillingEntitlement = {
      ...createSampleEntitlement('usr_eve', 'tok_eve_inapp', 1),
      productId: 'tscanner_vip_lifetime',
      productType: 'inapp',
      expiryTimeMillis: null
    };

    await store.bindOrUpdate('usr_eve', 'tok_eve_sub', sub);
    await store.bindOrUpdate('usr_eve', 'tok_eve_inapp', inapp);

    const entitlements = await store.getEntitlementsByOwner('usr_eve');
    assert.equal(entitlements.length, 2);
    const tokens = entitlements.map(e => e.purchaseToken).sort();
    assert.deepEqual(tokens, ['tok_eve_inapp', 'tok_eve_sub']);

    store.close();
  });
});

test('Storage Integration: Ack retry queue persists and retrieves pending retries', async () => {
  await withTempDb(async (dbPath) => {
    const store = new EntitlementStore(dbPath);
    const driver = store.getDriver();

    await driver.enqueueAckRetry({
      purchaseToken: 'tok_retry_1',
      productId: 'tscanner_vip_yearly',
      productType: 'subs',
      ownerAppUserId: 'usr_frank'
    });

    // Verify queued item
    const pending1 = await driver.getPendingAckRetries();
    assert.equal(pending1.length, 1);
    assert.equal(pending1[0].purchaseToken, 'tok_retry_1');
    assert.equal(pending1[0].status, 'PENDING');
    assert.equal(pending1[0].attemptCount, 0);

    // Mark failure with backoff
    await driver.markAckFailure('tok_retry_1', 'Google API 503 timeout', 60000);
    const pendingAfterFailure = await driver.getPendingAckRetries();
    assert.equal(pendingAfterFailure.length, 0, 'Should not be immediately due after 60s delay');

    // Simulate restart and success
    store.close();

    const storeAfterRestart = new EntitlementStore(dbPath);
    const driver2 = storeAfterRestart.getDriver();
    await driver2.markAckSuccess('tok_retry_1');

    storeAfterRestart.close();
  });
});
