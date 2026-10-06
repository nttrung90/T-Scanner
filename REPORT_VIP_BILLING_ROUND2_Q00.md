# Báo cáo Nghiệm thu VIP Billing Vòng 2 — Gói Q00

Ngày thực hiện: 26/09/2026  
Mã gói: **Q00 — Sửa fixture và cố định regression (R11)**  
Môi trường: Windows / PowerShell / Gradle 9.7.1 / Node.js  
Repo: `E:\DU AN AI\T-Scanner`

---

## 1. Mục tiêu và Kết quả gói Q00

Gói Q00 tập trung khắc phục sai lệch encoding trạng thái PENDING trong test fixture (Defect R11) và chuyển giao toàn bộ các probe kiểm thử lỗi của vòng audit 2 vào bộ kiểm thử regression vĩnh viễn của client và backend.

### 1.1. Khắc phục khiếm khuyết R11 trong `BillingTestFixtures.kt`
- **Nguyên nhân gốc:** Google Play Billing Library internal bytecode (`com.android.billingclient.api.Purchase`) giải mã JSON theo quy tắc:
  ```java
  public int getPurchaseState() {
      int state = this.zzc.optInt("purchaseState", 1);
      return state == 4 ? 2 : 1; // 4 trong JSON Play Store đại diện cho PENDING (2 trong SDK public enum)
  }
  ```
  Hàm `createTestPurchase` trước đây ghi trực tiếp enum public `Purchase.PurchaseState.PENDING` (`2`) vào raw JSON `{"purchaseState": 2}`. Khi SDK gọi getter, `optInt("purchaseState", 1)` trả về `2`, rơi vào nhánh `default`, làm `purchase.purchaseState` trả về `1` (`PURCHASED`).
- **Khắc phục:** Đã bổ sung bộ ánh xạ trong `createTestPurchase`:
  ```kotlin
  val rawPurchaseState = when (purchaseState) {
      Purchase.PurchaseState.PENDING, 4 -> 4
      else -> purchaseState
  }
  ```
  Khi truyền `Purchase.PurchaseState.PENDING` (`2`) hoặc `4`, JSON được ghi `"purchaseState": 4`. Nhờ đó, SDK getter `purchase.purchaseState` trả về chính xác `2` (`Purchase.PurchaseState.PENDING`).

### 1.2. Tạo bộ test độc lập cho Fixture: `BillingPurchaseFixtureTest.kt`
Xác thực 6 kịch bản hành vi:
1. `factoryMustProducePurchasedStateByDefault`: Trả về `PURCHASED` (`1`) theo mặc định.
2. `factoryMustProducePurchasedStateWhenExplicitlyRequested`: Trả về `PURCHASED` (`1`) khi yêu cầu tường minh.
3. `factoryMustProducePendingStateWhenRequestedWithEnum`: Trả về `PENDING` (`2`) khi truyền `Purchase.PurchaseState.PENDING`.
4. `factoryMustProducePendingStateWhenRequestedWithRawValueFour`: Trả về `PENDING` (`2`) khi truyền raw value `4`.
5. `pendingPurchaseMustNotBeAcknowledgedOrGrantVip`: Giao dịch PENDING không được cấp quyền VIP, không gửi acknowledge tới BillingClient, và callback trả về `false`.
6. `purchasedStateGrantsVipWhenProcessed`: Giao dịch PURCHASED hợp lệ kích hoạt VIP đúng quy trình.

### 1.3. Cố định Regression Suite vĩnh viễn
- **Android (`app/src/test/java/com/tscanner/app/BillingRound2RegressionTest.kt`):**
  - Chuyển toàn bộ 10 probe/control từ `build/vip-billing-reaudit2/BillingRound2ProbeTest.kt`.
  - Kết quả:
    - `probePendingFixtureMustRepresentPendingPurchase` (R11): **PASS** (Đã sửa ở Q00).
    - `controlDuplicateVerifiedReceiptDoesNotExtend`: **PASS**.
    - `controlConfiguredRemoteVerifierStillReportsMissingImplementation`: **PASS**.
    - 7 probe nghiệp vụ (`probeRevokedReceiptMustNotReturnOnReload`, `probeEmptyPlayQueryMustPreservePromotion`, `probeOldEmptyQueryMustNotRevokeNewAccount`, `probeOwnedReceiptMustNotReappearAsGuestAndBindToB`, `probeEqualVersionCannotResurrectRevokedState`, `probeCommitFailureMustNotReportPurchaseSuccess`, `probeRestoreMustNotReusePreviousPurchaseOwner`): **FAIL** như kỳ vọng, tái hiện chính xác các lỗi R02, R03, R04, R05, R06 để làm mốc nghiệm thu cho Q01–Q04.
- **Backend (`backend/billing-verifier/test/round2-regression.test.ts`):**
  - Chuyển 4 probe/control từ `build/vip-billing-reaudit2/backend-probes.test.ts`.
  - Kết quả:
    - `control ownership conflict rejected within one store instance`: **PASS**.
    - 3 probe (`probe RTDN retries same event after temporary Google API failure`, `probe authoritative expired verification must not restore stale active receipt`, `probe HTTP restore endpoint must require authentication`): **FAIL** như kỳ vọng, tái hiện chính xác các lỗi R07, R09, R10 cho Q05–Q08.

---

## 2. Danh sách file và tuân thủ Whitelist

| File | Trạng thái | Ghi chú |
|---|---|---|
| `app/src/test/java/com/tscanner/app/BillingTestFixtures.kt` | MODIFIED | Sửa ánh xạ `rawPurchaseState` trong `createTestPurchase` |
| `app/src/test/java/com/tscanner/app/BillingPurchaseFixtureTest.kt` | CREATED | 6 unit tests cho fixture & hành vi PENDING |
| `app/src/test/java/com/tscanner/app/BillingRound2RegressionTest.kt` | CREATED | 10 tests cố định Android regression Round 2 |
| `backend/billing-verifier/test/round2-regression.test.ts` | CREATED | 4 tests cố định Backend regression Round 2 |
| `REPORT_VIP_BILLING_ROUND2_Q00.md` | CREATED | Báo cáo gói Q00 |
| `app/src/main/...` | PRESERVED | Không sửa mã production client |
| `backend/billing-verifier/src/...` | PRESERVED | Không sửa mã production backend |

---

## 3. Bằng chứng thực thi lệnh và Test Counts

### 3.1. Baseline Android Unit Tests
```powershell
$env:GRADLE_USER_HOME='C:\Users\nguye\.gradle'
.\gradlew.bat :app:testDebugUnitTest
```
- **Kết quả baseline:** 834 tests, 0 skipped, 0 failures, 0 errors. **BUILD SUCCESSFUL**.

### 3.2. Fixture Test mới (`BillingPurchaseFixtureTest`)
```powershell
$env:GRADLE_USER_HOME='C:\Users\nguye\.gradle'
.\gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.BillingPurchaseFixtureTest
```
- **XML Report:** `app/build/test-results/testDebugUnitTest/TEST-com.tscanner.app.BillingPurchaseFixtureTest.xml`
- **Kết quả:** 6 tests, 0 skipped, 0 failures, 0 errors (100% GREEN).

### 3.3. Android Round 2 Regression Test (`BillingRound2RegressionTest`)
```powershell
$env:GRADLE_USER_HOME='C:\Users\nguye\.gradle'
.\gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.BillingRound2RegressionTest
```
- **Kết quả:** 10 tests completed:
  - **3 tests PASS:**
    - `probePendingFixtureMustRepresentPendingPurchase` (R11 FIXED)
    - `controlDuplicateVerifiedReceiptDoesNotExtend`
    - `controlConfiguredRemoteVerifierStillReportsMissingImplementation`
  - **7 tests FAIL (Tái hiện lỗi để bàn giao cho các gói sau):**
    - `probeRevokedReceiptMustNotReturnOnReload` (R03 -> Q04)
    - `probeEmptyPlayQueryMustPreservePromotion` (R03 -> Q04)
    - `probeOldEmptyQueryMustNotRevokeNewAccount` (R03 -> Q04)
    - `probeOwnedReceiptMustNotReappearAsGuestAndBindToB` (R02 -> Q01)
    - `probeEqualVersionCannotResurrectRevokedState` (R05 -> Q03)
    - `probeCommitFailureMustNotReportPurchaseSuccess` (R06 -> Q03)
    - `probeRestoreMustNotReusePreviousPurchaseOwner` (R04 -> Q02)

### 3.4. Backend Baseline Tests
```powershell
npm test (trong thư mục backend/billing-verifier)
```
- **Baseline:** 21 tests, 21 pass, 0 fail.

### 3.5. Backend Round 2 Regression Test (`round2-regression.test.ts`)
```powershell
node --experimental-strip-types --test test/round2-regression.test.ts
```
- **Kết quả:** 4 tests completed:
  - **1 test PASS:** `control ownership conflict rejected within one store instance`
  - **3 tests FAIL (Tái hiện lỗi backend để bàn giao):**
    - `probe RTDN retries same event after temporary Google API failure` (R09 -> Q08)
    - `probe authoritative expired verification must not restore stale active receipt` (R10 -> Q07)
    - `probe HTTP restore endpoint must require authentication` (R07 -> Q05)

---

## 4. Danh sách Test dùng Fallback ngầm định (Handoff cho Q11)

Theo yêu cầu của kế hoạch, ta lập danh sách các vị trí test đang phụ thuộc vào `Class.forName("org.junit.Test") != null` trong `PlayPurchaseVerifier.kt:27-44` (do gọi constructor `createInstanceForTesting` không truyền `verifier` tường minh):

1. **`BillingPurchaseVerificationTest.kt`:**
   - Dòng 121: `createInstanceForTesting(testContext, { fakeWrapper })`
   - Dòng 187: `createInstanceForTesting(testContext, { fakeWrapper })`
2. **`BillingReauditRegressionTest.kt`:**
   - Các dòng: 69, 85, 113, 138, 165, 201, 217, 230, 242, 260, 274, 289, 304, 317.
3. **`BillingManagerTest.kt`:**
   - Các dòng: 65, 88, 118, 141, 161, 195, 219, 266, 288, 327, 368.
4. **Các suite phụ trợ:**
   - `BillingOfferPresentationTest.kt`, `BillingConnectionCoordinatorTest.kt`, `BillingOperationEventsTest.kt`, `BillingReconciliationTest.kt`, `BillingLifecycleIntegrationTest.kt`.

**Handoff sang Q11:** Khi triển khai client HTTP verifier và bỏ `Class.forName("org.junit.Test")` khỏi production code, Q11 sẽ chuyển các test này sang truyền `FakePurchaseVerifier` tường minh thông qua overload `createInstanceForTesting(context, clientProvider, verifier)`.

---

## 5. Handoff sang Gói Q01

- Gói tiếp theo: **Q01 — Khóa migration legacy và guest ownership (R02)**.
- Mục tiêu chính của Q01:
  - Sửa `BillingManager.kt` và `BillingEntitlementStore.kt` để không tự ý gán `VERIFIED_ACTIVE` cho guest/legacy từ `lastReceipt` toàn cục, không chuyển quyền của tài khoản A sang tài khoản B qua thao tác bind cục bộ.
  - Chuyển probe `probeOwnedReceiptMustNotReappearAsGuestAndBindToB` trong `BillingRound2RegressionTest.kt` từ **ĐỎ sang XANH**.
- Trạng thái bàn giao:
  - Fixture `createTestPurchase` đã hoàn thiện và đáng tin cậy.
  - Regression suite đã sẵn sàng đo lường.

---

## 6. Trạng thái và Dừng gói

- **Hoàn thành:** 100% yêu cầu gói Q00.
- **Dừng lại:** Theo đúng kỷ luật, dừng sau gói Q00 để nghiệm thu trước khi chuyển sang gói Q01.
