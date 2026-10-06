package com.tscanner.app

import com.tscanner.app.utils.billing.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*

/** Production parser probes with synthetic HTTP payloads; no real network or credentials. */
class RootVipParserAuditTest {
 private fun item(token: String = "old-active") = JSONObject().apply {
  put("id", "GOOGLE_PLAY_SUBSCRIPTION_$token")
  put("ownerAppUserId", "A");put("productId", "tscanner_vip_yearly");put("productType", "subs")
  put("source", "GOOGLE_PLAY_SUBSCRIPTION");put("purchaseToken", token);put("state", "VERIFIED_ACTIVE")
  put("purchaseTimeMillis", 1700000000000L);put("verifiedAtMillis", 1700000000000L)
  put("expiryTimeMillis", 4102444800000L);put("snapshotVersion", 2L)
 }
 private fun body(items: JSONArray, owner: Boolean = true) = JSONObject().apply {
  put("status", "SUCCESS")
  put("snapshot", JSONObject().apply {
   if(owner) put("ownerAppUserId", "A")
   put("computedAtMillis", 1700000000000L);put("entitlements", items)
  })
 }.toString()
 private suspend fun parse(body: String) = PlayPurchaseVerifier(
  backendUrl="https://audit.invalid", httpTransport={_,_,_,_->VerifierHttpResponse(200,body)}
 ).restorePurchases(RestoreRequest("A"))
 @Test fun D01MissingRequiredSourceMustNotBecomeAcceptedPlayEntitlement()=runBlocking {
  val entitlement=item().apply { remove("source") }
  val result=parse(body(JSONArray().put(entitlement)))
  assertTrue("Parser invented Play source for malformed authoritative payload: $result",result !is RestoreResult.Success && result !is RestoreResult.Partial)
 }
 @Test fun D02MissingSnapshotAndItemOwnerMustNotBeSynthesizedFromRequest()=runBlocking {
  val entitlement=item().apply { remove("ownerAppUserId") }
  val result=parse(body(JSONArray().put(entitlement),owner=false))
  assertTrue("Parser invented authoritative owner from request: $result",result !is RestoreResult.Success && result !is RestoreResult.Partial)
 }
 @Test fun D03CanceledPendingTombstoneMustNotRejectValidLinkedActiveSnapshot()=runBlocking {
  val canceled=item("new-canceled").apply { put("state", "REVOKED");put("expiryTimeMillis",0L) }
  val result=parse(body(JSONArray().put(canceled).put(item())))
  assertTrue("Valid canceled-pending tombstone rejected entire linked ACTIVE snapshot: $result",result is RestoreResult.Success)
  val snapshot=(result as RestoreResult.Success).snapshot
  assertTrue(snapshot.isVipActive())
  assertFalse(snapshot.entitlements.single { it.purchaseToken=="new-canceled" }.isCurrentlyActive())
 }
 @Test fun D04ControlCompleteActivePayloadRemainsAccepted()=runBlocking {
  val result=parse(body(JSONArray().put(item())))
  assertTrue(result is RestoreResult.Success)
  assertTrue((result as RestoreResult.Success).snapshot.isVipActive())
 }
}
