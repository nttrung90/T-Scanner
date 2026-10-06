import test from 'node:test';
import assert from 'node:assert/strict';
import {EntitlementStore} from '../../backend/billing-verifier/src/store.ts';
import {BillingVerifierService} from '../../backend/billing-verifier/src/verifier.ts';
import {MockGooglePlayBillingApi,ProductionGooglePlayBillingApi} from '../../backend/billing-verifier/src/googlePlayClient.ts';
import {RtdnHandler} from '../../backend/billing-verifier/src/rtdnHandler.ts';
const logger={info(){},warn(){},error(){}};
const req={ownerAppUserId:'audit-A',productId:'tscanner_vip_yearly',productType:'subs' as const,purchaseToken:'synthetic-r5-token',clientPurchaseTimeMillis:1};
const sub=()=>({acknowledgementState:1,expiryTimeMillis:Date.now()+86400000,paymentState:1,autoRenewing:true});
const payload=(state?:string)=>({subscriptionState:state,acknowledgementState:'ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED',lineItems:[{productId:req.productId,expiryTime:new Date(Date.now()+(state==='SUBSCRIPTION_STATE_ON_HOLD'?-1000:86400000)).toISOString()}]});
test('B07 late RTDN must not overwrite newer verify expiry',async()=>{
 const store=new EntitlementStore(':memory:');const api=new MockGooglePlayBillingApi();const service=new BillingVerifierService(api,store,logger);
 try{api.registerSubscription(req.purchaseToken,async()=>sub());await service.verifyPurchase(req);
 let release!:()=>void,entered!:()=>void;const started=new Promise<void>(r=>entered=r),blocked=new Promise<void>(r=>release=r);let calls=0;
 api.registerSubscription(req.purchaseToken,async()=>{if(++calls===1){entered();await blocked;return sub();}return {...sub(),expiryTimeMillis:Date.now()-1000};});
 const old=new RtdnHandler(api,store,logger).processDeveloperNotification({version:'1',packageName:'com.tscanner.app',eventTimeMillis:Date.now(),subscriptionNotification:{version:'1',notificationType:2,purchaseToken:req.purchaseToken,subscriptionId:req.productId}});
 await started;await service.verifyPurchase(req);release();await old;
 assert.equal((await store.getRecordByToken(req.purchaseToken))?.entitlement.state,'EXPIRED');
 }finally{store.close();}
});
test('B01 first-binding late active must not resurrect committed expiry',async()=>{
 const store=new EntitlementStore(':memory:');const api=new MockGooglePlayBillingApi();const service=new BillingVerifierService(api,store,logger);
 try{
 let release!:()=>void,entered!:()=>void;const started=new Promise<void>(r=>entered=r),blocked=new Promise<void>(r=>release=r);let calls=0;
 api.registerSubscription(req.purchaseToken,async()=>{if(++calls===1){entered();await blocked;return sub();}return {...sub(),expiryTimeMillis:Date.now()-1000};});
 const old=service.verifyPurchase(req);await started;await service.verifyPurchase(req);release();await old;
 assert.equal((await store.getRecordByToken(req.purchaseToken))?.entitlement.state,'EXPIRED');
 }finally{store.close();}
});
test('B02 conflict must not discard authoritative expiry and return active',async()=>{
 const store=new EntitlementStore(':memory:');const api=new MockGooglePlayBillingApi();const service=new BillingVerifierService(api,store,logger);
 try{
 api.registerSubscription(req.purchaseToken,async()=>sub());await service.verifyPurchase(req);
 let release!:()=>void,entered!:()=>void;const started=new Promise<void>(r=>entered=r),blocked=new Promise<void>(r=>release=r);let calls=0;
 api.registerSubscription(req.purchaseToken,async()=>{if(++calls===1){entered();await blocked;return {...sub(),expiryTimeMillis:Date.now()-1000};}return sub();});
 const expiry=service.verifyPurchase(req);await started;await service.verifyPurchase(req);
 api.registerSubscription(req.purchaseToken,async()=>({...sub(),expiryTimeMillis:Date.now()-1000}));release();await expiry;
 assert.equal((await store.getRecordByToken(req.purchaseToken))?.entitlement.state,'EXPIRED');
 }finally{store.close();}
});
test('B03 on-hold V2 must invalidate stored active grant',async()=>{
 const store=new EntitlementStore(':memory:');let state='SUBSCRIPTION_STATE_ACTIVE';
 const api=new ProductionGooglePlayBillingApi({tokenProvider:async()=> 'synthetic',fetchFn:async()=>new Response(JSON.stringify(payload(state)),{status:200})});
 const service=new BillingVerifierService(api,store,logger);
 try{await service.verifyPurchase(req);state='SUBSCRIPTION_STATE_ON_HOLD';await service.verifyPurchase(req);
 assert.equal((await store.getRecordByToken(req.purchaseToken))?.entitlement.state,'ON_HOLD');
 }finally{store.close();}
});
test('B04 missing V2 lifecycle state must not grant active',async()=>{
 const store=new EntitlementStore(':memory:');
 const api=new ProductionGooglePlayBillingApi({tokenProvider:async()=> 'synthetic',fetchFn:async()=>new Response(JSON.stringify(payload()),{status:200})});
 try{assert.notEqual((await new BillingVerifierService(api,store,logger).verifyPurchase(req)).status,'SUCCESS');}finally{store.close();}
});
test('B05 client metadata must not suppress refresh of known refunded receipt',async()=>{
 const store=new EntitlementStore(':memory:');const api=new MockGooglePlayBillingApi();const service=new BillingVerifierService(api,store,logger);
 try{const r={...req,productId:'tscanner_vip_lifetime',productType:'inapp' as const};
 api.registerInApp(r.purchaseToken,async()=>({purchaseState:0,consumptionState:0,acknowledgementState:1,purchaseTimeMillis:1}));await service.verifyPurchase(r);
 api.registerInApp(r.purchaseToken,async()=>({purchaseState:1,consumptionState:0,acknowledgementState:1,purchaseTimeMillis:1}));
 const result=await service.restorePurchases({ownerAppUserId:r.ownerAppUserId,purchases:[{purchaseToken:r.purchaseToken,productId:'invalid-client-sku',productType:'inapp'}]});
 assert.equal(result.snapshot.entitlements[0].state,'REVOKED');
 }finally{store.close();}
});
test('B06 control empty restore refreshes refund with no conflicting metadata',async()=>{
 const store=new EntitlementStore(':memory:');const api=new MockGooglePlayBillingApi();const service=new BillingVerifierService(api,store,logger);
 try{const r={...req,productId:'tscanner_vip_lifetime',productType:'inapp' as const};
 api.registerInApp(r.purchaseToken,async()=>({purchaseState:0,consumptionState:0,acknowledgementState:1,purchaseTimeMillis:1}));await service.verifyPurchase(r);
 api.registerInApp(r.purchaseToken,async()=>({purchaseState:1,consumptionState:0,acknowledgementState:1,purchaseTimeMillis:1}));
 assert.equal((await service.restorePurchases({ownerAppUserId:r.ownerAppUserId,purchases:[]})).snapshot.entitlements[0].state,'REVOKED');
 }finally{store.close();}
});
