# Báo cáo U00 — Baseline và bảo toàn regression VIP vòng 5

## 1. Mục tiêu và phạm vi
- Thiết lập bộ regression test bền vững cho vòng 5:
  - Android: `app/src/test/java/com/tscanner/app/VipRound5RegressionTest.kt`
  - Backend: `backend/billing-verifier/test/round5-regression.test.ts`
- Chưa sửa đổi bất kỳ mã sản phẩm (production code) nào.
- Giữ nguyên trạng thái uncommitted của người dùng; snapshot git status đã được ghi nhận.

## 2. Kết quả chạy kiểm tra Baseline (16 Probes)

### Backend (Node.js test runner)
- Lệnh: `node --experimental-strip-types --test backend/billing-verifier/test/round5-regression.test.ts`
- Kết quả: **7 tests, 1 PASS, 6 FAIL**
  - ✖ `B07 late RTDN must not overwrite newer verify expiry` (FAIL - F01)
  - ✖ `B01 first-binding late active must not resurrect committed expiry` (FAIL - F01)
  - ✖ `B02 conflict must not discard authoritative expiry and return active` (FAIL - F01)
  - ✖ `B03 on-hold V2 must invalidate stored active grant` (FAIL - F02)
  - ✖ `B04 missing V2 lifecycle state must not grant active` (FAIL - F02)
  - ✖ `B05 client metadata must not suppress refresh of known refunded receipt` (FAIL - F03)
  - ✔ `B06 control empty restore refreshes refund with no conflicting metadata` (PASS đối chứng)

### Android (Gradle testDebugUnitTest)
- Lệnh: `./gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.VipRound5RegressionTest --offline --console=plain`
- Kết quả: **9 tests, 1 PASS, 8 FAIL**
  - ✖ `A01RestoreWrongOwnerMustFail` (FAIL - F04)
  - ✖ `A02RestoreUnknownSkuMustFail` (FAIL - F04)
  - ✖ `A03RestoreInvalidItemMustNotBecomeSuccessEmpty` (FAIL - F04)
  - ✖ `A04Http400MustNotAcceptSuccessBody` (FAIL - F04)
  - ✖ `A05LateRestoreAfterAccountSwitchMustNotWriteOrSignal` (FAIL - F05)
  - ✖ `A06StaleActiveSnapshotMustNotReportRestoredAgainstNewerRevocation` (FAIL - F06)
  - ✔ `A07ControlValidRestoreParses` (PASS đối chứng)
  - ✖ `A08PartialRestoreMustProjectCommittedEntitlementToCurrentProfile` (FAIL - F07)
  - ✖ `A09ExpiredSessionMustOfferReauthentication` (FAIL - F08)

## 3. Ma trận đối chiếu Probes ↔ Nhóm lỗi (F01–F08)
| Mã probe | Nhóm lỗi | Trạng thái hiện tại | Mô tả lỗi |
|---|---|---|---|
| B01 | F01 | FAIL | Initial insert / first-binding late active đè snapshot mới hơn (expectedVersion=undefined) |
| B02 | F02 / F01 | FAIL | Conflict trả về old cache ACTIVE thay vì refetch/retry upstream |
| B07 | F01 | FAIL | RTDN thiếu version guard xuyên qua verify, làm sống lại quyền cũ |
| B03 | F02 | FAIL | Play V2 trả ON_HOLD nhưng paymentState=0 khiến verifier trả PENDING sớm trước khi cập nhật ON_HOLD |
| B04 | F02 | FAIL | Play V2 thiếu subscriptionState nhưng vẫn fallback cấp VIP SUCCESS |
| B05 | F03 | FAIL | Client metadata sai đè metadata server khiến token đã biết không được refresh trạng thái REVOKED |
| B06 | F03 | PASS | Đối chứng: restore không có candidate xung đột thì refresh REVOKED đúng |
| A01 | F04 | FAIL | Restore response khác owner với request vẫn thành Success |
| A02 | F04 | FAIL | Restore response chứa SKU ngoài catalog vẫn thành Success |
| A03 | F04 | FAIL | Item state UNKNOWN bị silent drop thành Success rỗng |
| A04 | F04 | FAIL | HTTP status 400 nhưng có body success vẫn được nhận |
| A07 | F04 | PASS | Đối chứng: response hợp lệ được parse thành Success |
| A05 | F05 | FAIL | Restore hoàn tất sau khi đổi tài khoản vẫn ghi vào store tài khoản cũ |
| A06 | F06 | FAIL | Báo Restored dựa vào response incoming thay vì snapshot đã merge/commit thực tế |
| A08 | F07 | FAIL | Restore PARTIAL không project quyền vào AppAuthManager profile |
| A09 | F08 | FAIL | Token phiên hết hạn bị coordinator báo dịch vụ chưa sẵn sàng canRetry=false |

## 4. Xác nhận Acceptance U00
- Cả 14 test RED đều xuất phát từ logic sản phẩm, hoàn toàn không do lỗi compile, import, môi trường hay fixture.
- Hai đối chứng B06 và A07 chạy XANH (PASS), chứng minh harness hoạt động chính xác.

## 5. Kế hoạch tiếp theo
- Tự động chuyển sang **U01 — Lifecycle Play V2 nhất quán (F02)**: Sửa `backend/billing-verifier/src/googlePlayClient.ts`, `src/verifier.ts`, `src/types.ts` để giải quyết B03 và B04.
