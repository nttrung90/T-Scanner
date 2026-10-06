package com.tscanner.app
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.tscanner.app.data.model.UserProfile
import com.tscanner.app.utils.AppAuthManager
import com.tscanner.app.utils.BillingManager
import com.tscanner.app.utils.billing.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder

class VipRound6AuditTest {
 @get:Rule val tmp=TemporaryFolder()
 private lateinit var ctx:BillingTestContext
 private lateinit var client:FakeBillingClientWrapper
 private val store get()=BillingEntitlementStore.getInstance()
 private fun ent()=BillingEntitlement(id="GOOGLE_PLAY_SUBSCRIPTION_receipt",ownerAppUserId="A",productId=BillingManager.PRODUCT_VIP_YEARLY,productType="subs",purchaseToken="receipt",state=EntitlementState.VERIFIED_ACTIVE,expiryTimeMillis=System.currentTimeMillis()+86400000,snapshotVersion=2)
 @Before fun setup(){
  androidx.arch.core.executor.ArchTaskExecutor.getInstance().setDelegate(object:androidx.arch.core.executor.TaskExecutor(){override fun executeOnDiskIO(r:Runnable)=r.run();override fun postToMainThread(r:Runnable)=r.run();override fun isMainThread()=true})
  BillingManager.resetInstanceForTesting();AppAuthManager.resetForTesting();BillingEntitlementStore.resetInstanceForTesting()
  ctx=BillingTestContext(tmp.root);client=FakeBillingClientWrapper().apply{isReadyValue=true};AppAuthManager.setCurrentUserForTesting(UserProfile("A","A@example.test","A"))
 }
 @After fun cleanup(){BillingManager.resetInstanceForTesting();AppAuthManager.resetForTesting();BillingEntitlementStore.resetInstanceForTesting();androidx.arch.core.executor.ArchTaskExecutor.getInstance().setDelegate(null)}
 private fun reconcile(v:PurchaseVerifier,c:Context=ctx,onResult:(ReconciliationResult)->Unit={}){
  BillingReconciliation(c,client,{_,done->done(true)},BillingOperationContext(ownerAppUserId="A",sessionGeneration=AppAuthManager.getSessionGeneration(),operationType=BillingOperationType.RESTORE),v,Dispatchers.Unconfined).reconcile(onResult)
 }
 private fun remote(result:RestoreResult)=object:PurchaseVerifier{
  override suspend fun verifyPurchase(request:VerificationRequest)=VerificationResult.MissingBackendGate("unused")
  override suspend fun restorePurchases(request:RestoreRequest)=result
 }
 @Test fun A01PendingPlayPurchaseMustNotSuppressKnownAccountRefresh(){
  AppAuthManager.applyEntitlement(ctx,ent())
  client.subsPurchases.add(createTestPurchase(BillingManager.PRODUCT_VIP_YEARLY,purchaseState=com.android.billingclient.api.Purchase.PurchaseState.PENDING))
  var restores=0
  reconcile(object:PurchaseVerifier{
   override suspend fun verifyPurchase(request:VerificationRequest)=VerificationResult.MissingBackendGate("unused")
   override suspend fun restorePurchases(request:RestoreRequest):RestoreResult{restores++;return RestoreResult.Success(UserEntitlementSnapshot("A",listOf(ent().copy(state=EntitlementState.REVOKED,snapshotVersion=3))))}
  })
  assertEquals("Pending local candidate prevented refresh of different known entitlement",1,restores)
  assertFalse(store.getSnapshot(ctx,"A").isVipActive())
 }
 @Test fun A02NonemptyPlayListMustStillRefreshOtherServerReceipts(){
  val lifetime=ent().copy(id="GOOGLE_PLAY_INAPP_server-only-lifetime",productId=BillingManager.PRODUCT_VIP_LIFETIME,productType="inapp",expiryTimeMillis=null,source=EntitlementSource.GOOGLE_PLAY_INAPP,purchaseToken="server-only-lifetime")
  AppAuthManager.applyEntitlement(ctx,lifetime)
  client.subsPurchases.add(createTestPurchase(BillingManager.PRODUCT_VIP_YEARLY,acknowledged=true))
  var restores=0
  val v=object:PurchaseVerifier{
   override suspend fun verifyPurchase(request:VerificationRequest)=VerificationResult.Rejected(RejectionReason.PURCHASE_EXPIRED,"Paused device subscription",ent().copy(id="GOOGLE_PLAY_SUBSCRIPTION_${request.purchaseToken}",purchaseToken=request.purchaseToken,state=EntitlementState.PAUSED,snapshotVersion=3))
   override suspend fun restorePurchases(request:RestoreRequest):RestoreResult{restores++;return RestoreResult.Success(UserEntitlementSnapshot("A",listOf(lifetime.copy(state=EntitlementState.REVOKED,snapshotVersion=4))))}
  }
  BillingManager.createInstanceForTesting(ctx,{client},v).syncPurchases{}
  assertEquals("Known server receipts skipped whenever device catalog is nonempty",1,restores)
  assertFalse("Refunded remote-only lifetime remained VIP",AppAuthManager.isUserVip())
 }
 @Test fun A03PartialMustNotBePresentedAsCompleteRestore(){
  var result:ReconciliationResult?=null
  reconcile(remote(RestoreResult.Partial(UserEntitlementSnapshot("A",listOf(ent())),"One receipt could not be refreshed"))){result=it}
  assertTrue("Partial result discarded all failure metadata",result !is ReconciliationResult.Restored || (result as ReconciliationResult.Restored).failedCount>0)
 }
 @Test fun A04DestroyedManagerMustNotCommitSuspendedRestore(){
  val deferred=CompletableDeferred<RestoreResult>()
  val v=object:PurchaseVerifier{
   override suspend fun verifyPurchase(request:VerificationRequest)=VerificationResult.MissingBackendGate("unused")
   override suspend fun restorePurchases(request:RestoreRequest)=deferred.await()
  }
  val mgr=BillingManager.createInstanceForTesting(ctx,{client},v)
  var callbacks=0;mgr.restorePurchases{_,_->callbacks++}
  assertEquals("Fixture must reach suspended restore",0,callbacks)
  mgr.destroy();deferred.complete(RestoreResult.Success(UserEntitlementSnapshot("A",listOf(ent()))))
  assertTrue("Restore outlived manager scope and committed after destroy",store.getSnapshot(ctx,"A").entitlements.isEmpty())
  assertEquals(0,callbacks)
 }
 @Test fun A05ProjectionFailureMustNotProduceSuccessfulRestore(){
  var writes=0
  val prefs=ctx.getSharedPreferences("tscanner_billing_entitlements",Context.MODE_PRIVATE)
  val wrapped=object:SharedPreferences by prefs{
   override fun edit():SharedPreferences.Editor{val editor=prefs.edit();return object:SharedPreferences.Editor by editor{override fun commit():Boolean{writes++;return if(writes==2)false else editor.commit()}}}
  }
  val failingCtx=object:ContextWrapper(ctx){override fun getSharedPreferences(name:String?,mode:Int):SharedPreferences=if(name=="tscanner_billing_entitlements")wrapped else ctx.getSharedPreferences(name,mode)}
  var result:ReconciliationResult?=null
  reconcile(remote(RestoreResult.Success(UserEntitlementSnapshot("A",listOf(ent())))),failingCtx){result=it}
  assertTrue("Success despite second entitlement write failing and profile remaining Free",result !is ReconciliationResult.Restored || AppAuthManager.isUserVip())
 }
 @Test fun A06MissingIdTokenMustRequestReauthentication(){
  val user=UserProfile("A","A@example.test","A",idToken=null);AppAuthManager.setCurrentUserForTesting(user)
  assertTrue("Missing token fell through to ActivateVip",com.tscanner.app.ui.dialogs.VipUpgradeActionResolver.resolveUpgradeAction(user,true) is com.tscanner.app.ui.dialogs.VipUpgradeActionResolver.Action.RequestSignIn)
 }
 private fun restoreBody(type:String="subs",source:String="GOOGLE_PLAY_SUBSCRIPTION")="""{"status":"SUCCESS","snapshot":{"ownerAppUserId":"A","entitlements":[{"ownerAppUserId":"A","productId":"tscanner_vip_yearly","productType":"$type","source":"$source","purchaseToken":"receipt","state":"VERIFIED_ACTIVE","expiryTimeMillis":4102444800000,"snapshotVersion":2}]}}"""
 @Test fun A07RestoreWrongCatalogTypeMustFail()=runBlocking{
  val v=PlayPurchaseVerifier(backendUrl="https://audit.invalid",httpTransport={_,_,_,_->VerifierHttpResponse(200,restoreBody(type="inapp"))})
  assertFalse(v.restorePurchases(RestoreRequest("A")) is RestoreResult.Success)
 }
 @Test fun A08RestoreWrongProviderSourceMustFail()=runBlocking{
  val v=PlayPurchaseVerifier(backendUrl="https://audit.invalid",httpTransport={_,_,_,_->VerifierHttpResponse(200,restoreBody(source="PROMOTIONAL"))})
  assertFalse(v.restorePurchases(RestoreRequest("A")) is RestoreResult.Success)
 }
 @Test fun A09ControlGoodRestoreUpdatesWatermarkGate(){
  reconcile(remote(RestoreResult.Success(UserEntitlementSnapshot("A",listOf(ent())))))
  assertTrue(AppAuthManager.isUserVip())
  assertFalse(com.tscanner.app.utils.WatermarkHelper.shouldApplyWatermark(true))
 }
}
