# Báo cáo VIP Round 6 — W08: Missing/expired credential vào cùng auth recovery (R05)

## 1. Mục tiêu và phạm vi
- Khắc phục khiếm khuyết R05 (Probe A06).
- Sửa lỗi điều kiện trong `VipUpgradeActionResolver.resolveUpgradeAction` và `VipPurchaseActionCoordinator`:
  - Trước đây: `val isExpired = !token.isNullOrBlank() && PlayPurchaseVerifier.isTokenExpired(token)`. Khi token là `null` hoặc blank, `isExpired` nhận giá trị `false`, khiến người dùng có email nhưng không có token bị lọt vào nhánh `ActivateVip`, dẫn đến lỗi kỹ thuật sau đó thay vì hướng dẫn đăng nhập lại.
  - Sau khi sửa: Kiểm tra `val isTokenMissingOrExpired = token.isNullOrBlank() || PlayPurchaseVerifier.isTokenExpired(token)`. Bất kể token bị thiếu, rỗng hay hết hạn, toàn bộ đều được điều hướng thống nhất vào chu trình phục hồi xác thực (`RequestSignIn` hoặc `ShowSignInRequiredPrompt`).

## 2. Các file thay đổi
- `app/src/main/java/com/tscanner/app/ui/dialogs/VipUpgradeDialog.kt`
  - Đổi điều kiện kiểm tra trong `VipUpgradeActionResolver.resolveUpgradeAction`: chỉ cho phép `Action.ActivateVip` khi `currentUser != null && currentUser.email.isNotBlank() && !isTokenMissingOrExpired`.
  - Nếu token `null`, blank hoặc expired: trả về `Action.RequestSignIn` (nếu có callback) hoặc `Action.ShowSignInRequiredPrompt`.
- `app/src/main/java/com/tscanner/app/utils/VipPurchaseActionCoordinator.kt`
  - Đồng bộ logic kiểm tra token: `val tokenMissingOrExpired = token.isNullOrBlank() || PlayPurchaseVerifier.isTokenExpired(token)`. Dừng xử lý và phát callback `onRequestSignIn()` / `onShowSignInPrompt()` kịp thời.

## 3. Kết quả kiểm tra
- **Trước khi sửa:**
  - `A06MissingIdTokenMustRequestReauthentication` FAILED: Người dùng có email nhưng `idToken = null` bị rơi xuống `ActivateVip`.
- **Sau khi sửa:**
  - Chạy `gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.VipRound6RegressionTest.A06*`:
    - **BUILD SUCCESSFUL, 1 passed**.
  - Chạy toàn bộ suite `VipRound6RegressionTest`:
    - **BUILD SUCCESSFUL, 9/9 passed** (100% tests PASS).

## 4. Contract và cam kết
- Phiên đăng nhập thiếu token hoặc token hết hạn tuyệt đối không được phép mở giao diện thanh toán Google Play.
- Khôi phục xác thực diễn ra nhất quán và an toàn trước khi kích hoạt flow mua VIP.

## 5. Bước tiếp theo
- Chuyển sang thực hiện **W09** — Xác minh host độc lập & toàn bộ ma trận acceptance (12 probes R6 + 16 probes R5 + full Android unit suite + backend test suite + lintDebug + assembleDebug).
