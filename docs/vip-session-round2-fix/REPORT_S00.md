# Báo Cáo Gói S00 — Khóa Reproduction Và Sửa Chất Lượng Kiểm Thử (G05)

**Thời gian:** 04/10/2026  
**Workspace:** `E:\DU AN AI\T-Scanner`  
**Trạng thái gói:** **PASS** (Baseline locked, production seams in place, real-wiring tests active)

---

## 1. Mục tiêu và phạm vi gói S00

- Khóa baseline reproduction với bộ 8 probes độc lập từ `docs/vip-session-reaudit-20261004/`.
- Sửa chất lượng kiểm thử (G05): thay thế các test dùng fake callback / copy logic cục bộ (F02, F04) bằng các test đi qua wiring thực của `AccountDetailDialog` và `VipUpgradeDialog`.
- Tách test seams tối thiểu, an toàn trên production dialogs để đảm bảo:
  - Tháo callback ở hai nút tài khoản (`btnDialogUpgradeAction`, `containerMembershipStatus`) -> Test F02 lập tức ĐỎ.
  - Tháo điều kiện OR (`onRequestSignIn != null || onRequestSignInForAction != null`) -> Test F04 lập tức ĐỎ.
  - Giữ nguyên các bảo vệ và không sửa production ngoài phạm vi seam.

---

## 2. Kết quả chạy baseline & 8 Probes

Lệnh thực thi probe độc lập:
```powershell
$env:JAVA_HOME='C:\Users\nguye\.jdks\openjdk-21.0.1'
$env:GRADLE_USER_HOME='C:\Users\nguye\.gradle'
.\gradlew.bat :app:testDebugUnitTest --offline -I docs/vip-session-reaudit-20261004/audit.init.gradle --tests 'com.tscanner.app.VipSessionReauditProbeTest' --console=plain
```

### Kết quả: 8 tests, 6 FAIL / 2 PASS

| Probe ID | Test Name | Lỗi gốc | Kết quả | Bản chất lỗi |
|---|---|---|---|---|
| **P01** | `P01_guestUpgradeSurvivesSuccessfulLoginGenerationChange` | G01 | **FAIL** | Guest `UPGRADE` bị hủy do commit làm tăng generation |
| **P02** | `P02_guestRestoreSurvivesSuccessfulLoginGenerationChange` | G01 | **FAIL** | Guest `RESTORE` bị hủy do commit làm tăng generation |
| **P03** | `P03_unboundAttemptUsedByPdfAndIdCardAllowsAccountReplacement` | G02 | **FAIL** | Caller không truyền `expectedOwnerId` cho phép đè account |
| **P04** | `P04_duplicateProductCallbackMustNotLaunchTwice` | G04 | **FAIL** | Query callback lặp gọi `launchBillingFlow` lần 2 |
| **P05** | `P05_oldProductCallbackMustNotFinishNewAction` | G04 | **FAIL** | Query callback cũ xóa cờ busy của action mới |
| **P06** | `P06_backend401MustRemainAnAuthFailureNotNetworkFailure` | G03 | **FAIL** | Backend 401 bị nuốt thành `TransientError` (mạng) |
| **C01** | `C01_sameOwnerReauthContinuesOnce` | Control | **PASS** | Reauth cùng owner tiếp tục đúng 1 lần |
| **C02** | `C02_boundReauthRejectsOtherOwner` | Control | **PASS** | Reauth khác owner bị chặn trước commit |

---

## 3. Production Seams Đã Thêm

### 3.1 `AccountDetailDialog.kt`
- Thêm `private val hostContext: Context = context` tránh `Dialog.getContext()` trả về null trong môi trường JVM unit test stubs.
- Thêm seam `@VisibleForTesting internal var vipUpgradeDialogFactory` trong `openVipUpgradeDialog()`:
  ```kotlin
  @VisibleForTesting
  internal var vipUpgradeDialogFactory: (Context, (() -> Unit)?, (() -> Unit)?, (() -> Unit)?, ((SyncCatalogResult) -> Unit)?, ((VipContinuationAction) -> Unit)?) -> VipUpgradeDialog =
      { ctx, drive, upgrade, signIn, sync, signInAction ->
          VipUpgradeDialog(
              context = ctx,
              onRequestDrivePermission = drive,
              onUpgradeSuccess = upgrade,
              onRequestSignIn = signIn,
              onSyncResult = sync,
              onRequestSignInForAction = signInAction
          )
      }
  ```
- Thêm `@VisibleForTesting internal fun performUpgradeButtonClickForTesting()` và `@VisibleForTesting internal fun performMembershipStatusClickForTesting()`.

### 3.2 `VipUpgradeDialog.kt`
- Thêm `@VisibleForTesting internal fun hasSignInCallbackEvaluated(): Boolean = (onRequestSignIn != null || onRequestSignInForAction != null)`.
- Thay thế trực tiếp trong `btnConfirmVipUpgrade.setOnClickListener`:
  ```kotlin
  hasSignInCallback = hasSignInCallbackEvaluated()
  ```
- Thêm `@VisibleForTesting internal fun resolveUpgradeActionForTesting()` và `triggerUpgradeClickForTesting()`.

---

## 4. Kiểm Thử Wiring Thực (G05 Regression Verification)

Cập nhật `VipSessionExpiryRegressionTest.kt`:
- **`testF02_accountDetailDialog_expiredUser_mustHaveReauthPathNotDeadEndPrompt`**:
  Khởi tạo `AccountDetailDialog` thực, gán interceptor cho `vipUpgradeDialogFactory`, gọi cả 2 entry point (`performUpgradeButtonClickForTesting` và `performMembershipStatusClickForTesting`).
  Assert cả 2 điểm bấm đều gọi factory và chuyển chính xác 2 callback `onRequestSignIn` và `onRequestSignInForAction`.
- **`testF04_actionOnlyCallback_mustBeRecognizedAsHavingSignInCallback`**:
  Khởi tạo `VipUpgradeDialog` thực với `onRequestSignIn = null` và `onRequestSignInForAction != null`.
  Assert `dialog.hasSignInCallbackEvaluated()` là `true`, `dialog.resolveUpgradeActionForTesting()` là `Action.RequestSignIn`, và `dialog.triggerUpgradeClickForTesting()` gọi `onRequestSignIn` của listener chứ không phải `onShowSignInPrompt`.

Kết quả chạy suite `VipSessionExpiryRegressionTest`:
- **10/10 PASS** (BUILD SUCCESSFUL, 6s).

---

## 5. Hợp Đồng Bàn Giao Cho Gói S01 (Contract Handover)

1. **Vấn đề cần giải quyết:** G01 — Guest login thành công (`initialOwner == null` -> `currentUser == A`) làm `sessionGeneration` tăng từ $N \to N+1$, khiến `VipLoginContinuationHandler` hủy pending action vì $N \neq N+1$.
2. **Nguyên tắc an toàn:**
   - KHÔNG bỏ guard `sessionGeneration`.
   - KHÔNG truyền `-1L` trên production để vượt qua kiểm tra.
   - Cho phép chuyển phiên hợp lệ khi attempt bắt đầu từ guest (`null`) commit thành công tài khoản đầu tiên từ chính attempt đó.
   - Reauth cùng chủ sở hữu giữ nguyên owner `A -> A`.
   - Bất kỳ chuyển phiên ngoài attempt hoặc tài khoản khác owner bị loại bỏ.
3. **Files sở hữu bởi S01:**
   - `VipLoginContinuationHandler.kt`
   - `MoreFragment.kt`
   - `AppAuthManager.kt` / `GoogleLoginAttempt.kt` (nếu cần identity commit)
   - Tests: `P01`, `P02`, và các unit tests liên quan.
