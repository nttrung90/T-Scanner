import test from 'node:test';
import assert from 'node:assert/strict';
import {EntitlementStore} from '../../backend/billing-verifier/src/store.ts';
import {BillingVerifierService} from '../../backend/billing-verifier/src/verifier.ts';
import {GooglePlayApiError,MockGooglePlayBillingApi,ProductionGooglePlayBillingApi} from '../../backend/billing-verifier/src/googlePlayClient.ts';
const logger={info(){},warn(){},error(){}};
const req={ownerAppUserId:'audit-A',productId:'tscanner_vip_lifetime',productType:'inapp' as const,purchaseToken:'synthetic-r6-receipt',clientPurchaseTimeMillis:1};
test('B01 unresolved known receipt must not return fresh full restore success',async()=>{
 const store=new EntitlementStore(':memory:');const api=new MockGooglePlayBillingApi();const service=new BillingVerifierService(api,store,logger);
 try{api.registerInApp(req.purchaseToken,async()=>({purchaseState:0,consumptionState:0,acknowledgementState:1,purchaseTimeMillis:1}));await service.verifyPurchase(req);
 api.registerInApp(req.purchaseToken,async()=>{throw new GooglePlayApiError('Token not found',404,false)});
 const result=await service.restorePurchases({ownerAppUserId:req.ownerAppUserId,purchases:[]});
 assert.equal(result.results[0].status,'REJECTED');
 assert.notEqual(result.status,'SUCCESS','Rejected refresh was reported as fresh SUCCESS containing stale ACTIVE');
 }finally{store.close();}
});
test('B02 canonical pending-purchase-canceled state must be parsed for linked-token recovery',async()=>{
 const api=new ProductionGooglePlayBillingApi({tokenProvider:async()=> 'synthetic',fetchFn:async()=>new Response(JSON.stringify({subscriptionState:'SUBSCRIPTION_STATE_PENDING_PURCHASE_CANCELED',linkedPurchaseToken:'synthetic-old-receipt',lineItems:[{productId:'tscanner_vip_yearly'}]}),{status:200})});
 const result=await api.getSubscription('com.tscanner.app','tscanner_vip_yearly','synthetic-new-canceled-receipt');
 assert.equal(result.subscriptionState,'SUBSCRIPTION_STATE_PENDING_PURCHASE_CANCELED');
 assert.equal(result.linkedPurchaseToken,'synthetic-old-receipt');
});
test('B03 control mixed restore accurately declares PARTIAL on transient refresh',async()=>{
 const store=new EntitlementStore(':memory:');const api=new MockGooglePlayBillingApi();const service=new BillingVerifierService(api,store,logger);
 try{api.registerInApp('good',async()=>({purchaseState:0,consumptionState:0,acknowledgementState:1,purchaseTimeMillis:1}));
 api.registerInApp('offline',async()=>{throw new GooglePlayApiError('Temporary upstream failure',503,true)});
 const result=await service.restorePurchases({ownerAppUserId:req.ownerAppUserId,purchases:[{...req,purchaseToken:'good'},{...req,purchaseToken:'offline'}]});
 assert.equal(result.status,'PARTIAL');assert.equal(result.results.filter(r=>r.status==='TRANSIENT_ERROR').length,1);
 }finally{store.close();}
});
