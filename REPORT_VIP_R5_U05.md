# Báo cáo U05 — Kiểm tra contract HTTP restore Android (F04: A01, A02, A03, A04, A07)

## 1. Mục tiêu và phạm vi
- Sửa lỗi F04 (các probe A01, A02, A03, A04, A07) theo kế hoạch Round 5:
  - File: `app/src/main/java/com/tscanner/app/utils/billing/PlayPurchaseVerifier.kt`
  - Vấn đề:
    - `parseRestoreResponse` không kiểm tra mã phản hồi HTTP (`statusCode != 200`), cho phép HTTP 400 nhận payload SUCCESS (A04).
    - `dummyReq` được dựng từ chính dữ liệu trả về của server thay vì ràng buộc với `request.ownerAppUserId`, dẫn đến phản hồi trả về owner B vẫn được parse thành `RestoreResult.Success` cho user A (A01).
    - Không kiểm tra danh mục catalog SKU trong `parseRestoreResponse` và `parseEntitlementStrict`, khiến SKU ngoài danh mục (`unrelated-product`) vẫn thành `RestoreResult.Success` (A02).
    - Khi một entitlement item trong danh sách restore có trạng thái không hợp lệ (ví dụ `state: "UNKNOWN"`), `parseEntitlementStrict` trả về `null`, nhưng vòng lặp chỉ đơn giản bỏ qua item đó và trả về `RestoreResult.Success` với danh sách rỗng (A03).
  - Khắc phục:
    - Bắt buộc kiểm tra `statusCode == 200`. Bất kỳ mã lỗi HTTP nào (400, 401, 403, 429, 5xx) đều bị từ chối, không chấp nhận body giả mạo (A04).
    - Kiểm tra nghiêm ngặt `responseOwner`: nếu `request.ownerAppUserId != null` và `responseOwner != request.ownerAppUserId`, trả về `RestoreResult.Rejected(OWNERSHIP_CONFLICT)` (A01).
    - Kiểm tra `BillingManager.ALLOWED_PRODUCT_IDS.contains(...)` trong cả `parseRestoreResponse` và `parseEntitlementStrict`; từ chối với `PRODUCT_NOT_ALLOWED` nếu SKU ngoài catalog (A02).
    - Ràng buộc item trong snapshot: nếu có bất kỳ item nào không parse được (malformed / invalid state), không được âm thầm nuốt lỗi thành Success rỗng mà phải trả về lỗi `RestoreResult.TransientError` (A03).

## 2. Kết quả kiểm tra trước / sau sửa đổi

### Focused Probes (`app/src/test/java/com/tscanner/app/VipRound5RegressionTest.kt`)
- Trước U05: 9 tests (1 PASS, 8 FAIL).
- Sau U05: **9 tests (5 PASS, 4 FAIL)**
  - ✔ `A01RestoreWrongOwnerMustFail` (**ĐÃ SỬA - PASS**)
  - ✔ `A02RestoreUnknownSkuMustFail` (**ĐÃ SỬA - PASS**)
  - ✔ `A03RestoreInvalidItemMustNotBecomeSuccessEmpty` (**ĐÃ SỬA - PASS**)
  - ✔ `A04Http400MustNotAcceptSuccessBody` (**ĐÃ SỬA - PASS**)
  - ✔ `A07ControlValidRestoreParses` (**PASS đối chứng**)
  - ✖ `A05LateRestoreAfterAccountSwitchMustNotWriteOrSignal` (FAIL - Mục tiêu của U06)
  - ✖ `A06StaleActiveSnapshotMustNotReportRestoredAgainstNewerRevocation` (FAIL - Mục tiêu của U07)
  - ✖ `A08PartialRestoreMustProjectCommittedEntitlementToCurrentProfile` (FAIL - Mục tiêu của U07)
  - ✖ `A09ExpiredSessionMustOfferReauthentication` (FAIL - Mục tiêu của U08)

### Suite Kiểm thử HTTP Verifier Hiện Có
- Lệnh: `./gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.PlayPurchaseVerifierHttpTest --offline --console=plain`
- Kết quả: **BUILD SUCCESSFUL, 100% PASS, 0 FAIL**.

## 3. Thay đổi Contract và Thiết kế
- `PlayPurchaseVerifier.kt`: Ràng buộc toàn bộ dữ liệu phản hồi restore với contract:
  - HTTP transport code phải là 200 OK.
  - Owner phản hồi phải khớp với owner của phiên/yêu cầu.
  - Mọi SKU phải nằm trong allowlist của ứng dụng.
  - Snapshot item hỏng không được nuốt thành danh sách rỗng thành công.

## 4. Handoff cho Gói Tiếp Theo
- Bàn giao tầng xác thực HTTP restore đã chặt chẽ cho **U06 — Guard phiên và vòng đời restore sau await (F05: A05)**.
