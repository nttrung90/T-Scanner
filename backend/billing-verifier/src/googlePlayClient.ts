/**
 * Google Play Developer API interface, data representations,
 * and production HTTP transport adapter for Android Publisher v3.
 */
import { createSign } from 'node:crypto';
import { readFileSync, existsSync } from 'node:fs';

export interface GooglePlaySubscriptionResult {
  acknowledgementState: number; // 0: Yet to be acknowledged, 1: Acknowledged
  expiryTimeMillis: number;
  paymentState: number; // 0: Payment pending, 1: Payment received, 2: Free trial, 3: Deferred
  autoRenewing: boolean;
  obfuscatedExternalAccountId?: string;
  cancelReason?: number;
  orderId?: string;
  startTimeMillis?: number;
  linkedPurchaseToken?: string;
  subscriptionState?: string;
  lineItemProductId?: string;
}

export interface GooglePlayInAppResult {
  purchaseState: number; // 0: Purchased, 1: Canceled, 2: Pending
  consumptionState: number; // 0: Yet to be consumed, 1: Consumed
  acknowledgementState: number; // 0: Yet to be acknowledged, 1: Acknowledged
  purchaseTimeMillis: number;
  obfuscatedExternalAccountId?: string;
  orderId?: string;
}

export class MissingCredentialsError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'MissingCredentialsError';
  }
}

export class GooglePlayApiError extends Error {
  readonly statusCode: number;
  readonly isTransient: boolean;

  constructor(
    message: string,
    statusCode: number,
    isTransient: boolean = false
  ) {
    super(message);
    this.name = 'GooglePlayApiError';
    this.statusCode = statusCode;
    this.isTransient = isTransient;
  }
}

export interface GooglePlayBillingApi {
  getSubscription(
    packageName: string,
    subscriptionId: string,
    token: string
  ): Promise<GooglePlaySubscriptionResult>;

  getInAppProduct(
    packageName: string,
    productId: string,
    token: string
  ): Promise<GooglePlayInAppResult>;

  acknowledgeSubscription(
    packageName: string,
    subscriptionId: string,
    token: string
  ): Promise<void>;

  acknowledgeInAppProduct(
    packageName: string,
    productId: string,
    token: string
  ): Promise<void>;
}

export interface GoogleServiceAccountCredentials {
  client_email: string;
  private_key: string;
  token_uri?: string;
}

export interface GooglePlayClientOptions {
  serviceAccountPath?: string;
  serviceAccountJson?: string;
  serviceAccount?: GoogleServiceAccountCredentials;
  baseUrl?: string;
  tokenProvider?: () => Promise<string>;
  fetchFn?: typeof fetch;
}

function base64UrlEncode(data: string | Buffer): string {
  const buf = typeof data === 'string' ? Buffer.from(data, 'utf-8') : data;
  return buf.toString('base64').replace(/=/g, '').replace(/\+/g, '-').replace(/\//g, '_');
}

export function maskToken(token: string): string {
  if (!token) return '***';
  if (token.length <= 8) return '***';
  return `${token.slice(0, 4)}...${token.slice(-4)}`;
}

/**
 * Production HTTP adapter for Google Play Developer API (Android Publisher v3).
 * Communicates with https://androidpublisher.googleapis.com using OAuth2 Service Account
 * credentials or an injected token provider.
 */
export class ProductionGooglePlayBillingApi implements GooglePlayBillingApi {
  private serviceAccount?: GoogleServiceAccountCredentials;
  private baseUrl: string;
  private tokenProvider?: () => Promise<string>;
  private fetchFn: typeof fetch;

  private cachedAccessToken: { token: string; expiresAt: number } | null = null;

  constructor(options: GooglePlayClientOptions = {}) {
    this.baseUrl = (options.baseUrl || process.env.ANDROID_PUBLISHER_BASE_URL || 'https://androidpublisher.googleapis.com').replace(/\/+$/, '');
    this.tokenProvider = options.tokenProvider;
    this.fetchFn = options.fetchFn || globalThis.fetch;

    if (options.serviceAccount) {
      this.serviceAccount = options.serviceAccount;
    } else if (options.serviceAccountJson) {
      try {
        this.serviceAccount = JSON.parse(options.serviceAccountJson);
      } catch (e) {
        throw new Error(`Invalid serviceAccountJson: ${e}`);
      }
    } else {
      const saPath = options.serviceAccountPath || process.env.GOOGLE_APPLICATION_CREDENTIALS;
      if (saPath && existsSync(saPath)) {
        try {
          const content = readFileSync(saPath, 'utf-8');
          this.serviceAccount = JSON.parse(content);
        } catch (e) {
          throw new Error(`Failed to parse credentials file at ${saPath}: ${e}`);
        }
      } else if (process.env.GOOGLE_SERVICE_ACCOUNT_KEY) {
        try {
          const raw = process.env.GOOGLE_SERVICE_ACCOUNT_KEY.trim();
          const decoded = raw.startsWith('{') ? raw : Buffer.from(raw, 'base64').toString('utf-8');
          this.serviceAccount = JSON.parse(decoded);
        } catch (e) {
          throw new Error(`Failed to parse GOOGLE_SERVICE_ACCOUNT_KEY: ${e}`);
        }
      }
    }
  }

  /**
   * Retrieves or refreshes Google OAuth2 access token for Android Publisher API.
   */
  private async getAccessToken(): Promise<string> {
    if (this.tokenProvider) {
      return this.tokenProvider();
    }

    if (!this.serviceAccount) {
      throw new MissingCredentialsError(
        'GOOGLE_APPLICATION_CREDENTIALS environment variable is not configured. ' +
          'Production Google Play Developer API integration gate pending service account setup.'
      );
    }

    const now = Date.now();
    if (this.cachedAccessToken && now < this.cachedAccessToken.expiresAt - 60000) {
      return this.cachedAccessToken.token;
    }

    const tokenUri = this.serviceAccount.token_uri || 'https://oauth2.googleapis.com/token';
    const iat = Math.floor(now / 1000);
    const exp = iat + 3600;

    const header = { alg: 'RS256', typ: 'JWT' };
    const payload = {
      iss: this.serviceAccount.client_email,
      scope: 'https://www.googleapis.com/auth/androidpublisher',
      aud: tokenUri,
      iat,
      exp
    };

    const encodedHeader = base64UrlEncode(JSON.stringify(header));
    const encodedPayload = base64UrlEncode(JSON.stringify(payload));
    const signingInput = `${encodedHeader}.${encodedPayload}`;

    const sign = createSign('RSA-SHA256');
    sign.update(signingInput);
    sign.end();
    const signature = base64UrlEncode(sign.sign(this.serviceAccount.private_key));
    const assertion = `${signingInput}.${signature}`;

    const body = new URLSearchParams({
      grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer',
      assertion
    }).toString();

    let res: Response;
    try {
      res = await this.fetchFn(tokenUri, {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body
      });
    } catch (err: unknown) {
      throw new GooglePlayApiError(`Failed to contact OAuth2 token endpoint: ${err}`, 503, true);
    }

    if (!res.ok) {
      const errText = await res.text().catch(() => '');
      throw new GooglePlayApiError(
        `OAuth2 token exchange failed with HTTP ${res.status}: ${errText}`,
        res.status,
        res.status >= 500
      );
    }

    const tokenData = await res.json() as any;
    const token = tokenData.access_token;
    const expiresIn = Number(tokenData.expires_in) || 3600;

    this.cachedAccessToken = {
      token,
      expiresAt: now + expiresIn * 1000
    };

    return token;
  }

  /**
   * Authoritatively queries subscription details via Android Publisher v3.
   */
  async getSubscription(
    packageName: string,
    subscriptionId: string,
    token: string
  ): Promise<GooglePlaySubscriptionResult> {
    const accessToken = await this.getAccessToken();
    const url = `${this.baseUrl}/androidpublisher/v3/applications/${encodeURIComponent(packageName)}/purchases/subscriptionsv2/tokens/${encodeURIComponent(token)}`;

    let res: Response;
    try {
      res = await this.fetchFn(url, {
        method: 'GET',
        headers: {
          Authorization: `Bearer ${accessToken}`,
          Accept: 'application/json'
        }
      });
    } catch (err: unknown) {
      throw new GooglePlayApiError(`Network failure querying Google Play subscription: ${err}`, 503, true);
    }

    if (res.status === 404) {
      throw new GooglePlayApiError(`Subscription purchase token not found: ${maskToken(token)}`, 404, false);
    }
    if (res.status === 401 || res.status === 403) {
      throw new GooglePlayApiError(`Unauthorized or insufficient permissions for Google Play Developer API (${res.status})`, res.status, false);
    }
    if (res.status === 429) {
      throw new GooglePlayApiError('Google Play Developer API rate limit exceeded', 429, true);
    }
    if (res.status >= 500) {
      throw new GooglePlayApiError(`Google Play Developer API server error (${res.status})`, res.status, true);
    }
    if (!res.ok) {
      const errText = await res.text().catch(() => '');
      throw new GooglePlayApiError(`Google Play Developer API error: ${errText || res.statusText}`, res.status, false);
    }

    let data: any;
    try {
      data = await res.json();
    } catch (err: unknown) {
      throw new GooglePlayApiError(`Malformed JSON response from Google Play: ${err}`, 503, true);
    }

    if (!data || typeof data !== 'object' || Object.keys(data).length === 0) {
      throw new GooglePlayApiError('Empty or invalid JSON response from Google Play subscription API', 502, true);
    }

    // Support both subscriptionsv2 (v2) and subscriptions (v1)
    if (data.subscriptionState !== undefined || Array.isArray(data.lineItems)) {
      return this.parseSubscriptionV2(data, subscriptionId);
    }

    // Subscriptions v1 validation
    if (data.expiryTimeMillis === undefined || data.expiryTimeMillis === null || isNaN(Number(data.expiryTimeMillis)) || Number(data.expiryTimeMillis) <= 0) {
      throw new GooglePlayApiError('Malformed subscription response: missing or invalid expiryTimeMillis', 502, true);
    }

    if (data.paymentState === undefined || data.paymentState === null || isNaN(Number(data.paymentState))) {
      throw new GooglePlayApiError('Malformed subscription response: missing paymentState', 502, true);
    }

    const paymentState = Number(data.paymentState);
    if (![0, 1, 2, 3].includes(paymentState)) {
      throw new GooglePlayApiError(`Unknown paymentState '${paymentState}' in subscription response`, 502, true);
    }

    return {
      acknowledgementState: Number(data.acknowledgementState ?? 0),
      expiryTimeMillis: Number(data.expiryTimeMillis),
      startTimeMillis: data.startTimeMillis ? Number(data.startTimeMillis) : undefined,
      paymentState,
      autoRenewing: Boolean(data.autoRenewing),
      obfuscatedExternalAccountId: data.obfuscatedExternalAccountId || undefined,
      cancelReason: data.cancelReason !== undefined ? Number(data.cancelReason) : undefined,
      orderId: data.orderId || undefined,
      linkedPurchaseToken: data.linkedPurchaseToken || undefined
    };
  }

  private parseSubscriptionV2(data: any, expectedSubscriptionId: string): GooglePlaySubscriptionResult {
    const rawState = data.subscriptionState;

    if (rawState === undefined || rawState === null) {
      throw new GooglePlayApiError('Malformed subscriptionsv2 response: missing subscriptionState', 502, true);
    }

    const VALID_STATES = new Set([
      'SUBSCRIPTION_STATE_PENDING',
      'SUBSCRIPTION_STATE_ACTIVE',
      'SUBSCRIPTION_STATE_PAUSED',
      'SUBSCRIPTION_STATE_IN_GRACE_PERIOD',
      'SUBSCRIPTION_STATE_ON_HOLD',
      'SUBSCRIPTION_STATE_CANCELED',
      'SUBSCRIPTION_STATE_EXPIRED',
      'SUBSCRIPTION_STATE_PENDING_PURCHASE_CANCELED'
    ]);

    if (typeof rawState !== 'string' || !VALID_STATES.has(rawState)) {
      throw new GooglePlayApiError(`Subscription is in invalid or unknown state: ${rawState}`, 400, false);
    }

    let paymentState = 1;
    let autoRenewing = true;
    let cancelReason: number | undefined;

    const lineItems = Array.isArray(data.lineItems) ? data.lineItems : [];
    if (lineItems.length === 0) {
      throw new GooglePlayApiError('Malformed subscriptionsv2 response: lineItems array is empty', 502, true);
    }

    const item = expectedSubscriptionId
      ? lineItems.find((li: any) => li.productId === expectedSubscriptionId)
      : lineItems[0];
    if (!item || !item.productId) {
      if (expectedSubscriptionId) {
        throw new GooglePlayApiError(
          `Subscription line item mismatch: expected product '${expectedSubscriptionId}', but Google Play returned items: [${lineItems.map((li: any) => li.productId).join(', ')}]`,
          400,
          false
        );
      } else {
        throw new GooglePlayApiError('Malformed subscriptionsv2 response: lineItem missing productId', 502, true);
      }
    }

    let expiryTimeMillis = 0;
    if (rawState === 'SUBSCRIPTION_STATE_PENDING' || rawState === 'SUBSCRIPTION_STATE_PENDING_PURCHASE_CANCELED') {
      if (item.expiryTime) {
        const parsed = typeof item.expiryTime === 'string' ? Date.parse(item.expiryTime) : Number(item.expiryTime);
        expiryTimeMillis = isNaN(parsed) ? 0 : parsed;
      }
    } else {
      if (!item.expiryTime) {
        throw new GooglePlayApiError('Malformed subscriptionsv2 response: missing expiryTime in lineItem', 502, true);
      }

      expiryTimeMillis = typeof item.expiryTime === 'string'
        ? Date.parse(item.expiryTime)
        : Number(item.expiryTime);

      if (isNaN(expiryTimeMillis) || expiryTimeMillis <= 0) {
        throw new GooglePlayApiError(`Malformed subscriptionsv2 expiryTime '${item.expiryTime}'`, 502, true);
      }
    }

    if (item.autoRenewingPlan?.autoRenewEnabled !== undefined) {
      autoRenewing = Boolean(item.autoRenewingPlan.autoRenewEnabled);
    }

    if (rawState === 'SUBSCRIPTION_STATE_PENDING') {
      paymentState = 0;
      autoRenewing = false;
    } else if (rawState === 'SUBSCRIPTION_STATE_PENDING_PURCHASE_CANCELED') {
      paymentState = 0;
      autoRenewing = false;
      cancelReason = 1;
    } else if (rawState === 'SUBSCRIPTION_STATE_CANCELED') {
      autoRenewing = false;
      cancelReason = 1;
    } else if (rawState === 'SUBSCRIPTION_STATE_ON_HOLD') {
      autoRenewing = false;
    } else if (rawState === 'SUBSCRIPTION_STATE_PAUSED') {
      autoRenewing = false;
    } else if (rawState === 'SUBSCRIPTION_STATE_EXPIRED') {
      autoRenewing = false;
    }

    const ackState = data.acknowledgementState === 'ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED' || data.acknowledgementState === 1 ? 1 : 0;
    const startTimeMillis = data.startTime ? Date.parse(data.startTime) : undefined;
    const externalAccountId = data.externalAccountIdentifiers?.obfuscatedExternalAccountId || data.obfuscatedExternalAccountId;

    return {
      acknowledgementState: ackState,
      expiryTimeMillis,
      startTimeMillis: isNaN(startTimeMillis as number) ? undefined : startTimeMillis,
      paymentState,
      autoRenewing,
      obfuscatedExternalAccountId: externalAccountId,
      cancelReason,
      orderId: data.latestOrderId || data.orderId,
      linkedPurchaseToken: data.linkedPurchaseToken,
      subscriptionState: typeof rawState === 'string' ? rawState : undefined,
      lineItemProductId: item.productId
    };
  }

  /**
   * Authoritatively queries one-time in-app product details via Android Publisher v3.
   */
  async getInAppProduct(
    packageName: string,
    productId: string,
    token: string
  ): Promise<GooglePlayInAppResult> {
    const accessToken = await this.getAccessToken();
    const url = `${this.baseUrl}/androidpublisher/v3/applications/${encodeURIComponent(packageName)}/purchases/products/${encodeURIComponent(productId)}/tokens/${encodeURIComponent(token)}`;

    let res: Response;
    try {
      res = await this.fetchFn(url, {
        method: 'GET',
        headers: {
          Authorization: `Bearer ${accessToken}`,
          Accept: 'application/json'
        }
      });
    } catch (err: unknown) {
      throw new GooglePlayApiError(`Network failure querying Google Play in-app product: ${err}`, 503, true);
    }

    if (res.status === 404) {
      throw new GooglePlayApiError(`In-app purchase token not found: ${maskToken(token)}`, 404, false);
    }
    if (res.status === 401 || res.status === 403) {
      throw new GooglePlayApiError(`Unauthorized or insufficient permissions for Google Play Developer API (${res.status})`, res.status, false);
    }
    if (res.status === 429) {
      throw new GooglePlayApiError('Google Play Developer API rate limit exceeded', 429, true);
    }
    if (res.status >= 500) {
      throw new GooglePlayApiError(`Google Play Developer API server error (${res.status})`, res.status, true);
    }
    if (!res.ok) {
      const errText = await res.text().catch(() => '');
      throw new GooglePlayApiError(`Google Play Developer API error: ${errText || res.statusText}`, res.status, false);
    }

    let data: any;
    try {
      data = await res.json();
    } catch (err: unknown) {
      throw new GooglePlayApiError(`Malformed JSON response from Google Play: ${err}`, 503, true);
    }

    if (!data || typeof data !== 'object' || Object.keys(data).length === 0) {
      throw new GooglePlayApiError('Empty or invalid JSON response from Google Play in-app product API', 502, true);
    }

    if (data.purchaseState === undefined || data.purchaseState === null || isNaN(Number(data.purchaseState))) {
      throw new GooglePlayApiError('Malformed in-app product response: missing or invalid purchaseState', 502, true);
    }

    const purchaseState = Number(data.purchaseState);
    if (![0, 1, 2].includes(purchaseState)) {
      throw new GooglePlayApiError(`Unknown purchaseState '${purchaseState}' in Google Play in-app response`, 502, true);
    }

    if (data.purchaseTimeMillis === undefined || data.purchaseTimeMillis === null || isNaN(Number(data.purchaseTimeMillis)) || Number(data.purchaseTimeMillis) <= 0) {
      throw new GooglePlayApiError('Malformed in-app product response: missing or invalid purchaseTimeMillis', 502, true);
    }

    return {
      purchaseState,
      consumptionState: Number(data.consumptionState ?? 0),
      acknowledgementState: Number(data.acknowledgementState ?? 0),
      purchaseTimeMillis: Number(data.purchaseTimeMillis),
      obfuscatedExternalAccountId: data.obfuscatedExternalAccountId || undefined,
      orderId: data.orderId || undefined
    };
  }

  /**
   * Acknowledges a subscription purchase with Google Play.
   */
  async acknowledgeSubscription(
    packageName: string,
    subscriptionId: string,
    token: string
  ): Promise<void> {
    const accessToken = await this.getAccessToken();
    const url = `${this.baseUrl}/androidpublisher/v3/applications/${encodeURIComponent(packageName)}/purchases/subscriptions/${encodeURIComponent(subscriptionId)}/tokens/${encodeURIComponent(token)}:acknowledge`;

    let res: Response;
    try {
      res = await this.fetchFn(url, {
        method: 'POST',
        headers: {
          Authorization: `Bearer ${accessToken}`,
          'Content-Type': 'application/json'
        },
        body: JSON.stringify({})
      });
    } catch (err: unknown) {
      throw new GooglePlayApiError(`Network failure acknowledging subscription: ${err}`, 503, true);
    }

    if (res.status === 200 || res.status === 204) {
      return;
    }

    const errText = await res.text().catch(() => '');
    if (res.status === 400 && /already acknowledged|already been acknowledged/i.test(errText)) {
      throw new GooglePlayApiError('The purchase token has already been acknowledged.', 400, false);
    }

    if (res.status === 404) {
      throw new GooglePlayApiError(`Subscription purchase token not found: ${maskToken(token)}`, 404, false);
    }
    if (res.status === 401 || res.status === 403) {
      throw new GooglePlayApiError(`Unauthorized acknowledge request (${res.status})`, res.status, false);
    }
    if (res.status === 429) {
      throw new GooglePlayApiError('Rate limit exceeded acknowledging subscription', 429, true);
    }
    if (res.status >= 500) {
      throw new GooglePlayApiError(`Google Play server error acknowledging subscription (${res.status})`, res.status, true);
    }

    throw new GooglePlayApiError(`Failed to acknowledge subscription: ${errText || res.statusText}`, res.status, false);
  }

  /**
   * Acknowledges an in-app product purchase with Google Play.
   */
  async acknowledgeInAppProduct(
    packageName: string,
    productId: string,
    token: string
  ): Promise<void> {
    const accessToken = await this.getAccessToken();
    const url = `${this.baseUrl}/androidpublisher/v3/applications/${encodeURIComponent(packageName)}/purchases/products/${encodeURIComponent(productId)}/tokens/${encodeURIComponent(token)}:acknowledge`;

    let res: Response;
    try {
      res = await this.fetchFn(url, {
        method: 'POST',
        headers: {
          Authorization: `Bearer ${accessToken}`,
          'Content-Type': 'application/json'
        },
        body: JSON.stringify({})
      });
    } catch (err: unknown) {
      throw new GooglePlayApiError(`Network failure acknowledging in-app product: ${err}`, 503, true);
    }

    if (res.status === 200 || res.status === 204) {
      return;
    }

    const errText = await res.text().catch(() => '');
    if (res.status === 400 && /already acknowledged|already been acknowledged/i.test(errText)) {
      throw new GooglePlayApiError('The purchase token has already been acknowledged.', 400, false);
    }

    if (res.status === 404) {
      throw new GooglePlayApiError(`In-app purchase token not found: ${maskToken(token)}`, 404, false);
    }
    if (res.status === 401 || res.status === 403) {
      throw new GooglePlayApiError(`Unauthorized acknowledge request (${res.status})`, res.status, false);
    }
    if (res.status === 429) {
      throw new GooglePlayApiError('Rate limit exceeded acknowledging in-app product', 429, true);
    }
    if (res.status >= 500) {
      throw new GooglePlayApiError(`Google Play server error acknowledging in-app product (${res.status})`, res.status, true);
    }

    throw new GooglePlayApiError(`Failed to acknowledge in-app product: ${errText || res.statusText}`, res.status, false);
  }
}

/**
 * Controllable Mock implementation for unit tests and local development.
 */
export class MockGooglePlayBillingApi implements GooglePlayBillingApi {
  private subHandlers: Map<string, () => Promise<GooglePlaySubscriptionResult>> = new Map();
  private inAppHandlers: Map<string, () => Promise<GooglePlayInAppResult>> = new Map();
  public acknowledgedTokens: Set<string> = new Set();
  public ackFailureHandler?: (token: string) => Promise<void>;

  registerSubscription(token: string, handler: () => Promise<GooglePlaySubscriptionResult>): void {
    this.subHandlers.set(token, handler);
  }

  registerInApp(token: string, handler: () => Promise<GooglePlayInAppResult>): void {
    this.inAppHandlers.set(token, handler);
  }

  async getSubscription(
    _packageName: string,
    _subscriptionId: string,
    token: string
  ): Promise<GooglePlaySubscriptionResult> {
    const handler = this.subHandlers.get(token);
    if (!handler) {
      throw new GooglePlayApiError(`Subscription purchase token not found: ${token}`, 404, false);
    }
    return handler();
  }

  async getInAppProduct(
    _packageName: string,
    _productId: string,
    token: string
  ): Promise<GooglePlayInAppResult> {
    const handler = this.inAppHandlers.get(token);
    if (!handler) {
      throw new GooglePlayApiError(`In-app purchase token not found: ${token}`, 404, false);
    }
    return handler();
  }

  async acknowledgeSubscription(
    _packageName: string,
    _subscriptionId: string,
    token: string
  ): Promise<void> {
    if (this.ackFailureHandler) {
      await this.ackFailureHandler(token);
    }
    this.acknowledgedTokens.add(token);
  }

  async acknowledgeInAppProduct(
    _packageName: string,
    _productId: string,
    token: string
  ): Promise<void> {
    if (this.ackFailureHandler) {
      await this.ackFailureHandler(token);
    }
    this.acknowledgedTokens.add(token);
  }
}
