import { defaultLogger, type Logger, BillingVerifierService, ALLOWED_SUBSCRIPTION_IDS } from './verifier.ts';
import { EntitlementStore } from './store.ts';
import { GooglePlayApiError, type GooglePlayBillingApi, type GooglePlaySubscriptionResult } from './googlePlayClient.ts';
import type { BillingEntitlement, EntitlementState } from './types.ts';

export const RTDN_SUBSCRIPTION_TYPES = {
  RECOVERED: 1,
  RENEWED: 2,
  CANCELED: 3,
  PURCHASED: 4,
  ON_HOLD: 5,
  IN_GRACE_PERIOD: 6,
  RESTARTED: 7,
  PRICE_CHANGE_CONFIRMED: 8,
  DEFERRED: 9,
  PAUSED: 10,
  PAUSE_SCHEDULE_CHANGED: 11,
  REVOKED: 12,
  EXPIRED: 13
} as const;

export interface DeveloperNotificationPayload {
  version: string;
  packageName: string;
  eventTimeMillis: number | string;
  subscriptionNotification?: {
    version: string;
    notificationType: number;
    purchaseToken: string;
    subscriptionId?: string;
  };
  oneTimeProductNotification?: {
    version: string;
    notificationType: number;
    purchaseToken: string;
    sku: string;
  };
  voidedPurchaseNotification?: {
    purchaseToken: string;
    orderId?: string;
    productType?: number;
    refundType?: number;
  };
  testNotification?: {
    version: string;
  };
}

export interface PubSubPushBody {
  message: {
    data: string; // Base64 encoded JSON of DeveloperNotificationPayload
    messageId: string;
    publishTime: string;
  };
  subscription?: string;
}

export interface RtdnProcessResult {
  status: 'PROCESSED' | 'SKIPPED_STALE' | 'IGNORED_TEST' | 'PACKAGE_MISMATCH' | 'TOKEN_UNKNOWN' | 'ERROR';
  notificationType?: number;
  purchaseToken?: string;
  newState?: EntitlementState;
  message?: string;
}

export class RtdnHandler {
  private lastProcessedEventTimes = new Map<string, number>();
  private googlePlayApi: GooglePlayBillingApi;
  private store: EntitlementStore;
  private logger: Logger;
  private verifier: BillingVerifierService;

  constructor(
    googlePlayApi: GooglePlayBillingApi,
    store: EntitlementStore,
    logger: Logger = defaultLogger,
    verifier?: BillingVerifierService
  ) {
    this.googlePlayApi = googlePlayApi;
    this.store = store;
    this.logger = logger;
    this.verifier = verifier || new BillingVerifierService(googlePlayApi, store, logger);
  }

  /**
   * Processes a Pub/Sub push notification containing an RTDN payload.
   */
  async handlePushNotification(body: PubSubPushBody): Promise<RtdnProcessResult> {
    if (!body?.message?.data) {
      return { status: 'ERROR', message: 'Missing Pub/Sub message data' };
    }

    let payload: DeveloperNotificationPayload;
    try {
      const decodedJson = Buffer.from(body.message.data, 'base64').toString('utf-8');
      payload = JSON.parse(decodedJson);
    } catch (e) {
      this.logger.error('Failed to decode Base64 RTDN payload', e);
      return { status: 'ERROR', message: 'Invalid payload encoding' };
    }

    return this.processDeveloperNotification(payload);
  }

  /**
   * Processes the parsed DeveloperNotificationPayload.
   */
  async processDeveloperNotification(payload: DeveloperNotificationPayload): Promise<RtdnProcessResult> {
    if (payload.testNotification) {
      this.logger.info('Received Google Play test RTDN notification');
      return { status: 'IGNORED_TEST', message: 'Test notification acknowledged' };
    }

    if (payload.packageName !== 'com.tscanner.app') {
      this.logger.warn(`Ignored notification for foreign package: ${payload.packageName}`);
      return { status: 'PACKAGE_MISMATCH', message: 'Foreign package' };
    }

    const sub = payload.subscriptionNotification;
    const oneTime = (payload as any).oneTimeProductNotification;
    const voided = payload.voidedPurchaseNotification;

    if (!sub && !oneTime && !voided) {
      return { status: 'PROCESSED', message: 'Non-billing notification noted' };
    }

    if (voided) {
      const { purchaseToken, orderId, productType, refundType } = voided;
      if (!purchaseToken) {
        return { status: 'ERROR', message: 'Missing purchaseToken in voided notification' };
      }
      const eventTime = Number(payload.eventTimeMillis) || Date.now();
      const lastEventTime = this.lastProcessedEventTimes.get(purchaseToken) || 0;
      if (eventTime <= lastEventTime) {
        this.logger.warn(`Skipping stale or out-of-order voided RTDN for token ${purchaseToken.slice(0, 6)}...`);
        return { status: 'SKIPPED_STALE', purchaseToken, message: 'Older or duplicate voided notification ignored' };
      }

      const existingRecord = await this.store.getRecordByToken(purchaseToken);
      if (!existingRecord) {
        this.logger.warn(`RTDN received for unknown voided token ${purchaseToken.slice(0, 6)}...`);
        return { status: 'TOKEN_UNKNOWN', purchaseToken, message: 'Token not bound to any app user yet' };
      }

      let shouldRevoke = refundType === 1 || refundType === undefined;
      if (this.googlePlayApi) {
        try {
          if (existingRecord.entitlement.productType === 'inapp' || productType === 2) {
            const inAppResult = await this.googlePlayApi.getInAppProduct(
              payload.packageName,
              existingRecord.productId,
              purchaseToken
            );
            // In-app purchaseState: 0: Purchased, 1: Canceled, 2: Pending
            if (inAppResult.purchaseState === 1) {
              shouldRevoke = true;
            } else if (inAppResult.purchaseState === 0 && refundType !== 1) {
              shouldRevoke = false;
            }
          }
        } catch (err: unknown) {
          if (err instanceof GooglePlayApiError && (err.isTransient || err.statusCode >= 500 || err.statusCode === 429)) {
            this.logger.warn(`Transient Google Play API error when verifying voided purchase: ${err.message}`);
            return {
              status: 'ERROR',
              purchaseToken,
              message: 'Transient error checking Google Play authority for voided purchase'
            };
          }
          this.logger.warn(`Non-transient error checking Google Play authority for voided purchase:`, err);
        }
      }

      if (shouldRevoke) {
        const updatedEntitlement: BillingEntitlement = {
          ...existingRecord.entitlement,
          state: 'REVOKED',
          verifiedAtMillis: Date.now(),
          snapshotVersion: existingRecord.latestSnapshotVersion + 1
        };

        const bindResult = await this.store.bindOrUpdate(
          existingRecord.ownerAppUserId,
          purchaseToken,
          updatedEntitlement,
          existingRecord.obfuscatedAccountId,
          {
            eventTimeMillis: eventTime,
            expectedVersion: existingRecord.latestSnapshotVersion
          }
        );

        if (!bindResult.success) {
          if (bindResult.staleIgnored) {
            return {
              status: 'SKIPPED_STALE',
              purchaseToken,
              message: 'Voided update ignored by store as stale'
            };
          }
          if (bindResult.casConflict) {
            this.logger.warn(`CAS conflict revoking voided token ${purchaseToken.slice(0, 6)}...`);
            return {
              status: 'ERROR',
              purchaseToken,
              message: 'CAS conflict updating voided purchase'
            };
          }
          return {
            status: 'ERROR',
            purchaseToken,
            message: 'Failed to update voided purchase record'
          };
        }

        this.lastProcessedEventTimes.set(
          purchaseToken,
          Math.max(this.lastProcessedEventTimes.get(purchaseToken) || 0, eventTime)
        );

        return {
          status: 'PROCESSED',
          purchaseToken,
          newState: 'REVOKED',
          message: 'Voided purchase revoked'
        };
      }

      this.lastProcessedEventTimes.set(
        purchaseToken,
        Math.max(this.lastProcessedEventTimes.get(purchaseToken) || 0, eventTime)
      );

      return {
        status: 'PROCESSED',
        purchaseToken,
        message: 'Voided notification processed without revocation'
      };
    }

    if (oneTime) {
      const { purchaseToken, notificationType } = oneTime;
      const eventTime = Number(payload.eventTimeMillis) || Date.now();
      const lastEventTime = this.lastProcessedEventTimes.get(purchaseToken) || 0;
      if (eventTime <= lastEventTime) {
        this.logger.warn(`Skipping stale or out-of-order one-time RTDN for token ${purchaseToken.slice(0, 6)}...`);
        return { status: 'SKIPPED_STALE', purchaseToken, notificationType, message: 'Older or duplicate one-time notification ignored' };
      }

      const existingRecord = await this.store.getRecordByToken(purchaseToken);
      if (!existingRecord) {
        this.logger.warn(`RTDN received for unknown one-time token ${purchaseToken.slice(0, 6)}...`);
        return { status: 'TOKEN_UNKNOWN', purchaseToken, notificationType, message: 'Token not bound to any app user yet' };
      }

      if (notificationType === 2) {
        const updatedEntitlement = {
          ...existingRecord.entitlement,
          state: 'REVOKED' as EntitlementState,
          verifiedAtMillis: Date.now(),
          snapshotVersion: existingRecord.latestSnapshotVersion + 1
        };
        await this.store.bindOrUpdate(
          existingRecord.ownerAppUserId,
          purchaseToken,
          updatedEntitlement,
          existingRecord.obfuscatedAccountId,
          { eventTimeMillis: eventTime }
        );
        this.lastProcessedEventTimes.set(
          purchaseToken,
          Math.max(this.lastProcessedEventTimes.get(purchaseToken) || 0, eventTime)
        );
        return {
          status: 'PROCESSED',
          purchaseToken,
          notificationType,
          newState: 'REVOKED'
        };
      }

      this.lastProcessedEventTimes.set(
        purchaseToken,
        Math.max(this.lastProcessedEventTimes.get(purchaseToken) || 0, eventTime)
      );
      return { status: 'PROCESSED', purchaseToken, notificationType };
    }

    const { purchaseToken, subscriptionId, notificationType } = sub;
    const eventTime = Number(payload.eventTimeMillis) || Date.now();

    // 1. Stale / Out of order duplicate guard
    const lastEventTime = this.lastProcessedEventTimes.get(purchaseToken) || 0;
    if (eventTime <= lastEventTime) {
      this.logger.warn(
        `Skipping stale or out-of-order RTDN for token ${purchaseToken.slice(0, 6)}... (eventTime=${eventTime} <= last=${lastEventTime})`
      );
      return {
        status: 'SKIPPED_STALE',
        purchaseToken,
        notificationType,
        message: 'Older or duplicate notification ignored'
      };
    }

    // 2. Lookup existing owner record and process with CAS version guard
    const MAX_RTDN_CAS_RETRIES = 3;
    for (let attempt = 0; attempt <= MAX_RTDN_CAS_RETRIES; attempt++) {
      const existingRecord = await this.store.getRecordByToken(purchaseToken);
      if (!existingRecord) {
        let playResult: GooglePlaySubscriptionResult;
        try {
          playResult = await this.googlePlayApi.getSubscription('com.tscanner.app', subscriptionId || '', purchaseToken);
        } catch (err) {
          this.logger.error(`Error querying Play API for unbound RTDN token ${purchaseToken.slice(0, 6)}...:`, err);
          if (err instanceof GooglePlayApiError && !err.isTransient && err.statusCode === 404) {
            return {
              status: 'TOKEN_UNKNOWN',
              purchaseToken,
              notificationType,
              message: 'Token not bound and not recognized by Google Play'
            };
          }
          return {
            status: 'ERROR',
            purchaseToken,
            notificationType,
            message: 'Failed to query Play API for unbound token'
          };
        }

        // If Play returned a linkedPurchaseToken, check if the linked receipt is bound to a user
        if (playResult.linkedPurchaseToken && playResult.linkedPurchaseToken !== purchaseToken) {
          const linkedRecord = await this.store.getRecordByToken(playResult.linkedPurchaseToken);
          if (linkedRecord) {
            const ownerAppUserId = linkedRecord.ownerAppUserId;
            const expectedHash = this.verifier.computeObfuscatedAccountId(ownerAppUserId);
            if (playResult.obfuscatedExternalAccountId && playResult.obfuscatedExternalAccountId !== expectedHash) {
              this.logger.warn(`ObfuscatedAccountId mismatch on linked token for unbound RTDN token ${purchaseToken.slice(0, 6)}...`);
              return {
                status: 'ERROR',
                purchaseToken,
                notificationType,
                message: 'ObfuscatedAccountId mismatch with linked owner'
              };
            }

            // Resolve product identity from lineItems, or notification subscriptionId, or fallback
            const resolvedNewProductId = playResult.lineItemProductId || subscriptionId || linkedRecord.productId;
            if (!resolvedNewProductId || !ALLOWED_SUBSCRIPTION_IDS.has(resolvedNewProductId)) {
              this.logger.warn(`Disallowed or unknown product ID for new linked token: ${resolvedNewProductId}`);
              return {
                status: 'ERROR',
                purchaseToken,
                notificationType,
                message: `Product ${resolvedNewProductId} not allowed in catalog`
              };
            }

            // Refresh the linked old receipt via authoritative resolver
            const linkedOutcome = await this.verifier.resolveLinkedSubscriptionToken(
              'com.tscanner.app',
              linkedRecord.productId || subscriptionId || '',
              playResult.linkedPurchaseToken,
              ownerAppUserId,
              new Set([purchaseToken])
            );

            if (linkedOutcome.status === 'UNRESOLVED_TRANSIENT') {
              return {
                status: 'ERROR',
                purchaseToken,
                notificationType,
                message: 'Transient error refreshing linked token'
              };
            }

            // Determine authoritative state for the new purchase
            let newEntitlementState: EntitlementState;
            let autoRenewing = Boolean(playResult.autoRenewing);
            let requiresAck = false;

            if (playResult.subscriptionState) {
              switch (playResult.subscriptionState) {
                case 'SUBSCRIPTION_STATE_PENDING_PURCHASE_CANCELED':
                  newEntitlementState = 'REVOKED';
                  autoRenewing = false;
                  break;
                case 'SUBSCRIPTION_STATE_PENDING':
                  newEntitlementState = 'PENDING_PAYMENT';
                  autoRenewing = false;
                  break;
                case 'SUBSCRIPTION_STATE_ACTIVE':
                  if (playResult.expiryTimeMillis && Date.now() >= playResult.expiryTimeMillis) {
                    newEntitlementState = 'EXPIRED';
                  } else {
                    newEntitlementState = autoRenewing ? 'VERIFIED_ACTIVE' : 'CANCELED_ACTIVE';
                    if (playResult.acknowledgementState === 0) {
                      requiresAck = true;
                    }
                  }
                  break;
                case 'SUBSCRIPTION_STATE_IN_GRACE_PERIOD':
                  newEntitlementState = 'IN_GRACE_PERIOD';
                  break;
                case 'SUBSCRIPTION_STATE_ON_HOLD':
                  newEntitlementState = 'ON_HOLD';
                  break;
                case 'SUBSCRIPTION_STATE_PAUSED':
                  newEntitlementState = 'PAUSED';
                  break;
                case 'SUBSCRIPTION_STATE_CANCELED':
                  if (playResult.expiryTimeMillis && Date.now() >= playResult.expiryTimeMillis) {
                    newEntitlementState = 'EXPIRED';
                  } else {
                    newEntitlementState = 'CANCELED_ACTIVE';
                  }
                  break;
                case 'SUBSCRIPTION_STATE_EXPIRED':
                  newEntitlementState = 'EXPIRED';
                  break;
                default:
                  return {
                    status: 'ERROR',
                    purchaseToken,
                    notificationType,
                    message: `Unknown subscription state: ${playResult.subscriptionState}`
                  };
              }
            } else {
              if (playResult.paymentState === 0) {
                newEntitlementState = 'PENDING_PAYMENT';
              } else if (Date.now() >= playResult.expiryTimeMillis) {
                newEntitlementState = 'EXPIRED';
              } else if (!playResult.autoRenewing) {
                newEntitlementState = 'CANCELED_ACTIVE';
              } else {
                newEntitlementState = 'VERIFIED_ACTIVE';
                if (playResult.acknowledgementState === 0) {
                  requiresAck = true;
                }
              }
            }

            const newEntitlement: BillingEntitlement = {
              id: `GOOGLE_PLAY_SUBSCRIPTION_${purchaseToken}`,
              ownerAppUserId,
              productId: resolvedNewProductId,
              productType: 'subs',
              purchaseToken,
              orderId: playResult.orderId,
              source: 'GOOGLE_PLAY_SUBSCRIPTION',
              state: newEntitlementState,
              purchaseTimeMillis: playResult.startTimeMillis || Date.now(),
              expiryTimeMillis: playResult.expiryTimeMillis,
              autoRenewing,
              verifiedAtMillis: Date.now(),
              snapshotVersion: 1
            };

            const bindResult = await this.store.bindOrUpdate(
              ownerAppUserId,
              purchaseToken,
              newEntitlement,
              linkedRecord.obfuscatedAccountId || expectedHash,
              { eventTimeMillis: eventTime, expectedVersion: null, expectedAbsent: true }
            );

            if (!bindResult.success) {
              if (bindResult.casConflict) {
                // Concurrent worker may have bound this token during Play query
                const concurrentRecord = await this.store.getRecordByToken(purchaseToken);
                if (concurrentRecord) {
                  if (concurrentRecord.ownerAppUserId !== ownerAppUserId) {
                    this.logger.warn(`Ownership conflict: token ${purchaseToken.slice(0, 6)}... bound by concurrent worker to ${concurrentRecord.ownerAppUserId}`);
                    return {
                      status: 'ERROR',
                      purchaseToken,
                      notificationType,
                      message: `Token already bound to different owner: ${concurrentRecord.ownerAppUserId}`
                    };
                  }
                  // Same owner: attempt update using concurrent record's version
                  const retryBind = await this.store.bindOrUpdate(
                    ownerAppUserId,
                    purchaseToken,
                    {
                      ...newEntitlement,
                      snapshotVersion: concurrentRecord.latestSnapshotVersion + 1
                    },
                    concurrentRecord.obfuscatedAccountId || expectedHash,
                    { eventTimeMillis: eventTime, expectedVersion: concurrentRecord.latestSnapshotVersion }
                  );
                  if (!retryBind.success) {
                    if (retryBind.staleIgnored) {
                      return { status: 'SKIPPED_STALE', purchaseToken, notificationType, message: 'Older event rejected by store' };
                    }
                    this.logger.error(`CAS conflict retry exhausted for token ${purchaseToken.slice(0, 6)}...`);
                    return { status: 'ERROR', purchaseToken, notificationType, message: 'CAS conflict in unknown token binding' };
                  }
                } else {
                  return { status: 'ERROR', purchaseToken, notificationType, message: 'CAS conflict in unknown token binding' };
                }
              } else if (bindResult.conflictOwner) {
                this.logger.warn(`Ownership conflict binding new linked token ${purchaseToken.slice(0, 6)}... to ${bindResult.conflictOwner}`);
                return {
                  status: 'ERROR',
                  purchaseToken,
                  notificationType,
                  message: `Token already bound to different owner: ${bindResult.conflictOwner}`
                };
              } else if (bindResult.staleIgnored) {
                return {
                  status: 'SKIPPED_STALE',
                  purchaseToken,
                  notificationType,
                  message: 'Older event rejected by store'
                };
              } else {
                this.logger.warn(`Database rejected unknown linked bind for token ${purchaseToken.slice(0, 6)}...`);
                return {
                  status: 'ERROR',
                  purchaseToken,
                  notificationType,
                  message: 'Failed to bind new entitlement'
                };
              }
            }

            if (requiresAck) {
              try {
                await this.store.enqueueAckRetry({
                  purchaseToken,
                  productId: resolvedNewProductId,
                  productType: 'subs',
                  ownerAppUserId
                });
              } catch (err) {
                this.logger.error(`Failed to enqueue ack retry for token ${purchaseToken.slice(0, 6)}...:`, err);
                return {
                  status: 'ERROR',
                  purchaseToken,
                  notificationType,
                  message: 'Failed to persist required acknowledgement task'
                };
              }
            }

            this.lastProcessedEventTimes.set(
              purchaseToken,
              Math.max(this.lastProcessedEventTimes.get(purchaseToken) || 0, eventTime)
            );

            return {
              status: 'PROCESSED',
              purchaseToken,
              notificationType,
              newState: newEntitlementState
            };
          }
        }

        this.logger.warn(`RTDN received for unknown token ${purchaseToken.slice(0, 6)}... (not yet verified by any user)`);
        return {
          status: 'TOKEN_UNKNOWN',
          purchaseToken,
          notificationType,
          message: 'Token not bound to any app user yet'
        };
      }
      const expectedVersion = existingRecord.latestSnapshotVersion;

      // 3. Query authoritative Play API for fresh status and expiration
      try {
        const playResult = await this.googlePlayApi.getSubscription('com.tscanner.app', existingRecord.productId || subscriptionId || '', purchaseToken);

        let newState: EntitlementState;
        let autoRenewing = playResult.autoRenewing;

        switch (notificationType) {
          case RTDN_SUBSCRIPTION_TYPES.RENEWED:
          case RTDN_SUBSCRIPTION_TYPES.RECOVERED:
          case RTDN_SUBSCRIPTION_TYPES.PURCHASED:
            newState = 'VERIFIED_ACTIVE';
            break;

          case RTDN_SUBSCRIPTION_TYPES.CANCELED:
            // User turned off auto-renew; still active until current term expires
            newState = 'CANCELED_ACTIVE';
            autoRenewing = false;
            break;

          case RTDN_SUBSCRIPTION_TYPES.IN_GRACE_PERIOD:
            newState = 'IN_GRACE_PERIOD';
            break;

          case RTDN_SUBSCRIPTION_TYPES.ON_HOLD:
            newState = 'ON_HOLD';
            break;

          case RTDN_SUBSCRIPTION_TYPES.PAUSED:
            newState = 'PAUSED';
            break;

          case RTDN_SUBSCRIPTION_TYPES.REVOKED:
            newState = 'REVOKED';
            break;

          case RTDN_SUBSCRIPTION_TYPES.EXPIRED:
            newState = 'EXPIRED';
            break;

          default:
            newState = existingRecord.entitlement.state;
            break;
        }

        // Authoritative mapping from Subscriptions V2
        if (playResult.subscriptionState) {
          switch (playResult.subscriptionState) {
            case 'SUBSCRIPTION_STATE_PENDING_PURCHASE_CANCELED':
              newState = 'REVOKED';
              autoRenewing = false;
              break;
            case 'SUBSCRIPTION_STATE_EXPIRED':
              newState = 'EXPIRED';
              break;
            case 'SUBSCRIPTION_STATE_IN_GRACE_PERIOD':
              newState = 'IN_GRACE_PERIOD';
              break;
            case 'SUBSCRIPTION_STATE_ON_HOLD':
              newState = 'ON_HOLD';
              break;
            case 'SUBSCRIPTION_STATE_PAUSED':
              newState = 'PAUSED';
              break;
            case 'SUBSCRIPTION_STATE_CANCELED':
              if (playResult.expiryTimeMillis && Date.now() >= playResult.expiryTimeMillis) {
                newState = 'EXPIRED';
              } else {
                newState = 'CANCELED_ACTIVE';
              }
              break;
            case 'SUBSCRIPTION_STATE_ACTIVE':
              if (playResult.expiryTimeMillis && Date.now() >= playResult.expiryTimeMillis) {
                newState = 'EXPIRED';
              } else {
                newState = playResult.autoRenewing ? 'VERIFIED_ACTIVE' : 'CANCELED_ACTIVE';
              }
              break;
          }
        } else if (notificationType !== RTDN_SUBSCRIPTION_TYPES.REVOKED && playResult.expiryTimeMillis && Date.now() >= playResult.expiryTimeMillis) {
          newState = 'EXPIRED';
        }

        // Re-check event ordering post-await to guard against out-of-order completions
        const currentLatestEvent = this.lastProcessedEventTimes.get(purchaseToken) || 0;
        if (eventTime <= currentLatestEvent) {
          this.logger.warn(
            `Skipping out-of-order RTDN completion for token ${purchaseToken.slice(0, 6)}... (eventTime=${eventTime} <= currentLatest=${currentLatestEvent})`
          );
          return {
            status: 'SKIPPED_STALE',
            purchaseToken,
            notificationType,
            message: 'Older notification completed after newer one'
          };
        }

        // 4. Resolve linked purchase token BEFORE committing main entitlement and event watermark.
        // This ensures durable completion: if linked token refresh fails (e.g. Play 503),
        // the event is not recorded as completed, allowing identical-event retries to finish linked work.
        if (playResult.linkedPurchaseToken && playResult.linkedPurchaseToken !== purchaseToken) {
          const linkedOutcome = await this.verifier.resolveLinkedSubscriptionToken(
            'com.tscanner.app',
            existingRecord.productId || subscriptionId,
            playResult.linkedPurchaseToken,
            existingRecord.ownerAppUserId,
            new Set([purchaseToken])
          );
          if (linkedOutcome.status === 'UNRESOLVED_TRANSIENT') {
            return {
              status: 'ERROR',
              purchaseToken,
              notificationType,
              message: 'Transient error refreshing linked token'
            };
          }
        }

        // 5. Update entitlement in store with authoritative expiration and CAS version guard
        const updatedEntitlement = {
          ...existingRecord.entitlement,
          state: newState,
          autoRenewing,
          expiryTimeMillis: playResult.expiryTimeMillis,
          verifiedAtMillis: Date.now(),
          snapshotVersion: existingRecord.latestSnapshotVersion + 1
        };

        const bindRes = await this.store.bindOrUpdate(
          existingRecord.ownerAppUserId,
          purchaseToken,
          updatedEntitlement,
          existingRecord.obfuscatedAccountId,
          { eventTimeMillis: eventTime, expectedVersion }
        );

        if (!bindRes.success) {
          if (bindRes.casConflict) {
            if (attempt < MAX_RTDN_CAS_RETRIES) {
              this.logger.warn(`CAS conflict in RTDN on token ${purchaseToken.slice(0, 6)}..., retrying attempt ${attempt + 1}/${MAX_RTDN_CAS_RETRIES}...`);
              continue;
            }
            this.logger.error(`CAS conflict retry budget exhausted in RTDN for token ${purchaseToken.slice(0, 6)}...`);
            return {
              status: 'ERROR',
              purchaseToken,
              notificationType,
              message: 'Concurrent modification conflict in RTDN processing'
            };
          }

          this.logger.warn(`Database rejected RTDN update for token ${purchaseToken.slice(0, 6)}...`);
          return {
            status: 'ERROR',
            purchaseToken,
            notificationType,
            message: 'Database update rejected'
          };
        }

        if (bindRes.staleIgnored) {
          this.logger.warn(`Database rejected stale RTDN update for token ${purchaseToken.slice(0, 6)}...`);
          return {
            status: 'SKIPPED_STALE',
            purchaseToken,
            notificationType,
            message: 'Older event rejected by store'
          };
        }

        this.lastProcessedEventTimes.set(
          purchaseToken,
          Math.max(this.lastProcessedEventTimes.get(purchaseToken) || 0, eventTime)
        );

        this.logger.info(
          `Updated entitlement via RTDN: token=${purchaseToken.slice(0, 6)}..., type=${notificationType}, newState=${newState}, expiry=${playResult.expiryTimeMillis}`
        );

        return {
          status: 'PROCESSED',
          purchaseToken,
          notificationType,
          newState
        };
      } catch (err) {
        this.logger.error(`Error querying Play API during RTDN handling:`, err);
        return {
          status: 'ERROR',
          purchaseToken,
          notificationType,
          message: 'Failed to query Play API; user entitlement preserved safely.'
        };
      }
    }

    return {
      status: 'ERROR',
      purchaseToken,
      notificationType,
      message: 'Concurrent modification conflict in RTDN processing'
    };
  }

  clear(): void {
    this.lastProcessedEventTimes.clear();
  }
}
