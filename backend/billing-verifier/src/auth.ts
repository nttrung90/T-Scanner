import { createHmac, createPublicKey, createSign, createVerify, timingSafeEqual, verify, type KeyObject } from 'node:crypto';
import type { IncomingMessage } from 'node:http';
import type { AuthConfig, PubSubPrincipal, UserPrincipal } from './types.ts';

export class AuthenticationError extends Error {
  readonly statusCode: number = 401;
  constructor(message: string) {
    super(message);
    this.name = 'AuthenticationError';
  }
}

export class AuthorizationError extends Error {
  readonly statusCode: number = 403;
  constructor(message: string) {
    super(message);
    this.name = 'AuthorizationError';
  }
}

function base64UrlDecode(input: string): string {
  let base64 = input.replace(/-/g, '+').replace(/_/g, '/');
  while (base64.length % 4 !== 0) {
    base64 += '=';
  }
  return Buffer.from(base64, 'base64').toString('utf8');
}

function base64UrlEncode(input: string | Buffer): string {
  const buf = typeof input === 'string' ? Buffer.from(input, 'utf8') : input;
  return buf.toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

/**
 * Creates a signed JWT for testing or service authentication.
 */
export function createTestJwt(
  payload: Record<string, unknown>,
  secret: string = 'tscanner-dev-secret',
  options: {
    expiresInSeconds?: number;
    alg?: string;
    overrideSignature?: string;
    customHeader?: Record<string, unknown>;
  } = {}
): string {
  const header = options.customHeader || {
    alg: options.alg || 'HS256',
    typ: 'JWT'
  };

  const finalPayload = { ...payload };
  if (options.expiresInSeconds !== undefined) {
    finalPayload.exp = Math.floor(Date.now() / 1000) + options.expiresInSeconds;
  } else if (secret !== 'tscanner-dev-secret' && finalPayload.exp === undefined) {
    finalPayload.exp = Math.floor(Date.now() / 1000) + 3600;
  }

  const encodedHeader = base64UrlEncode(JSON.stringify(header));
  const encodedPayload = base64UrlEncode(JSON.stringify(finalPayload));
  const signingInput = `${encodedHeader}.${encodedPayload}`;

  if (options.overrideSignature !== undefined) {
    return `${signingInput}.${options.overrideSignature}`;
  }

  const signature = createHmac('sha256', secret)
    .update(signingInput)
    .digest();

  return `${signingInput}.${base64UrlEncode(signature)}`;
}

/**
 * Creates an RSA-signed JWT (RS256) for testing Google ID tokens.
 */
export function createTestRsaJwt(
  payload: Record<string, unknown>,
  privateKey: KeyObject | string,
  options: {
    kid?: string;
    expiresInSeconds?: number;
    alg?: string;
    overrideSignature?: string;
    customHeader?: Record<string, unknown>;
  } = {}
): string {
  const header = options.customHeader || {
    alg: options.alg || 'RS256',
    typ: 'JWT',
    ...(options.kid ? { kid: options.kid } : {})
  };

  const finalPayload = { ...payload };
  if (options.expiresInSeconds !== undefined) {
    finalPayload.exp = Math.floor(Date.now() / 1000) + options.expiresInSeconds;
  }

  const encodedHeader = base64UrlEncode(JSON.stringify(header));
  const encodedPayload = base64UrlEncode(JSON.stringify(finalPayload));
  const signingInput = `${encodedHeader}.${encodedPayload}`;

  if (options.overrideSignature !== undefined) {
    return `${signingInput}.${options.overrideSignature}`;
  }

  const sign = createSign('RSA-SHA256');
  sign.update(signingInput);
  const signature = sign.sign(privateKey);

  return `${signingInput}.${base64UrlEncode(signature)}`;
}

export class GoogleJwksClient {
  private readonly jwksUri: string;
  private readonly customFetchFn?: (url: string) => Promise<{ keys: Array<Record<string, unknown>> }>;
  private readonly ttlMs: number;
  private readonly cache: Map<string, KeyObject> = new Map();
  private cacheExpiresAt: number = 0;

  constructor(options: {
    jwksUri?: string;
    jwksFetchFn?: (url: string) => Promise<{ keys: Array<Record<string, unknown>> }>;
    ttlMs?: number;
  } = {}) {
    this.jwksUri = options.jwksUri || 'https://www.googleapis.com/oauth2/v3/certs';
    this.customFetchFn = options.jwksFetchFn;
    this.ttlMs = options.ttlMs || 3600000;
  }

  setKeysForTesting(keys: Array<{ kid?: string; publicKey: KeyObject | string } | Record<string, unknown>>) {
    this.cache.clear();
    for (const k of keys) {
      if ('publicKey' in k && (typeof k.publicKey === 'object' || typeof k.publicKey === 'string')) {
        const kid = k.kid || 'default';
        const pub = typeof k.publicKey === 'string' ? createPublicKey(k.publicKey) : (k.publicKey as KeyObject);
        this.cache.set(kid, pub);
      } else {
        const jwk = k as { kid?: string; n?: string; e?: string };
        if (jwk.kid && jwk.n && jwk.e) {
          const pub = createPublicKey({ key: jwk as any, format: 'jwk' });
          this.cache.set(jwk.kid, pub);
        }
      }
    }
    this.cacheExpiresAt = Date.now() + 86400000;
  }

  async refreshJwks(): Promise<void> {
    try {
      let data: { keys: Array<Record<string, unknown>> };
      if (this.customFetchFn) {
        data = await this.customFetchFn(this.jwksUri);
      } else {
        const res = await fetch(this.jwksUri, { signal: AbortSignal.timeout(5000) });
        if (!res.ok) {
          throw new Error(`JWKS endpoint returned HTTP ${res.status}`);
        }
        data = await res.json();
      }

      if (!data || !Array.isArray(data.keys)) {
        throw new Error('Malformed JWKS response: missing keys array');
      }

      this.cache.clear();
      for (const k of data.keys) {
        if (k.kty === 'RSA' && k.kid && k.n && k.e) {
          const pub = createPublicKey({ key: k as any, format: 'jwk' });
          this.cache.set(k.kid as string, pub);
        }
      }
      this.cacheExpiresAt = Date.now() + this.ttlMs;
    } catch (err) {
      throw new AuthenticationError(`Failed to fetch JWKS keys: ${err instanceof Error ? err.message : String(err)}`);
    }
  }

  hasCachedKey(kid?: string): boolean {
    if (!kid) return this.cache.size > 0 && Date.now() < this.cacheExpiresAt;
    return this.cache.has(kid) && Date.now() < this.cacheExpiresAt;
  }

  getCachedKey(kid?: string): KeyObject | undefined {
    if (Date.now() >= this.cacheExpiresAt && this.cache.size > 0) {
      return undefined;
    }
    if (kid) {
      return this.cache.get(kid);
    }
    if (this.cache.size === 1) {
      return this.cache.values().next().value;
    }
    return undefined;
  }

  async getKey(kid?: string): Promise<KeyObject> {
    let key = this.getCachedKey(kid);
    if (!key) {
      await this.refreshJwks();
      key = this.getCachedKey(kid);
    }
    if (!key) {
      throw new AuthenticationError(kid ? `Unknown key ID (kid: '${kid}') in token header` : 'No RSA keys found in JWKS');
    }
    return key;
  }
}

export class AuthService {
  readonly config: AuthConfig;
  readonly jwksClient: GoogleJwksClient;
  readonly pubsubJwksClient: GoogleJwksClient;
  private readonly customUserVerifier?: (token: string) => Promise<UserPrincipal | null>;
  private readonly customPubSubVerifier?: (token: string, req: IncomingMessage) => Promise<PubSubPrincipal | null>;

  constructor(
    config: AuthConfig = {},
    customUserVerifier?: (token: string) => Promise<UserPrincipal | null>,
    customPubSubVerifier?: (token: string, req: IncomingMessage) => Promise<PubSubPrincipal | null>
  ) {
    const resolvedAudience = config.expectedAudience || config.googleClientId || process.env.JWT_AUDIENCE || process.env.GOOGLE_CLIENT_ID;
    this.config = {
      googleClientId: resolvedAudience,
      expectedAudience: resolvedAudience,
      allowedIssuers: config.allowedIssuers || (process.env.JWT_ISSUER ? [process.env.JWT_ISSUER] : undefined),
      jwksUri: config.jwksUri || process.env.GOOGLE_JWKS_URI || 'https://www.googleapis.com/oauth2/v3/certs',
      jwksFetchFn: config.jwksFetchFn,
      keyRotationTtlMs: config.keyRotationTtlMs,
      pubsubExpectedAudience: config.pubsubExpectedAudience || process.env.PUBSUB_AUDIENCE,
      pubsubExpectedServiceAccount: config.pubsubExpectedServiceAccount || process.env.PUBSUB_SERVICE_ACCOUNT,
      pubsubSecretToken: config.pubsubSecretToken || process.env.PUBSUB_SECRET_TOKEN,
      jwtSecret: config.jwtSecret || process.env.JWT_SECRET,
      allowHmacFallback: config.allowHmacFallback ?? (Boolean(config.jwtSecret || process.env.JWT_SECRET))
    };

    this.jwksClient = new GoogleJwksClient({
      jwksUri: this.config.jwksUri,
      jwksFetchFn: this.config.jwksFetchFn,
      ttlMs: this.config.keyRotationTtlMs
    });

    this.pubsubJwksClient = new GoogleJwksClient({
      jwksUri: this.config.pubsubJwksUri || this.config.jwksUri,
      jwksFetchFn: this.config.jwksFetchFn,
      ttlMs: this.config.keyRotationTtlMs
    });

    this.customUserVerifier = customUserVerifier;
    this.customPubSubVerifier = customPubSubVerifier;
  }

  isUserAuthConfigured(): boolean {
    if (this.customUserVerifier) return true;
    if (this.config.expectedAudience) return true;
    if (this.config.jwtSecret && this.config.jwtSecret !== 'tscanner-dev-secret' && this.config.allowHmacFallback) return true;
    return false;
  }

  isPubSubConfigured(): boolean {
    if (this.customPubSubVerifier) return true;
    if (this.config.pubsubSecretToken) return true;
    if (this.config.pubsubExpectedAudience && this.config.pubsubExpectedServiceAccount) return true;
    return false;
  }

  isConfigured(): boolean {
    return this.isUserAuthConfigured() && this.isPubSubConfigured();
  }

  setJwksKeysForTesting(keys: Array<{ kid?: string; publicKey: KeyObject | string } | Record<string, unknown>>) {
    this.jwksClient.setKeysForTesting(keys);
    this.pubsubJwksClient.setKeysForTesting(keys);
  }

  /**
   * Verifies standard user Bearer JWT token from HTTP Authorization header.
   * Throws AuthenticationError (401) on failure.
   */
  async authenticateUser(req: IncomingMessage): Promise<UserPrincipal> {
    const authHeader = req.headers.authorization;
    if (!authHeader) {
      throw new AuthenticationError('Missing Authorization header');
    }

    if (!authHeader.startsWith('Bearer ')) {
      throw new AuthenticationError("Authorization header must use 'Bearer' scheme");
    }

    const token = authHeader.slice(7).trim();
    if (!token) {
      throw new AuthenticationError('Bearer token is empty');
    }

    if (this.customUserVerifier) {
      const customResult = await this.customUserVerifier(token);
      if (!customResult || !customResult.sub) {
        throw new AuthenticationError('Custom token verification failed');
      }
      return customResult;
    }

    return this.verifyUserTokenAsync(token);
  }

  async verifyUserTokenAsync(token: string): Promise<UserPrincipal> {
    const parts = token.split('.');
    if (parts.length !== 3) {
      throw new AuthenticationError('Invalid JWT structure: must have 3 dot-separated segments');
    }

    let header: Record<string, unknown>;
    try {
      header = JSON.parse(base64UrlDecode(parts[0]));
    } catch {
      throw new AuthenticationError('Malformed JWT header');
    }

    if (header.alg === 'RS256') {
      const key = await this.jwksClient.getKey(header.kid as string | undefined);
      return this.verifyUserToken(token, key);
    }

    return this.verifyUserToken(token);
  }

  /**
   * Parses and cryptographically validates a user JWT token.
   * Synchronous version for testing or when RSA key is resolved.
   */
  verifyUserToken(token: string, resolvedRsaKey?: KeyObject): UserPrincipal {
    const parts = token.split('.');
    if (parts.length !== 3) {
      throw new AuthenticationError('Invalid JWT structure: must have 3 dot-separated segments');
    }

    const [headerB64, payloadB64, signatureB64] = parts;
    let header: Record<string, unknown>;
    let payload: Record<string, unknown>;

    try {
      header = JSON.parse(base64UrlDecode(headerB64));
    } catch {
      throw new AuthenticationError('Malformed JWT header');
    }

    try {
      payload = JSON.parse(base64UrlDecode(payloadB64));
    } catch {
      throw new AuthenticationError('Malformed JWT payload');
    }

    if (header.alg === 'none') {
      throw new AuthenticationError("Insecure algorithm 'none' is disallowed");
    }

    if (this.config.jwtSecret === 'tscanner-dev-secret') {
      throw new AuthenticationError("Insecure development secret 'tscanner-dev-secret' is rejected");
    }

    const signingInput = `${headerB64}.${payloadB64}`;

    if (header.alg === 'RS256') {
      const key = resolvedRsaKey || this.jwksClient.getCachedKey(header.kid as string | undefined);
      if (!key) {
        if (this.config.jwtSecret) {
          throw new AuthenticationError("Algorithm mismatch: token declares RS256 but HMAC secret was provided");
        }
        throw new AuthenticationError(`Unknown key ID (kid: '${header.kid}') in token header`);
      }

      const sigBuf = Buffer.from(signatureB64, 'base64url');
      const isValid = verify('RSA-SHA256', Buffer.from(signingInput), key, sigBuf);
      if (!isValid) {
        throw new AuthenticationError('Invalid token signature');
      }
    } else if (header.alg === 'HS256') {
      if (!this.config.jwtSecret) {
        throw new AuthenticationError("No HMAC secret configured for HS256 token");
      }
      if (payload.iss === 'https://accounts.google.com' || payload.iss === 'accounts.google.com') {
        throw new AuthenticationError("Google identity tokens cannot use HMAC HS256");
      }
      if (!this.config.allowHmacFallback && !process.env.ALLOW_HMAC_FALLBACK) {
        throw new AuthenticationError("HMAC verification is not permitted for Google identity");
      }

      const expectedSig = createHmac('sha256', this.config.jwtSecret)
        .update(signingInput)
        .digest();
      const expectedSigB64 = base64UrlEncode(expectedSig);

      const sigBuf = Buffer.from(signatureB64, 'utf8');
      const expectedBuf = Buffer.from(expectedSigB64, 'utf8');

      if (sigBuf.length !== expectedBuf.length || !timingSafeEqual(sigBuf, expectedBuf)) {
        throw new AuthenticationError('Invalid token signature');
      }
    } else {
      throw new AuthenticationError(`Unsupported algorithm '${header.alg}'`);
    }

    // Mandatory Subject Validation (Canonical user ID)
    const sub = typeof payload.sub === 'string' ? payload.sub.trim() : undefined;
    if (!sub) {
      throw new AuthenticationError("Token payload missing mandatory 'sub' claim");
    }

    // Mandatory Expiration Validation
    if (typeof payload.exp !== 'number' || isNaN(payload.exp)) {
      throw new AuthenticationError("Token payload missing mandatory 'exp' claim");
    }
    const nowSeconds = Math.floor(Date.now() / 1000);
    if (nowSeconds >= payload.exp) {
      throw new AuthenticationError(`Token expired at ${payload.exp} (current=${nowSeconds})`);
    }

    // Issuer Verification
    if (header.alg === 'RS256') {
      const googleIssuers = this.config.allowedIssuers && this.config.allowedIssuers.length > 0
        ? this.config.allowedIssuers
        : ['https://accounts.google.com', 'accounts.google.com'];
      const iss = typeof payload.iss === 'string' ? payload.iss : undefined;
      if (!iss || !googleIssuers.includes(iss)) {
        throw new AuthenticationError(`Invalid token issuer '${iss}'`);
      }
    } else if (this.config.allowedIssuers && this.config.allowedIssuers.length > 0) {
      const iss = typeof payload.iss === 'string' ? payload.iss : undefined;
      if (!iss || !this.config.allowedIssuers.includes(iss)) {
        throw new AuthenticationError(`Invalid token issuer '${iss}'`);
      }
    }

    // Audience Verification (Mandatory for RS256 Google user tokens)
    if (header.alg === 'RS256') {
      if (!this.config.expectedAudience) {
        throw new AuthenticationError("Authentication misconfigured: expected audience or googleClientId is required");
      }
      const aud = payload.aud;
      const audList = Array.isArray(aud) ? aud : typeof aud === 'string' ? [aud] : [];
      if (!audList.includes(this.config.expectedAudience)) {
        throw new AuthenticationError(`Invalid token audience '${aud}', expected '${this.config.expectedAudience}'`);
      }
    } else if (this.config.expectedAudience) {
      const aud = payload.aud;
      const audList = Array.isArray(aud) ? aud : typeof aud === 'string' ? [aud] : [];
      if (!audList.includes(this.config.expectedAudience)) {
        throw new AuthenticationError(`Invalid token audience '${aud}'`);
      }
    }

    return {
      sub,
      email: typeof payload.email === 'string' ? payload.email : undefined,
      email_verified: typeof payload.email_verified === 'boolean' ? payload.email_verified : undefined,
      aud: typeof payload.aud === 'string' ? payload.aud : undefined,
      iss: typeof payload.iss === 'string' ? payload.iss : undefined,
      exp: payload.exp,
      ...payload
    };
  }

  /**
   * Verifies incoming Google Cloud Pub/Sub push notification.
   * Throws AuthenticationError (401) or AuthorizationError (403) on failure.
   */
  async authenticatePubSub(req: IncomingMessage): Promise<PubSubPrincipal> {
    const authHeader = req.headers.authorization;
    const secretHeader = req.headers['x-pubsub-secret'];

    // Check shared secret header/param if configured
    if (this.config.pubsubSecretToken) {
      if (secretHeader === this.config.pubsubSecretToken) {
        return { sub: 'pubsub-service-agent', email: 'service-agent@pubsub.google.test', email_verified: true };
      }
      const url = new URL(req.url || '', 'http://127.0.0.1');
      if (url.searchParams.get('secret') === this.config.pubsubSecretToken) {
        return { sub: 'pubsub-service-agent', email: 'service-agent@pubsub.google.test', email_verified: true };
      }
    }

    if (!authHeader) {
      throw new AuthenticationError('Missing Pub/Sub Authorization header or secret');
    }

    if (!authHeader.startsWith('Bearer ')) {
      throw new AuthenticationError("Pub/Sub Authorization header must use 'Bearer' scheme");
    }

    const token = authHeader.slice(7).trim();
    if (!token) {
      throw new AuthenticationError('Pub/Sub Bearer token is empty');
    }

    if (this.customPubSubVerifier) {
      const custom = await this.customPubSubVerifier(token, req);
      if (!custom) {
        throw new AuthenticationError('Custom Pub/Sub verification failed');
      }
      return custom;
    }

    const parts = token.split('.');
    if (parts.length !== 3) {
      throw new AuthenticationError('Invalid Pub/Sub JWT structure');
    }

    const [headerB64, payloadB64, signatureB64] = parts;
    let header: Record<string, unknown>;
    let payload: Record<string, unknown>;
    try {
      header = JSON.parse(base64UrlDecode(headerB64));
    } catch {
      throw new AuthenticationError('Malformed Pub/Sub JWT header');
    }
    try {
      payload = JSON.parse(base64UrlDecode(payloadB64));
    } catch {
      throw new AuthenticationError('Malformed Pub/Sub JWT payload');
    }

    if (header.alg === 'none') {
      throw new AuthenticationError("Insecure algorithm 'none' is disallowed");
    }

    const signingInput = `${headerB64}.${payloadB64}`;

    if (header.alg === 'RS256') {
      if (!this.config.pubsubExpectedAudience || !this.config.pubsubExpectedServiceAccount) {
        throw new AuthorizationError('Pub/Sub push authentication misconfigured: missing expected audience or service account');
      }

      const key = await this.pubsubJwksClient.getKey(header.kid as string | undefined);
      const sigBuf = Buffer.from(signatureB64, 'base64url');
      const isValid = verify('RSA-SHA256', Buffer.from(signingInput), key, sigBuf);
      if (!isValid) {
        throw new AuthenticationError('Invalid Pub/Sub token signature');
      }

      if (payload.email_verified !== true) {
        throw new AuthorizationError('Pub/Sub token email is not verified');
      }

      const googleIssuers = this.config.allowedIssuers && this.config.allowedIssuers.length > 0
        ? this.config.allowedIssuers
        : ['https://accounts.google.com', 'accounts.google.com'];
      const iss = typeof payload.iss === 'string' ? payload.iss : undefined;
      if (!iss || !googleIssuers.includes(iss)) {
        throw new AuthenticationError(`Invalid Pub/Sub token issuer '${iss}'`);
      }

      const aud = typeof payload.aud === 'string' ? payload.aud : undefined;
      if (aud !== this.config.pubsubExpectedAudience) {
        throw new AuthorizationError(`Pub/Sub audience mismatch: expected '${this.config.pubsubExpectedAudience}', got '${aud}'`);
      }

      const email = typeof payload.email === 'string' ? payload.email : undefined;
      if (email !== this.config.pubsubExpectedServiceAccount) {
        throw new AuthorizationError(`Pub/Sub service account mismatch: expected '${this.config.pubsubExpectedServiceAccount}', got '${email}'`);
      }
    } else if (header.alg === 'HS256') {
      if (this.config.jwtSecret === 'tscanner-dev-secret') {
        throw new AuthenticationError("Insecure development secret 'tscanner-dev-secret' is rejected");
      }
      if (!this.config.jwtSecret || (!this.config.allowHmacFallback && !process.env.ALLOW_HMAC_FALLBACK)) {
        throw new AuthenticationError('HMAC verification not allowed for Pub/Sub');
      }

      const expectedSig = createHmac('sha256', this.config.jwtSecret)
        .update(signingInput)
        .digest();
      const expectedSigB64 = base64UrlEncode(expectedSig);

      const sigBuf = Buffer.from(signatureB64, 'utf8');
      const expectedBuf = Buffer.from(expectedSigB64, 'utf8');
      if (sigBuf.length !== expectedBuf.length || !timingSafeEqual(sigBuf, expectedBuf)) {
        throw new AuthenticationError('Invalid Pub/Sub token signature');
      }
    } else {
      throw new AuthenticationError(`Unsupported Pub/Sub algorithm '${header.alg}'`);
    }

    // Expiration check (Mandatory)
    if (typeof payload.exp !== 'number' || isNaN(payload.exp)) {
      throw new AuthenticationError("Pub/Sub token missing mandatory 'exp' claim");
    }
    const now = Math.floor(Date.now() / 1000);
    if (now >= payload.exp) {
      throw new AuthenticationError('Pub/Sub token expired');
    }

    // Audience check (if configured)
    if (this.config.pubsubExpectedAudience) {
      const aud = typeof payload.aud === 'string' ? payload.aud : undefined;
      if (aud !== this.config.pubsubExpectedAudience) {
        throw new AuthorizationError(`Pub/Sub audience mismatch: expected '${this.config.pubsubExpectedAudience}', got '${aud}'`);
      }
    }

    // Service Account check (if configured)
    if (this.config.pubsubExpectedServiceAccount) {
      const email = typeof payload.email === 'string' ? payload.email : undefined;
      if (email !== this.config.pubsubExpectedServiceAccount) {
        throw new AuthorizationError(`Pub/Sub service account mismatch: expected '${this.config.pubsubExpectedServiceAccount}', got '${email}'`);
      }
    }

    return {
      sub: typeof payload.sub === 'string' ? payload.sub : 'pubsub-agent',
      email: typeof payload.email === 'string' ? payload.email : undefined,
      email_verified: payload.email_verified === true,
      aud: typeof payload.aud === 'string' ? payload.aud : undefined,
      iss: typeof payload.iss === 'string' ? payload.iss : undefined,
      exp: payload.exp,
      ...payload
    };
  }
}
