# Báo cáo U08 — Hết hạn phiên có đường re-auth rõ (F08: A09)

## 1. Mục tiêu và phạm vi
- Sửa lỗi F08 (probe A09) theo kế hoạch Round 5:
  - Các file sửa đổi:
    - `app/src/main/java/com/tscanner/app/utils/billing/PlayPurchaseVerifier.kt`
    - `app/src/main/java/com/tscanner/app/ui/dialogs/VipUpgradeDialog.kt` (`VipUpgradeActionResolver`)
    - `app/src/main/java/com/tscanner/app/utils/VipPurchaseActionCoordinator.kt`
  - Vấn đề:
    - Khi người dùng đăng nhập nhưng session ID token bị hết hạn, `PlayPurchaseVerifier.isConfigured()` trả về `false` do gộp chung kiểm tra cấu hình backend và tính hợp lệ của token xác thực.
    - `VipUpgradeActionResolver` thấy `currentUser != null` nên gán action là `ActivateVip`.
    - Sau đó `VipPurchaseActionCoordinator` thấy `!isVerifierConfigured()` liền báo lỗi dịch vụ không thể retry (`listener.onError("Dịch vụ xác thực thanh toán hiện chưa sẵn sàng...", canRetry = false)`). Người dùng bị chặn hoàn toàn, không được điều hướng đăng nhập lại.
  - Khắc phục:
    - Tách bạch cấu hình backend (`isBackendConfigured()`) và trạng thái xác thực (`isAuthReady()`, `isTokenExpired(token)`).
    - Expose hàm `isTokenExpired(token)` công khai trên companion của `PlayPurchaseVerifier`.
    - Trong `VipUpgradeActionResolver.resolveUpgradeAction`: Nếu session token của người dùng đã hết hạn, phân giải thành `RequestSignIn` (nếu có callback) hoặc `ShowSignInRequiredPrompt` (nếu không có callback) thay vì `ActivateVip`.
    - Trong `VipPurchaseActionCoordinator`: Bổ sung kiểm tra token hết hạn trước khi kiểm tra `isVerifierConfigured()`; nếu token hết hạn, điều hướng sang flow đăng nhập lại thay vì báo lỗi dịch vụ.

## 2. Kết quả kiểm tra trước / sau sửa đổi

### Focused Probes (`app/src/test/java/com/tscanner/app/VipRound5RegressionTest.kt`)
- Trước U08: 9 tests (8 PASS, 1 FAIL).
- Sau U08: **9 tests (9 PASS, 0 FAIL)** — **100% PASS**
  - ✔ `A01RestoreWrongOwnerMustFail` (**PASS từ U05**)
  - ✔ `A02RestoreUnknownSkuMustFail` (**PASS từ U05**)
  - ✔ `A03RestoreInvalidItemMustNotBecomeSuccessEmpty` (**PASS từ U05**)
  - ✔ `A04Http400MustNotAcceptSuccessBody` (**PASS từ U05**)
  - ✔ `A07ControlValidRestoreParses` (**PASS đối chứng**)
  - ✔ `A05LateRestoreAfterAccountSwitchMustNotWriteOrSignal` (**PASS từ U06**)
  - ✔ `A06StaleActiveSnapshotMustNotReportRestoredAgainstNewerRevocation` (**PASS từ U07**)
  - ✔ `A08PartialRestoreMustProjectCommittedEntitlementToCurrentProfile` (**PASS từ U07**)
  - ✔ `A09ExpiredSessionMustOfferReauthentication` (**ĐÃ SỬA - PASS**)

### Suite Kiểm thử Purchase Action Coordinator
- Lệnh: `./gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.VipPurchaseActionCoordinatorTest --offline --console=plain`
- Kết quả: **BUILD SUCCESSFUL, 100% PASS, 0 FAIL**.

## 3. Thay đổi Contract và Thiết kế
- Tách bạch giữa trạng thái hạ tầng backend (URL, transport, HTTPS) và trạng thái phiên người dùng (JWT token, exp claim).
- Tránh thông báo lỗi sai lệch (lỗi kết nối dịch vụ) khi thực chất là người dùng cần đăng nhập lại; đảm bảo UX liền mạch cho flow nâng cấp VIP.

## 4. Handoff cho Gói Tiếp Theo
- Toàn bộ 9/9 Android probes (A01–A09) và 7/7 backend probes (B01–B07) đã hoàn toàn xanh.
- Chuyển tiếp sang **U09 — Regression tổng hợp & kiểm tra độc lập**.
