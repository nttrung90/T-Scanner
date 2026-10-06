package com.tscanner.app
import com.tscanner.app.data.model.UserProfile
import com.tscanner.app.utils.AppAuthManager
import com.tscanner.app.utils.BillingManager
import com.tscanner.app.utils.billing.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder

class RootVipAuthAuditTest {
 @get:Rule val tmp=TemporaryFolder()
 private lateinit var ctx:BillingTestContext
 private lateinit var client:FakeBillingClientWrapper
 @Before fun setup(){
  androidx.arch.core.executor.ArchTaskExecutor.getInstance().setDelegate(object:androidx.arch.core.executor.TaskExecutor(){override fun executeOnDiskIO(r:Runnable)=r.run();override fun postToMainThread(r:Runnable)=r.run();override fun isMainThread()=true})
  BillingManager.resetInstanceForTesting();AppAuthManager.resetForTesting();BillingEntitlementStore.resetInstanceForTesting()
  ctx=BillingTestContext(tmp.root);client=FakeBillingClientWrapper().apply{isReadyValue=true};AppAuthManager.setCurrentUserForTesting(UserProfile("A","A@example.test","A",idToken=null))
 }
 @After fun cleanup(){BillingManager.resetInstanceForTesting();AppAuthManager.resetForTesting();BillingEntitlementStore.resetInstanceForTesting();androidx.arch.core.executor.ArchTaskExecutor.getInstance().setDelegate(null)}
 private fun jwt(payload:String)="eyJhbGciOiJSUzI1NiJ9."+java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray())+".synthetic-signature"
 @Test fun C01DefaultProfileMustNotInventCredential(){
  assertNull("Production model invents a test token when none was acquired",UserProfile("A","A@example.test","A").idToken)
 }
 @Test fun C02MalformedProductionTokenMustNotBeAuthReady(){
  val v=PlayPurchaseVerifier(backendUrl="https://audit.invalid",tokenProvider={"not-a-jwt"})
  assertFalse("Malformed credential treated as authenticated-ready",v.isAuthReady())
 }
 @Test fun C03JwtWithoutExpiryMustNotBeAuthReady(){
  val v=PlayPurchaseVerifier(backendUrl="https://audit.invalid",tokenProvider={jwt("""{"sub":"A"}""")})
  assertFalse("JWT missing mandatory expiration treated as ready",v.isAuthReady())
 }
 @Test fun C04RestoreMustEnforceHttpsBeforeSendingBearer()=runBlocking{
  var calls=0
  val token=jwt("""{"sub":"A","exp":4102444800}""")
  val v=PlayPurchaseVerifier(backendUrl="http://audit.invalid",tokenProvider={token},httpTransport={_,_,_,_->calls++;VerifierHttpResponse(503,"{}")})
  assertFalse(v.isBackendConfigured())
  v.restorePurchases(RestoreRequest("A"))
  assertEquals("Restore sent request even though transport configuration is rejected",0,calls)
 }
 @Test fun C05Restore401MustExposeAuthenticationRecovery(){
  val token=jwt("""{"sub":"A","exp":1}""")
  AppAuthManager.setCurrentUserForTesting(UserProfile("A","A@example.test","A",idToken=token))
  val v=PlayPurchaseVerifier(backendUrl="https://audit.invalid",tokenProvider={token},httpTransport={_,_,_,_->VerifierHttpResponse(401,"""{"message":"Phiên đăng nhập đã hết hạn"}""")})
  val mgr=BillingManager.createInstanceForTesting(ctx,{client},v)
  var message="";mgr.restorePurchases{_,text->message=text}
  assertTrue("Restore authentication failure became generic Google Play connection error: $message",message.contains("đăng nhập",ignoreCase=true)||message.contains("Phiên",ignoreCase=true))
 }
 @Test fun C06ControlUnexpiredTokenIsReady(){
  val v=PlayPurchaseVerifier(backendUrl="https://audit.invalid",tokenProvider={jwt("""{"sub":"A","exp":4102444800}""")})
  assertTrue(v.isAuthReady())
 }
}
