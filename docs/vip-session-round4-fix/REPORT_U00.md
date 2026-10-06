# Báo Cáo Kiểm Tra & Khóa Hợp Đồng Gói U00

**Ngày thực hiện:** 05/10/2026  
**Workspace:** `E:\DU AN AI\T-Scanner`  
**Gói công việc:** U00 (Theo `PLAN_FIX_VIP_SESSION_ROUND4_GEMINI_2026-10-05.md`)

---

## 1. Mục Tiêu & Phạm Vi (Scope)

- **Mục tiêu:**
  - Khóa baseline trước khi sửa Round 4 (K01–K03).
  - Ghi nhận trạng thái git status và mã hash SHA256 của các file nguồn thuộc diện sửa đổi.
  - Chạy lại 7 probes Vòng 4 nguyên bản (`VipSessionRound4ProbeTest.kt`) để xác nhận chính xác 5 FAIL và 2 PASS (controls).
  - Chạy lại 14 bài kiểm thử hồi quy phù hợp của Vòng 2 và Vòng 3 để đảm bảo các sửa đổi trước không bị ảnh hưởng.
  - Thiết kế và chuẩn bị production consumer `VipPurchaseAuthConsumer` và kiểu kết quả `PurchaseAuthDecision` (RequestReauth / Stop / Ignore / Defer), kết nối trực tiếp vào `VipUpgradeDialog.handlePurchaseAuthRequired`.

---

## 2. Bằng Chứng Thực Chạy (Evidence)

### 2.1. Mã băm SHA256 các file sản xuất trước khi sửa
Đã lưu trữ tại `docs/vip-session-round4-fix/baseline_hashes.txt`:
- `BillingManager.kt`: `CD0998D0372B8FACD8C8C70DAC122A49B294EE094A633494C5EC1F12ED92C567`
- `VipUpgradeDialog.kt`: `36132B7A971BBB99F95364984AF09A5A4567D1C128EC9C5CF49D7716D10F223C`
- `MoreFragment.kt`: `FBDFF0B5DD34B701104BE4F9D3E2627A3AED464599C1574D7EF9AE6D59D35884`
- `VipLoginContinuationHandler.kt`: `4CA4BC9FBD420DBEB2048234AB86BA6BECE95F3A6658D7B44699BCC21B2AC44B`
- `MainActivity.kt`: `2F3AB5046FA8B66B9227623DC766F2496C158DB61B14282A3800D94E4AF74F1D`
- `HomeFragment.kt`: `E700EFA1BF40EE6C8A6F4B8530C0A3CE6E40156FC2215A5C9993808EA6F2FACF`

### 2.2. Kết quả chạy 7 probes Vòng 4 (`VipSessionRound4ProbeTest`)
- Lệnh thực thi:
  ```powershell
  .\gradlew.bat :app:testDebugUnitTest --offline -I docs/vip-session-round4-reaudit-20261005/audit.init.gradle --tests 'com.tscanner.app.VipSessionRound4ProbeTest' --console=plain
  ```
- **Kết quả: 5 FAILED / 2 PASSED (Chính xác theo RECHECK_VIP_SESSION_ROUND4)**
  - `P01_backgroundVerificationMustNotSpendInteractiveRecoveryAttempt`: **FAILED** (RECONCILE nền tiêu tốn lượt trước khi vào foreground).
  - `P02_noListenerMustNotSpendRecoveryBeforeUserCanAuthenticate`: **FAILED** (Sự kiện khi chưa có UI listener gắn kết vẫn bị tính là đã retry).
  - `P03_newExplicitOperationMustNotInheritReceiptLifetimeRetryFlag`: **FAILED** (Thao tác mới với operation ID mới bị dùng lại retry flag của thao tác cũ).
  - `P04_sessionChangeDuringDispatchMustSuppressRemainingOldEvents`: **FAILED** (Sự kiện của user A lọt sang consumer sau khi session chuyển sang user B).
  - `P05_oldGuestNavigationMustNotRunInNewGuestSession`: **FAILED** (Navigation bắt đầu ở guest session cũ bị Accept ở guest session mới khác generation).
  - `C01_firstInteractiveEventIsNotRetryAndDoesNotGrantVip`: **PASSED** (Control).
  - `C02_matchingGuestNavigationIsAccepted`: **PASSED** (Control).

### 2.3. Kết quả chạy 14 regressions Vòng 2 & Vòng 3
- Lệnh thực thi:
  ```powershell
  .\gradlew.bat :app:testDebugUnitTest --offline -I docs/vip-session-round4-reaudit-20261005/audit.init.gradle --tests 'com.tscanner.app.VipSessionRound3ProbeTest' --tests 'com.tscanner.app.VipSessionReauditProbeTest.P01*' --tests 'com.tscanner.app.VipSessionReauditProbeTest.P02*' --tests 'com.tscanner.app.VipSessionReauditProbeTest.P04*' --tests 'com.tscanner.app.VipSessionReauditProbeTest.P05*' --tests 'com.tscanner.app.VipSessionReauditProbeTest.P06*' --tests 'com.tscanner.app.VipSessionReauditProbeTest.C01*' --tests 'com.tscanner.app.VipSessionReauditProbeTest.C02*' --console=plain
  ```
- **Kết quả: 14/14 PASSED (BUILD SUCCESSFUL)**.

---

## 3. Thiết Kế Seam Sản Xuất (Production Consumer Seam)

1. **Khởi tạo `VipPurchaseAuthConsumer` (`app/src/main/java/com/tscanner/app/ui/dialogs/VipPurchaseAuthConsumer.kt`):**
   - Độc lập với Android View framework, cho phép kiểm thử trực tiếp trên host JVM mà không cần robolectric hay mock View.
   - Nhận:
     - `event: PurchaseAuthRequiredEvent` (immutable)
     - `currentOwnerId: String?`
     - `currentGeneration: Long`
     - `currentEpoch: String`
     - `isUiActive: Boolean`
   - Xuất: `PurchaseAuthDecision` (RequestReauth / Stop / Ignore / Defer).
2. **Nối vào `VipUpgradeDialog.kt`:**
   - Hàm `handlePurchaseAuthRequired(event)` và seam `@VisibleForTesting handlePurchaseAuthRequiredWithDecision(event, ...)` gọi trực tiếp `VipPurchaseAuthConsumer.evaluate(...)`.
   - UI chỉ thực thi quyết định: hiển thị Toast, dismiss, hoặc kích hoạt callback re-auth (`RESTORE`).

---

## 4. Nghiệm thu & Chuyển giao
- Tiêu chí gói U00: **ĐẠT (PASS)**.
- Sẵn sàng tiến hành gói **U01** để giải quyết triệt để **K01** (hạn mức reauth theo user operation).
