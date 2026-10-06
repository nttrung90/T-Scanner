package com.tscanner.app
import com.tscanner.app.data.model.UserProfile
import com.tscanner.app.utils.AppAuthManager
import com.tscanner.app.utils.BillingManager
import com.tscanner.app.utils.billing.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder

class VipRound5AuditTest {
 @get:Rule val tmp=TemporaryFolder()
 private lateinit var ctx:BillingTestContext
 private lateinit var client:FakeBillingClientWrapper
 private val store get()=BillingEntitlementStore.getInstance()
 private fun ent(version:Long=1)=BillingEntitlement(id="GOOGLE_PLAY_SUBSCRIPTION_receipt",ownerAppUserId="A",productId=BillingManager.PRODUCT_VIP_YEARLY,productType="subs",purchaseToken="receipt",state=EntitlementState.VERIFIED_ACTIVE,expiryTimeMillis=System.currentTimeMillis()+86400000,snapshotVersion=version)
 @Before fun setup(){
  androidx.arch.core.executor.ArchTaskExecutor.getInstance().setDelegate(object:androidx.arch.core.executor.TaskExecutor(){override fun executeOnDiskIO(r:Runnable)=r.run();override fun postToMainThread(r:Runnable)=r.run();override fun isMainThread()=true})
  BillingManager.resetInstanceForTesting();AppAuthManager.resetForTesting();BillingEntitlementStore.resetInstanceForTesting()
  ctx=BillingTestContext(tmp.root);client=FakeBillingClientWrapper().apply{isReadyValue=true};AppAuthManager.setCurrentUserForTesting(UserProfile("A","A@example.test","A"))
 }
 @After fun cleanup(){BillingManager.resetInstanceForTesting();AppAuthManager.resetForTesting();BillingEntitlementStore.resetInstanceForTesting();androidx.arch.core.executor.ArchTaskExecutor.getInstance().setDelegate(null)}
 private fun response(owner:String="A",product:String="tscanner_vip_yearly",state:String="VERIFIED_ACTIVE")="""{"status":"SUCCESS","snapshot":{"ownerAppUserId":"$owner","entitlements":[{"ownerAppUserId":"$owner","productId":"$product","productType":"subs","purchaseToken":"receipt","state":"$state","expiryTimeMillis":4102444800000,"snapshotVersion":2}]}}"""
 private fun verifier(body:String,code:Int=200)=PlayPurchaseVerifier(backendUrl="https://audit.invalid",httpTransport={_,_,_,_->VerifierHttpResponse(code,body)})
 @Test fun A01RestoreWrongOwnerMustFail()=runBlocking{
  assertFalse(verifier(response(owner="B")).restorePurchases(RestoreRequest("A",emptyList())) is RestoreResult.Success)
 }
 @Test fun A02RestoreUnknownSkuMustFail()=runBlocking{
  assertFalse(verifier(response(product="unrelated-product")).restorePurchases(RestoreRequest("A",emptyList())) is RestoreResult.Success)
 }
 @Test fun A03RestoreInvalidItemMustNotBecomeSuccessEmpty()=runBlocking{
  assertFalse(verifier(response(state="UNKNOWN")).restorePurchases(RestoreRequest("A",emptyList())) is RestoreResult.Success)
 }
 @Test fun A04Http400MustNotAcceptSuccessBody()=runBlocking{
  assertFalse(verifier(response(),400).restorePurchases(RestoreRequest("A",emptyList())) is RestoreResult.Success)
 }
 private fun reconcile(v:PurchaseVerifier,onResult:(ReconciliationResult)->Unit){
  BillingReconciliation(ctx,client,{_,done->done(true)},BillingOperationContext(ownerAppUserId="A",sessionGeneration=AppAuthManager.getSessionGeneration(),operationType=BillingOperationType.RESTORE),v,Dispatchers.Unconfined).reconcile(onResult)
 }
 @Test fun A05LateRestoreAfterAccountSwitchMustNotWriteOrSignal(){
  val deferred=CompletableDeferred<RestoreResult>()
  val v=object:PurchaseVerifier{override suspend fun verifyPurchase(request:VerificationRequest)=VerificationResult.MissingBackendGate("unused");override suspend fun restorePurchases(request:RestoreRequest)=deferred.await()}
  var callbacks=0;reconcile(v){callbacks++}
  assertEquals("Fixture must reach suspended restore",0,callbacks)
  AppAuthManager.setCurrentUserForTesting(UserProfile("B","B@example.test","B"))
  deferred.complete(RestoreResult.Success(UserEntitlementSnapshot("A",listOf(ent()))))
  assertEquals("Stale restore committed before manager could reject callback",0,store.getSnapshot(ctx,"A").entitlements.size)
  assertEquals(0,callbacks)
 }
 @Test fun A06StaleActiveSnapshotMustNotReportRestoredAgainstNewerRevocation(){
  store.applyEntitlement(ctx,ent(10).copy(state=EntitlementState.REVOKED))
  val v=object:PurchaseVerifier{override suspend fun verifyPurchase(request:VerificationRequest)=VerificationResult.MissingBackendGate("unused");override suspend fun restorePurchases(request:RestoreRequest)=RestoreResult.Success(UserEntitlementSnapshot("A",listOf(ent(9))))}
  var result:ReconciliationResult?=null;reconcile(v){result=it}
  assertFalse("Reports restored from rejected old response rather than committed merged state",result is ReconciliationResult.Restored)
 }
 @Test fun A07ControlValidRestoreParses()=runBlocking{
  assertTrue(verifier(response()).restorePurchases(RestoreRequest("A",emptyList())) is RestoreResult.Success)
 }
 @Test fun A08PartialRestoreMustProjectCommittedEntitlementToCurrentProfile(){
  val v=object:PurchaseVerifier{override suspend fun verifyPurchase(request:VerificationRequest)=VerificationResult.MissingBackendGate("unused");override suspend fun restorePurchases(request:RestoreRequest)=RestoreResult.Partial(UserEntitlementSnapshot("A",listOf(ent())))}
  reconcile(v){}
  assertTrue(store.getSnapshot(ctx,"A").isVipActive())
  assertTrue("Persisted VIP not projected to profile used by watermark and Drive",AppAuthManager.isUserVip())
 }
 @Test fun A09ExpiredSessionMustOfferReauthentication(){
  val raw=java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("""{"sub":"A","exp":1}""".toByteArray())
  val user=UserProfile("A","A@example.test","A",idToken="eyJhbGciOiJSUzI1NiJ9.$raw.synthetic")
  AppAuthManager.setCurrentUserForTesting(user)
  val remote=PlayPurchaseVerifier(backendUrl="https://audit.invalid",tokenProvider={AppAuthManager.getSessionToken()})
  val launcher=object:com.tscanner.app.utils.VipPurchaseLauncher{
   override val connectionState=BillingManager.ConnectionState.CONNECTED
   override fun isVerifierConfigured()=remote.isConfigured()
   override fun isProductDetailsAvailable(productId:String)=true
   override fun launchBillingFlow(activity:android.app.Activity,productId:String,onError:((String)->Unit)?)=false
   override fun startConnection(onComplete:((Boolean)->Unit)?){onComplete?.invoke(true)}
   override fun queryProducts(onComplete:((Boolean)->Unit)?){onComplete?.invoke(true)}
  }
  var signIn=0
  com.tscanner.app.utils.VipPurchaseActionCoordinator(launcher).onUpgradeClicked(null,user,true,listener=object:com.tscanner.app.utils.VipPurchaseActionCoordinator.Listener{
   override fun onLoading(message:String){}
   override fun onError(message:String,canRetry:Boolean){}
   override fun onLaunchSuccess(){}
   override fun onRequestSignIn(){signIn++}
   override fun onShowSignInPrompt(){signIn++}
  })
  assertEquals("Expired persisted session only shows nonretryable service error",1,signIn)
 }
}

