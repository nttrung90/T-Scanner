package com.tscanner.app

import android.app.Activity
import androidx.arch.core.executor.ArchTaskExecutor
import androidx.arch.core.executor.TaskExecutor
import com.tscanner.app.data.model.UserProfile
import com.tscanner.app.utils.*
import com.tscanner.app.utils.billing.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import java.util.Base64

/** Audit-only synthetic auth and delayed-purchase probes calling production logic. */
class RootRound8AuthAuditTest {
 @get:Rule val tmp=TemporaryFolder()
 private lateinit var ctx:BillingTestContext
 private lateinit var client:FakeBillingClientWrapper
 private fun jwt(exp:Long=4102444800L,subject:String="A")="eyJhbGciOiJSUzI1NiJ9."+Base64.getUrlEncoder().withoutPadding().encodeToString("""{"sub":"$subject","exp":$exp}""".toByteArray())+".synthetic"
 private fun user(id:String="A",token:String?=jwt(subject=id))=UserProfile(id,"$id@example.test",id,idToken=token)
 @Before fun setup(){
  ArchTaskExecutor.getInstance().setDelegate(object:TaskExecutor(){override fun executeOnDiskIO(r:Runnable)=r.run();override fun postToMainThread(r:Runnable)=r.run();override fun isMainThread()=true})
  BillingManager.resetInstanceForTesting();AppAuthManager.resetForTesting();BillingEntitlementStore.resetInstanceForTesting()
  ctx=BillingTestContext(tmp.root);client=FakeBillingClientWrapper().apply{isReadyValue=true}
  AppAuthManager.setCurrentUserForTesting(user())
 }
 @After fun cleanup(){BillingManager.resetInstanceForTesting();AppAuthManager.resetForTesting();BillingEntitlementStore.resetInstanceForTesting();ArchTaskExecutor.getInstance().setDelegate(null)}
 @Test fun C801RestoreExpiredCredentialMustStopBeforeTransport()=runBlocking{
  var calls=0
  val v=PlayPurchaseVerifier(backendUrl="https://audit.invalid",tokenProvider={jwt(1)},httpTransport={_,_,_,_->calls++;VerifierHttpResponse(401,"{}")})
  assertFalse(v.isAuthReady())
  val result=v.restorePurchases(RestoreRequest("A"))
  assertEquals("Expired bearer was sent despite not auth-ready",0,calls)
  assertTrue(result is RestoreResult.AuthRequired)
 }
 @Test fun C802RestoreMalformedCredentialMustStopBeforeTransport()=runBlocking{
  var calls=0
  val v=PlayPurchaseVerifier(backendUrl="https://audit.invalid",tokenProvider={"malformed-synthetic"},httpTransport={_,_,_,_->calls++;VerifierHttpResponse(401,"{}")})
  assertFalse(v.isAuthReady())
  v.restorePurchases(RestoreRequest("A"))
  assertEquals("Malformed bearer passed restore transport guard",0,calls)
 }
 @Test fun C803VerifyMustUseSameHttpsGuardAsRestore()=runBlocking{
  var calls=0
  val v=PlayPurchaseVerifier(backendUrl="http://audit.invalid",tokenProvider={jwt()},httpTransport={_,_,_,_->calls++;VerifierHttpResponse(503,"{}")})
  assertFalse(v.isBackendConfigured())
  v.verifyPurchase(VerificationRequest("A",BillingManager.PRODUCT_VIP_YEARLY,"subs","synthetic-receipt"))
  assertEquals("Verify bypassed its HTTPS configuration guard",0,calls)
 }
 private class TestActivity:Activity(){override fun isFinishing()=false;override fun isDestroyed()=false}
 private class Listener:VipPurchaseActionCoordinator.Listener{
  var launches=0;var signIns=0
  override fun onLoading(message:String){}
  override fun onError(message:String,canRetry:Boolean){}
  override fun onLaunchSuccess(){launches++}
  override fun onRequestSignIn(){signIns++}
  override fun onShowSignInPrompt(){signIns++}
 }
 private class DelayedLauncher(private val manager:BillingManager):VipPurchaseLauncher{
  override var connectionState=BillingManager.ConnectionState.CONNECTED
  var productAvailable=true
  var connectionCallback:((Boolean)->Unit)?=null
  var productCallback:((Boolean)->Unit)?=null
  override fun isProductDetailsAvailable(productId:String)=productAvailable
  override fun isVerifierConfigured()=manager.isVerifierConfigured()
  override fun launchBillingFlow(activity:Activity,productId:String,onError:((String)->Unit)?)=manager.launchBillingFlow(activity,productId,onError)
  override fun startConnection(onComplete:((Boolean)->Unit)?){connectionCallback=onComplete}
  override fun queryProducts(onComplete:((Boolean)->Unit)?){productCallback=onComplete}
 }
 private fun preparedLauncher():DelayedLauncher{
  client.productDetailsListToReturn.add(createTestProductDetails(BillingManager.PRODUCT_VIP_YEARLY))
  val v=PlayPurchaseVerifier(backendUrl="https://audit.invalid",tokenProvider={AppAuthManager.getSessionToken()},httpTransport={_,_,_,_->VerifierHttpResponse(503,"{}")})
  val manager=BillingManager.createInstanceForTesting(ctx,{client},v)
  manager.queryAllProducts()
  assertTrue("Fixture must prepare real product cache",manager.products.value.isNotEmpty())
  return DelayedLauncher(manager)
 }
 @Test fun C804DelayedPurchaseMustNotLaunchUnderDifferentAppOwner(){
  val launcher=preparedLauncher().apply{connectionState=BillingManager.ConnectionState.DISCONNECTED}
  val listener=Listener();val coordinator=VipPurchaseActionCoordinator(launcher)
  coordinator.onUpgradeClicked(TestActivity(),AppAuthManager.getCurrentUser(),true,listener=listener)
  assertNotNull("Fixture must await connection",launcher.connectionCallback)
  assertNull(client.lastLaunchedParams)
  AppAuthManager.setCurrentUserForTesting(user("B"))
  launcher.connectionState=BillingManager.ConnectionState.CONNECTED
  launcher.connectionCallback!!.invoke(true)
  assertNull("Purchase initiated as A launched under B after reconnect",client.lastLaunchedParams)
  assertEquals(0,listener.launches)
 }
 @Test fun C805CredentialExpiringDuringProductAwaitMustNotLaunchPlayPayment(){
  val launcher=preparedLauncher().apply{productAvailable=false}
  val listener=Listener();val coordinator=VipPurchaseActionCoordinator(launcher)
  coordinator.onUpgradeClicked(TestActivity(),AppAuthManager.getCurrentUser(),true,listener=listener)
  assertNotNull("Fixture must await products",launcher.productCallback)
  AppAuthManager.setCurrentUserForTesting(user(token=jwt(1)))
  launcher.productAvailable=true;launcher.productCallback!!.invoke(true)
  assertNull("Paid flow launched after credential became expired",client.lastLaunchedParams)
  assertEquals(0,listener.launches)
 }
 @Test fun C806ControlValidRestoreUsesHttpsTransport()=runBlocking{
  var calls=0
  val v=PlayPurchaseVerifier(backendUrl="https://audit.invalid",tokenProvider={jwt()},httpTransport={_,_,_,_->calls++;VerifierHttpResponse(200,"""{"status":"SUCCESS","snapshot":{"ownerAppUserId":"A","entitlements":[]}}""")})
  assertTrue(v.restorePurchases(RestoreRequest("A")) is RestoreResult.Success)
  assertEquals(1,calls)
 }
 @Test fun C807ControlSameOwnerDelayedPurchaseStillLaunches(){
  val launcher=preparedLauncher().apply{connectionState=BillingManager.ConnectionState.DISCONNECTED}
  val listener=Listener();val coordinator=VipPurchaseActionCoordinator(launcher)
  coordinator.onUpgradeClicked(TestActivity(),AppAuthManager.getCurrentUser(),true,listener=listener)
  launcher.connectionState=BillingManager.ConnectionState.CONNECTED;launcher.connectionCallback!!.invoke(true)
  assertNotNull(client.lastLaunchedParams);assertEquals(1,listener.launches)
 }
 @Test fun C808ControlMissingCredentialAlreadyStopsRestore()=runBlocking{
  var calls=0
  val v=PlayPurchaseVerifier(backendUrl="https://audit.invalid",tokenProvider={null},httpTransport={_,_,_,_->calls++;VerifierHttpResponse(401,"{}")})
  assertTrue(v.restorePurchases(RestoreRequest("A")) is RestoreResult.AuthRequired)
  assertEquals(0,calls)
 }
}
