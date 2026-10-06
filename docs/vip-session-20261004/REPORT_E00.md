# Báo Cáo Triển Khai Gói E00 — Chụp Baseline và Tạo Regression Thực Sự

Ngày: 04/10/2026  
Workspace: `E:\DU AN AI\T-Scanner`

## 1. Thông tin chung & Trạng thái

- **Gói:** E00 — Chụp baseline và tạo regression thực sự
- **Trạng thái:** PASS (Acceptance đạt, đã khóa 3 tests đỏ hành vi F02, F04, F05; 4 control tests pass; 28 tests baseline pass)

## 2. Code evidence và reproduction

- **F01 & F02:** Trong `AccountDetailDialog.kt:106, 112`, cả hai nút mở mua VIP (`btnDialogUpgradeAction` và `containerMembershipStatus`) đều chỉ khởi tạo `VipUpgradeDialog(context, onRequestDrivePermission)` mà không truyền `onRequestSignIn` hay `onRequestSignInForAction`. Do đó, khi người dùng đã đăng nhập tài khoản Free với credential hết hạn, `hasSignInCallback` bằng false, dẫn đến `ShowSignInRequiredPrompt` và coordinator gọi `listener.onShowSignInPrompt()`, hiển thị toast yêu cầu đăng nhập và đóng dialog. Người dùng bị ngõ cụt: bấm vào tài khoản ở `MoreFragment` chỉ mở lại `AccountDetailDialog`, buộc phải đăng xuất mới mua được.
- **F04:** Trong `VipUpgradeDialog.kt:154`, `hasSignInCallback` được tính cứng bằng `(onRequestSignIn != null)`. Caller chỉ cấu hình `onRequestSignInForAction` (để nhận action cụ thể) bị coi là không có callback đăng nhập (`hasSignInCallback = false`), bị đá về prompt đăng nhập thay vì kích hoạt luồng auth.
- **F05:** Trong `MoreFragment.kt:340`, nhánh Credential Manager success gọi `vipContinuationHandler.onSignInSuccess { showVipUpgradeDialog() }`, bỏ qua `action` đã lưu, khiến continuation `RESTORE` bị chuyển đổi thành `UPGRADE`.

## 3. Files thay đổi trong gói (tách thay đổi có sẵn)

- **File mới tạo trong gói E00:**
  - `app/src/test/java/com/tscanner/app/VipSessionExpiryRegressionTest.kt`
  - `docs/vip-session-20261004/baseline_git_status.txt`
  - `docs/vip-session-20261004/baseline_git_diff_summary.txt`
  - `docs/vip-session-20261004/REPORT_E00.md`
- **Không sửa file production trong E00** (tuân thủ scope E00).
- **Thay đổi có sẵn trước đó:** Được bảo toàn nguyên vẹn (xem `baseline_git_status.txt`).

## 4. Test đỏ trước / xanh sau, command và log

- **Command chạy regression suite:**
  ```powershell
  $env:JAVA_HOME = 'C:\Users\nguye\.jdks\openjdk-21.0.1'
  $env:GRADLE_USER_HOME = 'C:\Users\nguye\.gradle'
  .\gradlew.bat :app:testDebugUnitTest --offline --tests 'com.tscanner.app.VipSessionExpiryRegressionTest' --console=plain
  ```
- **Kết quả biên dịch & thực thi:**
  - `compileDebugUnitTestKotlin`: SUCCESS (test biên dịch hoàn toàn sạch, không lỗi compile)
  - `7 tests completed, 3 failed`
  - **4 Control tests GREEN:**
    - `testControl_expiredTokenDetection_isTokenExpired`: PASSED
    - `testControl_validUser_tokenNotExpired_resolvesToActivateVip`: PASSED
    - `testControl_guestUser_withCallback_resolvesToRequestSignIn`: PASSED
    - `testControl_guestUser_withoutCallback_resolvesToShowSignInRequiredPrompt`: PASSED
  - **3 Regression tests RED (Behavioral Failures):**
    - `testF02_accountDetailDialog_expiredUser_mustHaveReauthPathNotDeadEndPrompt`: FAILED
      - Error: `java.lang.AssertionError: Must not show dead-end sign in prompt to logged-in user with expired credential expected:<0> but was:<1>`
    - `testF04_actionOnlyCallback_mustBeRecognizedAsHavingSignInCallback`: FAILED
      - Error: `java.lang.AssertionError: Host with onRequestSignInForAction must NOT show dead-end prompt expected:<0> but was:<1>`
    - `testF05_credentialManagerSuccess_preservesRestoreContinuation`: FAILED
      - Error: `java.lang.AssertionError: Credential Manager success must preserve RESTORE continuation action expected:<RESTORE> but was:<UPGRADE>`

- **Command chạy baseline suites:**
  ```powershell
  .\gradlew.bat :app:testDebugUnitTest --offline --tests 'com.tscanner.app.VipPurchaseActionCoordinatorTest' --tests 'com.tscanner.app.VipRound8RegressionTest' --console=plain
  ```
- **Kết quả baseline:**
  - `VipPurchaseActionCoordinatorTest`: 11/11 PASSED
  - `VipRound8RegressionTest`: 17/17 PASSED
  - Tổng baseline: 28/28 PASSED (BUILD SUCCESSFUL).

## 5. Acceptance đạt / chưa đạt

- [x] Đọc kế hoạch và các file F01–F06.
- [x] Lưu git status và git diff summary baseline vào `docs/vip-session-20261004/`.
- [x] Tạo `VipSessionExpiryRegressionTest.kt` với synthetic JWT `exp` quá khứ và tương lai.
- [x] Tái hiện đúng 3 behavioral failures F02, F04, F05 mà không gặp lỗi compile.
- [x] 28 test baseline tiếp tục chạy và vượt qua.
- **Kết luận:** Acceptance E00 ĐẠT HOÀN TOÀN.

## 6. Rủi ro hoặc gates chưa chạy

- ADB daemon không khả dụng trên host Windows cho kiểm thử thiết bị (ghi nhận theo baseline). Các gates thiết bị được phân bổ cho E06.
- Production code chưa sửa, các test F02/F04/F05 đang ở trạng thái RED được kiểm soát.

## 7. Contract bàn giao và gói tiếp theo

- Đã khóa fixtures và 3 test đỏ cho E01–E04.
- Chuyển sang **E01 — Xác thực lại an toàn cho tài khoản đang đăng nhập**:
  - Sở hữu: `AppAuthManager.kt`, `GoogleLoginAttempt.kt`, các auth tests.
  - Thêm cơ chế/mode reauthentication an toàn gắn liền với expected owner, attempt token, session generation; kiểm tra trước `processSignedInAccountInternal` để ngăn chặn side effect khi tài khoản B đăng nhập đè tài khoản A.
