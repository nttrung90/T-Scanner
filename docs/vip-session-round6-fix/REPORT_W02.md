# REPORT_W02 — Nối Handshake Recovery & Lan Truyền Context Toàn Diện Qua Mọi Điểm Vào (S02)

**Ngày thực hiện:** 06/10/2026  
**Gói công việc:** W02 — Wire Recovery Handshake & Preserve Origin Context Across All Entry Points  
**Workspace:** `E:\DU AN AI\T-Scanner`  
**Baseline trước gói:** W01 đạt 27/27 probes xanh, authoritative ledger commit-on-accept hoạt động đúng.

---

## 1. Mục tiêu và Phạm vi (S02)

1. **Khắc phục S02 (Đầu cuối các điểm vào UI & Dialog):**
   - Loại bỏ hoàn toàn việc gọi `confirmStarted()` sớm khi fallback callback là `Unit` hoặc khi `onRequestSignInForRecovery` bị `null`. Khi thiếu host có khả năng recovery hoặc fallback không hỗ trợ, an toàn gọi `decision.release()` để giải phóng reservation, tránh lạm tiêu budget.
   - Nối luồng recovery handshake hai chiều (`onStarted`, `onRefused`) qua tất cả các điểm vào UI:
     1. **Direct MoreFragment:** Nhận event, evaluate reauth, forward `BillingOperationContext` vào `startSignInForVipContinuation` và `executeVipContinuation`.
     2. **Direct PdfViewerActivity:** Nhận event từ `CreatePdfDialog` hoặc reader, chuyển tiếp recovery callback với tên file và watermark được bảo toàn.
     3. **Direct IdCardComposeActivity:** Nhận event, chuyển tiếp recovery handshake và context an toàn.
     4. **Home -> More Navigation:** Tạo `VipRecoveryRegistry` làm kho lưu trữ typed in-memory envelope để truyền an toàn các closure `(onStarted, onRefused)` và `BillingOperationContext` mà không làm rò rỉ closure qua Android `Bundle`. Khi điều hướng bị Discard (sai owner, session gen mismatch), tự động gọi `onRefused()`; khi Accept, gọi `onStarted()`.
     5. **AccountDetailDialog -> VipUpgradeDialog:** Bổ sung tham số `onRequestSignInForRecovery` vào `AccountDetailDialog`, truyền an toàn xuống factory `vipUpgradeDialogFactory` trong khi duy trì khả năng tương thích ngược hoàn toàn (overload 6 tham số cũ).
     6. **CreatePdfDialog -> VipUpgradeDialog:** Bổ sung tham số `onRequestSignInForRecovery` kèm `(currentName: String)` bảo toàn tên tài liệu và thiết lập watermark, forward đầy đủ sang `VipUpgradeDialog`.
2. **Di chuyển Test Fixture:**
   - Cập nhật `VipSessionRound3IntegrationTest`: Phải có accepted provider qua production seam trước khi assert chặn lần 2 (`Stop`, `isRetry = true`).
   - Cập nhật `VipPurchaseRecoveryIntegrationTest`:
     - `contract3`: Kiểm tra duplicate với 2 consumer khác nhau thay vì dùng 1 consumer lặp lại.
     - `contract4`: Assert `restoredSuccess = success` thực sự thay vì chỉ kiểm tra số lần gọi.
3. **Bộ kiểm thử tích hợp chuyên biệt:**
   - Tạo mới `VipEntryPointsRecoveryIntegrationTest.kt` kiểm thử toàn bộ 6 entry points.

---

## 2. Các file đã chỉnh sửa & tạo mới

1. **`app/src/main/java/com/tscanner/app/utils/billing/VipRecoveryRegistry.kt`** (Tạo mới):
   - Đăng ký và tiêu thụ an toàn `VipRecoveryRequest(action, operationContext, expectedOwnerId, originGeneration, processEpoch, onStarted, onRefused)` theo `operationId`.
   - Hỗ trợ TTL cleanup và reset phục vụ testing.
2. **`app/src/main/java/com/tscanner/app/ui/home/HomeFragment.kt`**:
   - Trong `showVipUpgradeDialog()`, cấu hình `onRequestSignInForRecovery` đăng ký request vào `VipRecoveryRegistry` và điều hướng sang `MoreFragment` mang theo `operationId`.
3. **`app/src/main/java/com/tscanner/app/ui/more/MoreFragment.kt`**:
   - Khi nhận `REQUEST_KEY_VIP_SIGN_IN`, tiêu thụ từ `VipRecoveryRegistry`.
   - Nếu validation `Discard`: gọi `req.onRefused()` giải phóng reservation.
   - Nếu validation `Accept`: gọi `startSignInForVipContinuation` với `onStarted = req.onStarted` và `onRefused = req.onRefused`, truyền `opCtx` vào `executeVipContinuation`.
4. **`app/src/main/java/com/tscanner/app/ui/dialogs/AccountDetailDialog.kt`**:
   - Bổ sung tham số `onRequestSignInForRecovery` vào constructor chính và overload tương thích ngược.
   - Cập nhật `vipUpgradeDialogFactory` mặc định nối `onRequestSignInForRecovery` sang `VipUpgradeDialog`.
5. **`app/src/main/java/com/tscanner/app/ui/dialogs/CreatePdfDialog.kt`**:
   - Bổ sung tham số `onRequestSignInForRecovery: ((VipContinuationAction, String, BillingOperationContext?, () -> Unit, () -> Unit) -> Unit)?`.
   - Chuyển tiếp callback và giữ nguyên `currentName` sang `VipUpgradeDialog`.
6. **`app/src/main/java/com/tscanner/app/ui/viewer/PdfViewerActivity.kt`**:
   - Truyền `onRequestSignInForRecovery` khi khởi tạo `CreatePdfDialog` tại cả 2 callsite (line 758 và line 868).
7. **`app/src/main/java/com/tscanner/app/ui/dialogs/VipUpgradeDialog.kt`**:
   - Sử dụng `hostContext` trong `executePurchaseAuthDecision` và bọc các thao tác UI thông báo (`dismiss()`, `Toast.makeText()`) trong `runCatching` để đảm bảo luồng điều phối business recovery không bao giờ bị gián đoạn.
8. **`app/src/test/java/com/tscanner/app/VipSessionRound3IntegrationTest.kt`**:
   - Di chuyển fixture sang mô hình provider accepted trước khi kiểm tra reauth block lần 2.
9. **`app/src/test/java/com/tscanner/app/VipPurchaseRecoveryIntegrationTest.kt`**:
   - `contract3`: Dùng 2 consumer lambdas độc lập.
   - `contract4`: Assert `restoredSuccess = true` thực tế.
10. **`app/src/test/java/com/tscanner/app/VipEntryPointsRecoveryIntegrationTest.kt`** (Tạo mới):
   - 6 test cases bao phủ trọn vẹn 6 entry points.

---

## 3. Kết quả Kiểm thử Thực tế

| Nhóm kiểm thử / Lệnh | Số Test | PASS | FAIL | Trạng thái |
|---|:---:|:---:|:---:|:---:|
| `VipEntryPointsRecoveryIntegrationTest` | 6 | 6 | 0 | **PASS** |
| `VipPurchaseRecoveryIntegrationTest` | 7 | 7 | 0 | **PASS** |
| `VipSessionRound3IntegrationTest` | 3 | 3 | 0 | **PASS** |
| `VipPurchaseAuthConsumerTest` | 10 | 10 | 0 | **PASS** |
| 27 Probes (`run-probes.ps1`) | 27 | 27 | 0 | **PASS (GRADLE_EXIT=0)** |

XML Reports xác nhận:
- `TEST-com.tscanner.app.VipEntryPointsRecoveryIntegrationTest.xml`: `tests="6" failures="0" errors="0"`
- `TEST-com.tscanner.app.VipPurchaseRecoveryIntegrationTest.xml`: `tests="7" failures="0" errors="0"`
- `TEST-com.tscanner.app.VipSessionRound3IntegrationTest.xml`: `tests="3" failures="0" errors="0"`
- `TEST-com.tscanner.app.VipSessionRound6ProbeTest.xml`: `tests="2" failures="0" errors="0"`

---

## 4. Kết luận Nghiệm thu Gói W02

Tiêu chí nghiệm thu của W02 đã đạt đầy đủ:
- Tồn tại và hoạt động thông suốt cơ chế truyền envelope recovery an toàn (`VipRecoveryRegistry`) giữa `HomeFragment` và `MoreFragment`.
- Cả 6 điểm vào UI đều nhận, lan truyền context và thực hiện handshake `(onStarted, onRefused)` đúng đặc tả.
- Không có extra purchase launch nào xảy ra khi recover receipt.
- Fixture test integration đã được di chuyển đúng theo hợp đồng kiến trúc Round 6.
- Toàn bộ 27 probes và test suites liên quan đều xanh 100%.

Sẵn sàng chuyển sang **W03 — Verification, Regression, Lint, Build & Handover**.
