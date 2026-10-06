# BÁO CÁO TỔNG KẾT VÒNG 6 — KHÉP ADMISSION & CÁC ENTRY POINT VIP (W00–W03)

**Ngày báo cáo:** 06/10/2026  
**Dự án:** `E:\DU AN AI\T-Scanner`  
**Chuyên trách:** Gemini (Antigravity)  
**Tiêu chuẩn an toàn:** Tuân thủ tuyệt đối E00–E06, không commit/push/reset/stash/clean, không đổi signing/OAuth/backend/version/R8.

---

## 1. Tóm Tắt Kết Quả Toàn Vòng (Executive Summary)

Vòng 6 đã giải quyết triệt để hai khuyết tật trọng tâm **S01** và **S02** theo `PLAN_FIX_VIP_SESSION_ROUND6_GEMINI_2026-10-06.md` và `RECHECK_VIP_SESSION_ROUND6_2026-10-06.md`:
1. **S01 (Authoritative Ledger & Dispatch No-Spend):** Loại bỏ việc tiêu trước recovery budget (`attemptedRecoveryKeys.add`) khi dispatch hoặc invoke listener trong `BillingManager`. Hợp nhất trạng thái sang một sổ cái duy nhất (`VipPurchaseAuthConsumer`), chỉ commit attempt khi host/provider thực sự chấp nhận (`onStarted()` / `confirmStarted()`).
2. **S02 (Recovery Handshake & Context Across All Entry Points):** Xóa bỏ việc gọi sớm `confirmStarted()` khi callback là `Unit` hoặc `null` trong `VipUpgradeDialog`. Nối toàn diện luồng recovery handshake hai chiều (`onStarted`, `onRefused`) và chuyển giao an toàn `BillingOperationContext` qua toàn bộ 6 điểm vào: Direct More, Direct PdfViewer, Direct IdCard, Home $\to$ More Navigation (qua `VipRecoveryRegistry`), AccountDetail Dialog Factory, và CreatePdf Dialog (bảo toàn file name và watermark).
3. **Chuyển biến Red $\to$ Green:**
   - 27 Probes: Từ **26 PASS / 1 FAIL** $\to$ **27 PASS / 0 FAIL (100% GREEN)**. Probe `P01` đã chuyển đỏ sang xanh.
   - Toàn bộ Unit Tests: **1.098 tests PASS / 0 FAIL / 0 SKIPPED** (Tăng từ baseline 1.090).
   - Kiểm tra Lint & Đóng gói: `lintDebug` và `assembleDebug` đều **BUILD SUCCESSFUL**.
   - Cổng thiết bị: Được ghi nhận chính xác là **BLOCKED_EXTERNAL** do môi trường máy chủ không kết nối thiết bị thật/Google Play.

---

## 2. Bảng Call Sites và Điểm Vào UI Đã Khép (S02)

| Entry Point | Nơi phát sinh / Điều phối | Cơ chế chuyển giao & Handshake | Bảo toàn Context & Dữ liệu |
|---|---|---|---|
| **1. Direct MoreFragment** | `MoreFragment` mở `VipUpgradeDialog` | Dialog nhận event $\to$ forward `(action, opCtx, onStarted, onRefused)` sang `startSignInForVipContinuation` $\to$ `executeVipContinuation`. | `BillingOperationContext` giữ nguyên tới `restorePurchases`. |
| **2. Direct PdfViewerActivity** | `PdfViewerActivity` gọi `VipUpgradeDialog` | Khởi tạo dialog với `onRequestSignInForRecovery`, forward handshake 2 chiều. | Context được giữ nguyên, 0 extra purchase launch. |
| **3. Direct IdCardComposeActivity** | `IdCardComposeActivity` gọi `VipUpgradeDialog` | Tương tự, nhận và chuyển tiếp recovery handshake an toàn. | Context được bảo toàn. |
| **4. Home $\to$ More Navigation** | `HomeFragment` $\to$ `MainActivity` $\to$ `MoreFragment` | Sử dụng typed `VipRecoveryRegistry` lưu request theo `operationId` mà không đưa closure vào `Bundle`. Nếu `VipNavigationValidator` quyết định `Discard` $\to$ gọi `onRefused()` giải phóng reservation; nếu `Accept` $\to$ gọi `onStarted()`. | Giữ vững owner, session generation, process epoch và `operationId`. |
| **5. AccountDetailDialog $\to$ VIP** | `AccountDetailDialog.vipUpgradeDialogFactory` | Bổ sung tham số `onRequestSignInForRecovery`, nối vào factory khởi tạo `VipUpgradeDialog` bên trong. Tương thích ngược với callers 6 tham số. | Đảm bảo khi dialog chi tiết tài khoản gặp 401 thì recovery callback vẫn được kết nối. |
| **6. CreatePdfDialog $\to$ VIP** | `CreatePdfDialog` lồng `VipUpgradeDialog` | Bổ sung callback `onRequestSignInForRecovery(action, currentName, opContext, onStarted, onRefused)` chuyển tiếp sang `VipUpgradeDialog`. | **Bảo toàn tên tệp PDF hiện tại (`currentName`) và trạng thái watermark** khi người dùng reauth thành công. |

---

## 3. Bằng Chứng Red $\to$ Green và Di Chuyển Test Fixtures

### 3.1. Bằng chứng Red $\to$ Green
- **Trước khi sửa (W00 Baseline):**
  - `VipSessionRound6ProbeTest.P01_listenerDropsBeforeConsumerMustNotSpendRecovery`: **FAIL**
  - Lỗi: `expected:<1> but was:<0>` (Do dispatch gọi `attemptedRecoveryKeys.add(recoveryKey)` khiến lượt bị tiêu trước khi consumer có cơ hội nhận).
- **Sau khi sửa (W01 $\to$ W03):**
  - `VipSessionRound6ProbeTest.P01_listenerDropsBeforeConsumerMustNotSpendRecovery`: **PASS**
  - `VipSessionRound6ProbeTest.C01_explicitRefusalThenAcceptanceWorksAndDoesNotRepeat`: **PASS**
  - Tổng số probes: **27/27 PASS (GRADLE_EXIT=0)**.

### 3.2. Di chuyển Test Fixtures (Minh bạch, không che lỗi)
1. **`VipSessionRound3IntegrationTest.kt`**:
   - *Nguyên nhân di chuyển:* Ở Round 3 cũ, test giả định chỉ cần dispatch là `isRetry = true` ngay ở lần dispatch thứ hai. Ở Round 6, theo hợp đồng kiến trúc S01, dispatch không tiêu budget mà phải qua bước consumer $\to$ provider accepted qua production seam.
   - *Cách di chuyển:* Test được cập nhật để provider gọi `confirmStarted()` trước khi phát sinh event 401 thứ hai. Xác nhận chính xác event thứ hai trả về `Stop` và không mở luồng reauth trùng lặp.
2. **`VipPurchaseRecoveryIntegrationTest.kt`**:
   - `contract3`: Kiểm tra duplicate consumer bằng **2 consumer lambdas độc lập khác nhau** (`consumerA` và `consumerB`) thay vì dùng 1 lambda lặp lại.
   - `contract4`: Assert `restoredSuccess = success` thực tế từ callback restore thay vì gán cờ `true` tĩnh.

---

## 4. Danh Sách Files Thay Đổi & Tạo Mới

### Mã nguồn Production (`app/src/main/`)
1. `app/src/main/java/com/tscanner/app/utils/BillingManager.kt`:
   - Thêm `onCommitAttempt` vào `PurchaseAuthRequiredEvent`.
   - Bỏ `attemptedRecoveryKeys.add` khỏi dispatch; uỷ quyền kiểm tra trạng thái sang `VipPurchaseAuthConsumer.isRecoveryStartedOrCompleted`.
2. `app/src/main/java/com/tscanner/app/ui/dialogs/VipPurchaseAuthConsumer.kt`:
   - Thêm hàm `isRecoveryStartedOrCompleted(recoveryKey)`.
   - Gọi `event.commitAttempt()` khi `confirmStarted()` được thực thi.
3. `app/src/main/java/com/tscanner/app/ui/dialogs/VipUpgradeDialog.kt`:
   - An toàn gọi `decision.release()` khi fallback `onRequestSignInForRecovery` là null.
   - Sử dụng `hostContext` và bọc `dismiss()`, `Toast.makeText()` trong `runCatching` để ngăn ngoại lệ UI làm gián đoạn luồng recovery.
4. `app/src/main/java/com/tscanner/app/utils/billing/VipRecoveryRegistry.kt` *(Tạo mới)*:
   - Sổ đăng ký typed envelope an toàn cho điều hướng Home $\to$ More.
5. `app/src/main/java/com/tscanner/app/ui/home/HomeFragment.kt`:
   - Đăng ký envelope vào `VipRecoveryRegistry` khi điều hướng sang More.
6. `app/src/main/java/com/tscanner/app/ui/more/MoreFragment.kt`:
   - Tiêu thụ `VipRecoveryRegistry`, xử lý `Discard` (gọi `onRefused`) và `Accept` (gọi `onStarted`).
7. `app/src/main/java/com/tscanner/app/ui/dialogs/AccountDetailDialog.kt`:
   - Bổ sung tham số và dây nối `onRequestSignInForRecovery`.
8. `app/src/main/java/com/tscanner/app/ui/dialogs/CreatePdfDialog.kt`:
   - Nối `onRequestSignInForRecovery` bảo toàn `currentName`.
9. `app/src/main/java/com/tscanner/app/ui/viewer/PdfViewerActivity.kt`:
   - Cung cấp `onRequestSignInForRecovery` cho cả 2 call sites của `CreatePdfDialog`.

### Mã nguồn Kiểm thử (`app/src/test/`)
1. `app/src/test/java/com/tscanner/app/VipEntryPointsRecoveryIntegrationTest.kt` *(Tạo mới)*: 6 tests bao phủ 6 entry points.
2. `app/src/test/java/com/tscanner/app/VipPurchaseAuthConsumerTest.kt`: Bổ sung 2 tests cho authoritative ledger.
3. `app/src/test/java/com/tscanner/app/VipSessionRound3IntegrationTest.kt`: Di chuyển fixture accepted provider.
4. `app/src/test/java/com/tscanner/app/VipPurchaseRecoveryIntegrationTest.kt`: Sửa contract3 và contract4.

### Tài liệu và Thư mục Bằng chứng (`docs/vip-session-round6-fix/`)
- `REPORT_W00.md`, `REPORT_W01.md`, `REPORT_W02.md`, `REPORT_W03.md`, `REPORT_FINAL.md`.
- `run-probes.ps1`, `audit.init.gradle`, `baseline_hashes.txt`, `baseline_git_status.txt`, `probes.log`.
- Toàn bộ XML reports của các probe và integration test suites.

---

## 5. Bảng Tổng Hợp Kiểm Thử & Metric Bàn Giao

| Nhóm Kiểm Thử / Chỉ Tiêu | Số lượng | Kết quả | Trạng thái |
|---|:---:|:---:|:---:|
| **27 Probes (`run-probes.ps1`)** | 27 | 27 PASS, 0 FAIL | **PASS** |
| **`VipEntryPointsRecoveryIntegrationTest`** | 6 | 6 PASS, 0 FAIL | **PASS** |
| **`VipPurchaseRecoveryIntegrationTest`** | 7 | 7 PASS, 0 FAIL | **PASS** |
| **`VipSessionRound3IntegrationTest`** | 3 | 3 PASS, 0 FAIL | **PASS** |
| **`VipPurchaseAuthConsumerTest`** | 10 | 10 PASS, 0 FAIL | **PASS** |
| **Full Unit Tests (`:app:testDebugUnitTest`)** | **1.098** | **1.098 PASS, 0 FAIL, 0 SKIPPED** | **PASS** |
| **Lint Check (`:app:lintDebug`)** | 31 tasks | BUILD SUCCESSFUL | **PASS** |
| **Build APK (`:app:assembleDebug`)** | 40 tasks | BUILD SUCCESSFUL | **PASS** |
| **Restores per Accepted Recovery** | Đếm thực tế | **Chính xác 1** | **PASS** |
| **Extra Purchase Launches khi Recover** | Đếm thực tế | **Chính xác 0** | **PASS** |
| **Device / Google Play Verification** | Cổng thiết bị | Không có thiết bị ngoại vi | **BLOCKED_EXTERNAL** |

---

## 6. Kết Luận & Bàn Giao

1. Toàn bộ khuyết tật **S01** và **S02** đã được khắc phục hoàn chỉnh, nhất quán trên toàn bộ kiến trúc từ `BillingManager`, `VipPurchaseAuthConsumer`, tới mọi tầng UI Dialog và Fragment.
2. Không còn hiện tượng tiêu sớm recovery budget khi dispatch hoặc khi listener bị hủy trước consumer.
3. Không có bất kỳ giao dịch mua mới nào bị phát sinh ngoài ý muốn khi khôi phục receipt (`extra launches = 0`).
4. Toàn bộ các quy tắc ràng buộc E00–E06 được giữ nguyên vẹn 100%. Quá trình thực hiện dừng lại ở bước bàn giao kỹ thuật để chờ người dùng kiểm tra lại, không tự ý commit, push hay phát hành.
