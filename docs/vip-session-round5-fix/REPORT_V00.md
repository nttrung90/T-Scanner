# Báo Cáo Gói V00 — Khóa Baseline Và Test Đỏ (Vòng 5)

Ngày thực hiện: 06/10/2026. Workspace: `E:\DU AN AI\T-Scanner`.

---

## 1. Mục Tiêu & Ranh Giới Gói V00

- Khóa bằng chứng baseline, mã băm (SHA256) các file sẽ sửa, tình trạng git hiện thời.
- Chạy nguyên bản 4 probe vòng 5 và 21 regression được chọn từ các vòng trước bằng `run-probes.ps1`.
- Ghi nhận chi tiết các điểm đỏ (P01, P02, P03) và điểm xanh (C01 + 21 regressions).
- Lập bảng phân tích wiring thực từ BillingManager qua Consumer/Coordinator đến Host UI và Restore.

---

## 2. Mã Băm (SHA256) Các File Sẽ Sửa Trước Khi Can Thiệp

Lưu tại `docs/vip-session-round5-fix/baseline_hashes.txt`:
```
app\build.gradle: BD3AD4CF6F993CE47D98C00182C6BDAAC950CEA70BA53B85F10012F5ECD95B56
app\src\main\java\com\tscanner\app\utils\BillingManager.kt: 38B9F8893E8E097E2C079B0F8FF27D8418CEC7B077FD2E599007FDAD21C112A9
app\src\main\java\com\tscanner\app\ui\dialogs\VipPurchaseAuthConsumer.kt: 6EBABE166DCEC711A4515E446225B1C05F15A14F407A7867C5888720F2F02378
app\src\main\java\com\tscanner\app\ui\dialogs\VipUpgradeDialog.kt: 0A8A5AA6D048EF40FAAD76F9A61CDB17674C173D0FF2D0618D46B0F2C17BF725
app\src\main\java\com\tscanner\app\ui\more\MoreFragment.kt: 75CE07449BE6B7FF9E1C574AB9CD834045823E4461AB5FE04FC4C5AEA2156737
app\src\main\java\com\tscanner\app\utils\VipLoginContinuationHandler.kt: 4CA4BC9FBD420DBEB2048234AB86BA6BECE95F3A6658D7B44699BCC21B2AC44B
```

Git status baseline được lưu tại `docs/vip-session-round5-fix/baseline_git_status.txt` (toàn bộ thay đổi unstaged/staged/untracked được giữ nguyên vẹn).

---

## 3. Kết Quả Chạy Probe Baseline (25 Tests)

Lệnh thực thi:
```powershell
$env:JAVA_HOME = 'C:\Users\nguye\.jdks\openjdk-21.0.1'
$env:GRADLE_USER_HOME = 'C:\Users\nguye\.gradle'
powershell -NoProfile -File docs/vip-session-round5-reaudit-20261006/run-probes.ps1
```

### 3.1. Tổng quan
- **Tổng số test thực thi:** 25 tests
- **PASS:** 22 tests
- **FAIL:** 3 tests (P01, P02, P03 vòng 5)
- **SKIPPED / ERRORS:** 0

### 3.2. Chi tiết 4 Probe Vòng 5 (`VipSessionRound5ProbeTest`)

| Test Case | Kết Quả | Chi Tiết Lỗi / Assertion |
|---|---|---|
| `P01_deferredUiMustAllowFirstActualProviderStart` | **FAIL** | `AssertionError: Deferred listener has not spent a provider attempt; active retry must start it expected:<1> but was:<0>` tại dòng 56 |
| `P02_providerRefusalMustNotSpendAcceptedAttempt` | **FAIL** | `AssertionError: No provider accepted first request; allow recovery when it becomes available expected:<1> but was:<0>` tại dòng 70 |
| `P03_twoConsumersMustNotStartRecoveryTwiceForSameOperation` | **FAIL** | `AssertionError: Exactly one consumer may own recovery for the operation expected:<1> but was:<2>` tại dòng 77 |
| `C01_oneActiveConsumerStartsOnce` | **PASS** | 1 RequestReauth trên 2 lần emit; không cấp VIP sớm |

### 3.3. Chi tiết 21 Regression Cũ Được Giữ Vững

- **Vòng 2 (`VipSessionReauditProbeTest`):** 7/7 PASS (P01, P02, P04, P05, P06, P07, P08).
- **Vòng 3 (`VipSessionRound3ProbeTest`):** 7/7 PASS (P01, P02, P03, P04, P05, P06, P07).
- **Vòng 4 (`VipSessionRound4ProbeTest`):** 7/7 PASS (P01, P02, P03, P04, P05, P06, P07).

---

## 4. Phân Tích Cơ Chế Lỗi & Bảng Wiring Thực Tế

### 4.1. Bảng Wiring Hiện Tại (Baseline Gap)

```
[1] BillingManager.processPurchase (AuthRequired)
       │
       ├─► attemptedRecoveryKeys.add(recoveryKey)  <-- VẤN ĐỀ: Tiêu lượt sớm trước khi consumer/provider nhận!
       │
       ▼
[2] notifyPurchaseAuthRequired(event)
       │
       ▼
[3] VipUpgradeDialog.authRequiredListener
       │
       ├─► VipPurchaseAuthConsumer.evaluate(event, ...)
       │      │
       │      ├─ Nếu inactive: trả về Defer
       │      │    --> VẤN ĐỀ: BillingManager đã đánh dấu recoveryKey; lần emit sau isRetry=true -> Stop! (P01 FAIL)
       │      │
       │      └─ Nếu active: trả về RequestReauth
       │           --> VẤN ĐỀ: Hai listener cùng nhận event đều trả RequestReauth (P03 FAIL)
       ▼
[4] VipUpgradeDialog.executePurchaseAuthDecision
       │
       ├─► dismiss()
       └─► onRequestSignInForAction?.invoke(action)  <-- VẤN ĐỀ: Kiểu (VipContinuationAction) -> Unit
                                                              Không có phản hồi accepted/refused/busy
                                                              Không mang BillingOperationContext
       ▼
[5] MoreFragment.startSignInForVipContinuation(action)
       │
       ├─► vipContinuationHandler.requestContinuation(...)
       └─► performGoogleSignIn()
              │
              ├─ Nếu false (busy): reset vipContinuationHandler
              │    --> VẤN ĐỀ: Không giải phóng reservation/attemptedRecoveryKeys; emit sau isRetry=true -> Stop! (P02 FAIL)
              │
              └─ Nếu true (started): mở Credential Manager / Intent launcher
```

### 4.2. Yêu Cầu Thiết Kế Khắc Phục (V01 & V02)

1. **State Machine / Reservation Token (V01):**
   - Không đánh dấu tiêu lượt sớm tại `BillingManager.kt` chỉ vì có UI listener.
   - Quản lý reservation trạng thái nguyên tử (atomic claim) theo `recoveryKey`:
     - Trạng thái: `Available` -> `Reserved` (1 consumer claim duy nhất) -> `Started` (host/provider xác nhận nhận việc) HOẶC `Released` (UI inactive, host từ chối, provider busy).
   - Khi consumer 1 claim `Reserved`, consumer 2 nhận `Ignore` (giải quyết P03).
   - Nếu `Defer` hoặc `release()`: reservation quay về `Available`, lượt chưa tiêu, lần emit tiếp theo vẫn được phép nhận (giải quyết P01, P02).
   - Khi provider xác nhận bắt đầu (`confirmStarted`): chuyển sang `Started`, khóa tối đa 1 accepted start cho recovery operation.
2. **Handshake Nhận/Từ Chối & Operation Context (V02):**
   - Host callback mở rộng: nhận action + `BillingOperationContext` + handshake callbacks (`onStarted`, `onRefused`).
   - `MoreFragment`: nếu `performGoogleSignIn()` thành công -> gọi `onStarted()`; nếu thất bại/busy -> gọi `onRefused()`.
   - Khi đăng nhập thành công đúng tài khoản: trigger `RESTORE` với operation context gốc, thực hiện restore receipt với **0 extra launchBillingFlow**.

---

## 5. Kết Luận Gói V00

- Baseline đã khóa an toàn.
- 3 test đỏ P01, P02, P03 và 1 test xanh C01 cùng 21 regression cũ phản ánh chính xác defect R01.
- Sẵn sàng chuyển tiếp sang **V01**.
