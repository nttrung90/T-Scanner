import { createServer, type IncomingMessage, type ServerResponse, type Server } from 'node:http';
import { BillingVerifierService, defaultLogger, type Logger } from './verifier.ts';
import { EntitlementStore } from './store.ts';
import { ProductionGooglePlayBillingApi, type GooglePlayBillingApi } from './googlePlayClient.ts';
import type { RestoreRequest, VerificationRequest, AcknowledgeRequest } from './types.ts';
import { AcknowledgeService } from './ackService.ts';
import { AckWorker } from './ackWorker.ts';
import { RtdnHandler } from './rtdnHandler.ts';
import { AuthService, AuthenticationError, AuthorizationError } from './auth.ts';

const PORT = process.env.PORT ? parseInt(process.env.PORT, 10) : 8080;

export interface HttpServerOptions {
  store?: EntitlementStore;
  googleApi?: GooglePlayBillingApi;
  verifierService?: BillingVerifierService;
  ackService?: AcknowledgeService;
  ackWorker?: AckWorker;
  rtdnHandler?: RtdnHandler;
  authService?: AuthService;
  logger?: Logger;
}

function parseJsonBody<T>(req: IncomingMessage): Promise<T> {
  return new Promise((resolve, reject) => {
    let body = '';
    req.on('data', (chunk) => {
      body += chunk;
      if (body.length > 1e6) {
        req.destroy();
        reject(new Error('Payload too large'));
      }
    });
    req.on('end', () => {
      try {
        resolve(JSON.parse(body || '{}'));
      } catch (e) {
        reject(new Error('Invalid JSON format'));
      }
    });
    req.on('error', (err) => reject(err));
  });
}

function sendJson(res: ServerResponse, statusCode: number, data: unknown): void {
  const payload = JSON.stringify(data);
  res.writeHead(statusCode, {
    'Content-Type': 'application/json',
    'Content-Length': Buffer.byteLength(payload)
  });
  res.end(payload);
}

export function createHttpServer(options: HttpServerOptions = {}): Server {
  const store = options.store || new EntitlementStore();
  const googleApi = options.googleApi || new ProductionGooglePlayBillingApi();
  const logger = options.logger || defaultLogger;
  const verifierService = options.verifierService || new BillingVerifierService(googleApi, store, logger);
  const ackService = options.ackService || new AcknowledgeService(googleApi, store, logger);
  const ackWorker = options.ackWorker;
  const rtdnHandler = options.rtdnHandler || new RtdnHandler(googleApi, store, logger);
  const authService = options.authService || new AuthService();

  const server = createServer(async (req, res) => {
    const url = req.url || '';
    const method = req.method || 'GET';

    if (url === '/health' && method === 'GET') {
      return sendJson(res, 200, { status: 'UP', timestamp: Date.now() });
    }

    if ((url === '/readiness' || url === '/health/ready' || url === '/ready') && method === 'GET') {
      const isStorageDurable = store.isDurable() || process.env.NODE_ENV === 'test';
      const isUserAuthConfigured = authService.isUserAuthConfigured();
      const isPubSubConfigured = authService.isPubSubConfigured();
      const ready = isStorageDurable && isUserAuthConfigured && isPubSubConfigured;
      const statusCode = ready ? 200 : 503;
      return sendJson(res, statusCode, {
        status: ready ? 'READY' : 'NOT_READY',
        timestamp: Date.now(),
        checks: {
          storage: isStorageDurable ? 'OK' : 'FAIL_NOT_DURABLE',
          userAuth: isUserAuthConfigured ? 'OK' : 'FAIL_UNCONFIGURED',
          pushAuth: isPubSubConfigured ? 'OK' : 'FAIL_UNCONFIGURED'
        }
      });
    }

    if (url === '/api/v1/billing/verify' && method === 'POST') {
      // 1. Authenticate user
      let principal;
      try {
        principal = await authService.authenticateUser(req);
      } catch (err: unknown) {
        const statusCode = err instanceof AuthenticationError ? 401 : err instanceof AuthorizationError ? 403 : 401;
        const msg = err instanceof Error ? err.message : 'Authentication required';
        return sendJson(res, statusCode, { status: 'UNAUTHORIZED', message: msg });
      }

      try {
        const body = await parseJsonBody<VerificationRequest>(req);
        if (!body.productId || !body.purchaseToken) {
          return sendJson(res, 400, {
            status: 'REJECTED',
            message: 'Missing required fields: productId, purchaseToken'
          });
        }

        // 2. Ownership check: If client sent ownerAppUserId, it must strictly match principal
        if (body.ownerAppUserId && body.ownerAppUserId !== principal.sub) {
          return sendJson(res, 403, {
            status: 'FORBIDDEN',
            message: `Caller identity '${principal.sub}' does not match requested ownerAppUserId '${body.ownerAppUserId}'`
          });
        }

        // Canonical owner is derived strictly from authenticated principal
        const verifiedRequest: VerificationRequest = {
          ...body,
          ownerAppUserId: principal.sub
        };

        const response = await verifierService.verifyPurchase(verifiedRequest);
        const httpCode = response.status === 'SUCCESS' ? 200 : response.status === 'REJECTED' ? 409 : 503;
        return sendJson(res, httpCode, response);
      } catch (err: unknown) {
        const msg = err instanceof Error ? err.message : 'Internal server error';
        return sendJson(res, 500, { status: 'TRANSIENT_ERROR', message: msg });
      }
    }

    if (url === '/api/v1/billing/restore' && method === 'POST') {
      // 1. Authenticate user
      let principal;
      try {
        principal = await authService.authenticateUser(req);
      } catch (err: unknown) {
        const statusCode = err instanceof AuthenticationError ? 401 : err instanceof AuthorizationError ? 403 : 401;
        const msg = err instanceof Error ? err.message : 'Authentication required';
        return sendJson(res, statusCode, { status: 'UNAUTHORIZED', message: msg });
      }

      try {
        const body = await parseJsonBody<RestoreRequest>(req);
        if (!Array.isArray(body.purchases)) {
          return sendJson(res, 400, {
            status: 'TRANSIENT_ERROR',
            message: 'Missing required field: purchases[]'
          });
        }

        // 2. Ownership check: If client sent ownerAppUserId, it must strictly match principal
        if (body.ownerAppUserId && body.ownerAppUserId !== principal.sub) {
          return sendJson(res, 403, {
            status: 'FORBIDDEN',
            message: `Caller identity '${principal.sub}' does not match requested ownerAppUserId '${body.ownerAppUserId}'`
          });
        }

        // Canonical owner is derived strictly from authenticated principal
        const restoreRequest: RestoreRequest = {
          ownerAppUserId: principal.sub,
          purchases: body.purchases
        };

        const response = await verifierService.restorePurchases(restoreRequest);
        return sendJson(res, 200, response);
      } catch (err: unknown) {
        const msg = err instanceof Error ? err.message : 'Internal server error';
        return sendJson(res, 500, { status: 'TRANSIENT_ERROR', message: msg });
      }
    }

    if (url === '/api/v1/billing/acknowledge' && method === 'POST') {
      // 1. Authenticate user
      let principal;
      try {
        principal = await authService.authenticateUser(req);
      } catch (err: unknown) {
        const statusCode = err instanceof AuthenticationError ? 401 : err instanceof AuthorizationError ? 403 : 401;
        const msg = err instanceof Error ? err.message : 'Authentication required';
        return sendJson(res, statusCode, { status: 'UNAUTHORIZED', message: msg });
      }

      try {
        const body = await parseJsonBody<AcknowledgeRequest>(req);
        if (!body.purchaseToken || !body.productId) {
          return sendJson(res, 400, { status: 'FAILED', message: 'Missing purchaseToken or productId' });
        }

        // 2. Authorization check: Token must exist in store and belong to the authenticated caller
        const record = await store.getRecordByToken(body.purchaseToken);
        if (!record) {
          return sendJson(res, 403, {
            status: 'FAILED',
            message: 'Purchase token must be verified before acknowledge'
          });
        }
        if (record.ownerAppUserId !== principal.sub) {
          return sendJson(res, 403, {
            status: 'FORBIDDEN',
            message: `Purchase token belongs to user '${record.ownerAppUserId}', not caller '${principal.sub}'`
          });
        }

        const ackResult = await ackService.acknowledge({
          purchaseToken: body.purchaseToken,
          productId: body.productId,
          productType: body.productType
        });
        const httpCode = ackResult.status === 'ACKNOWLEDGED' || ackResult.status === 'ALREADY_ACKNOWLEDGED' ? 200 : 503;
        return sendJson(res, httpCode, ackResult);
      } catch (err: unknown) {
        const msg = err instanceof Error ? err.message : 'Internal server error';
        return sendJson(res, 500, { status: 'FAILED', message: msg });
      }
    }

    if (url === '/api/v1/billing/rtdn' && method === 'POST') {
      // 1. Authenticate Pub/Sub push notification
      try {
        await authService.authenticatePubSub(req);
      } catch (err: unknown) {
        const statusCode = err instanceof AuthenticationError ? 401 : err instanceof AuthorizationError ? 403 : 401;
        const msg = err instanceof Error ? err.message : 'Pub/Sub authentication failed';
        return sendJson(res, statusCode, { status: 'UNAUTHORIZED', message: msg });
      }

      try {
        const body = await parseJsonBody<any>(req);
        const result = await rtdnHandler.handlePushNotification(body);
        const httpStatus = result.status === 'ERROR' ? 503 : 200;
        return sendJson(res, httpStatus, result);
      } catch (err: unknown) {
        const msg = err instanceof Error ? err.message : 'Internal server error';
        return sendJson(res, 500, { status: 'ERROR', message: msg });
      }
    }

    return sendJson(res, 404, { message: 'Not found' });
  });

  if (ackWorker) {
    server.on('close', () => {
      void ackWorker.stop();
    });
  }

  return server;
}

const defaultStore = new EntitlementStore();
const defaultGoogleApi = new ProductionGooglePlayBillingApi();
const defaultVerifierService = new BillingVerifierService(defaultGoogleApi, defaultStore, defaultLogger);
const defaultAckService = new AcknowledgeService(defaultGoogleApi, defaultStore, defaultLogger);
const defaultAckWorker = new AckWorker(defaultStore, defaultAckService, defaultLogger);
const defaultRtdnHandler = new RtdnHandler(defaultGoogleApi, defaultStore, defaultLogger);
const defaultAuthService = new AuthService();

const server = createHttpServer({
  store: defaultStore,
  googleApi: defaultGoogleApi,
  verifierService: defaultVerifierService,
  ackService: defaultAckService,
  ackWorker: defaultAckWorker,
  rtdnHandler: defaultRtdnHandler,
  authService: defaultAuthService,
  logger: defaultLogger
});

const isTestEnv = process.env.NODE_ENV === 'test' ||
  Boolean(process.env.NODE_TEST_CONTEXT) ||
  process.execArgv.includes('--test');

if (!isTestEnv) {
  defaultAckWorker.start();
  server.listen(PORT, () => {
    defaultLogger.info(`Billing Verifier Server listening on port ${PORT}`);
  });
}

export {
  server,
  defaultVerifierService as verifierService,
  defaultStore as store,
  defaultAuthService as authService,
  defaultAckService as ackService,
  defaultAckWorker as ackWorker,
  AckWorker
};
