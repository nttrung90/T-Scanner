# Báo cáo Checkpoint VIP Round 7 — Gói X00

## 1. Mục tiêu và phạm vi gói X00
- **Mục tiêu:** Thiết lập baseline, bảo toàn staged/uncommitted/untracked checkout, và chuyển toàn bộ 27 test probes (18 Android + 9 Backend) từ thư mục audit `docs/vip-round7-20261001/` sang sourceSet kiểm thử chính thức để tạo suite regression bền vững. Chưa sửa production code.
- **Phạm vi file:**
  - `backend/billing-verifier/test/round7-regression.test.ts` (mới tạo)
  - `app/src/test/java/com/tscanner/app/VipRound7RegressionTest.kt` (mới tạo)
  - `PROGRESS_VIP_R7_AUTORUN.md` (mới tạo)
  - `REPORT_VIP_R7_X00.md` (báo cáo checkpoint)

## 2. Kết quả kiểm thử trước sửa (Baseline Red Evidence)

### 2.1 Backend Probes (`backend/billing-verifier/test/round7-regression.test.ts`)
- **Lệnh chạy:** `node --experimental-strip-types --test backend/billing-verifier/test/round7-regression.test.ts`
- **Kết quả:** 9 tests (2 PASS, 7 FAIL)
  - ✖ `B701 first-bind linked query must not overwrite a newer authoritative EXPIRED receipt` (F01)
  - ✖ `B702 canceled yearly upgrade must recover authoritative linked monthly receipt` (F02)
  - ✖ `B703 unresolved linked upstream failure must not be reported as complete restore SUCCESS` (F02)
  - ✖ `B704 RTDN after a real pending verification must refresh the canceled purchases linked old receipt` (F03)
  - ✖ `B705 all-pending restore must preserve per-token PENDING semantics` (F06 backend)
  - ✖ `B706 all unknown rejected restore candidates must not be reported as full SUCCESS` (F06 backend / policy contract)
  - ✔ `B709 control same-SKU linked ACTIVE receipt is resolved and owned correctly` (Control PASS)
  - ✔ `B710 control linked receipt already owned by another account is preserved` (Control PASS)
  - ✖ `B711 linked first-bind race must remain protected across two SQLite connections and restart` (F01)

### 2.2 Android Probes (`app/src/test/java/com/tscanner/app/VipRound7RegressionTest.kt`)
- **Lệnh chạy:** `./gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.VipRound7RegressionTest --offline --console=plain`
- **Kết quả:** 18 tests (3 PASS, 15 FAIL)
  - ✖ `A01PlayQueryErrorMustStillRefreshAuthoritativeAccountReceipts` (F05)
  - ✖ `A02RemoteFailureAfterLocalSuccessMustNotReportCompleteRestore` (F06)
  - ✖ `A03PartialResultMustReachManagerCallbackAsPartial` (F06)
  - ✖ `A04ParserMustPreserveActualPerTokenFailureCount` (F06)
  - ✖ `A05CachedUnresolvedEntitlementMustNotCountAsFreshRestoreSuccess` (F06)
  - ✖ `A06DestroyedManagerMustNotCommitNoncooperativeVerificationResponse` (F04)
  - ✖ `A07RestoreMustKeepOriginalSessionOwnershipAcrossConnectionAwait` (F04)
  - ✔ `A08ControlFullRestoreStillProjectsVipAndCompletes` (Control PASS)
  - ✖ `C01DefaultProfileMustNotInventCredential` (F07)
  - ✖ `C02MalformedProductionTokenMustNotBeAuthReady` (F07)
  - ✖ `C03JwtWithoutExpiryMustNotBeAuthReady` (F07)
  - ✖ `C04RestoreMustEnforceHttpsBeforeSendingBearer` (F08)
  - ✖ `C05Restore401MustExposeAuthenticationRecovery` (F07)
  - ✔ `C06ControlUnexpiredTokenIsReady` (Control PASS)
  - ✖ `D01MissingRequiredSourceMustNotBecomeAcceptedPlayEntitlement` (F09)
  - ✖ `D02MissingSnapshotAndItemOwnerMustNotBeSynthesizedFromRequest` (F09)
  - ✖ `D03CanceledPendingTombstoneMustNotRejectValidLinkedActiveSnapshot` (F09)
  - ✔ `D04ControlCompleteActivePayloadRemainsAccepted` (Control PASS)

### 2.3 Tổng kết ma trận Round 7 Baseline
- **Tổng số ca kiểm thử:** 27
- **Số ca FAIL xuất phát từ logic sản phẩm (red evidence):** 22
- **Số ca đối chứng PASS (control):** 5 (`B709`, `B710`, `A08`, `C06`, `D04`)
- **Không có lỗi fixture, compile hay môi trường.**
- **Bảo toàn:** Toàn bộ test cũ (946 Android / 99 backend) và probes R5 (16/16), R6 (12/12) được giữ nguyên.

## 3. Hợp đồng và Giới hạn
- Chưa có thay đổi logic sản phẩm trong X00.
- Tất cả probes giữ nguyên assertion hành vi và boundary production.
- B706 được phân loại theo quyết định thiết kế ở X04 (policy check về việc từ chối toàn bộ candidate không được báo SUCCESS).

## 4. Chuyển giao gói tiếp theo
- **Gói kế tiếp:** **X01 — CAS cho mọi linked bind/update (F01)**.
- **Mục tiêu X01:** Sửa `backend/billing-verifier/src/verifier.ts` để áp dụng CAS khi bind/update linked purchase tokens (bao gồm cả trường hợp record chưa tồn tại bằng explicit absent sentinel), giải quyết B701 và B711.
