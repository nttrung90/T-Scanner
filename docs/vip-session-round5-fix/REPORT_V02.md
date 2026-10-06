# Báo Cáo Gói V02 — Nối Nhận/Từ Chối Và Continuation Vào Host Thật

Ngày thực hiện: 06/10/2026. Workspace: `E:\DU AN AI\T-Scanner`.

---

## 1. Mục Tiêu & Phạm Vi Gói V02

- Kết nối toàn diện cơ chế atomic reservation và lifecycle handshake từ V01 vào production UI và host coordinator thật:
  1. `VipUpgradeDialog`: chuyển giao quyền điều phối receipt recovery qua callback có handshake hai chiều: `onRequestSignInForRecovery: (action, opContext, onStarted, onRefused) -> Unit`.
  2. Bắt tay hai chiều với host: chỉ gọi `confirmStarted()` khi host thực sự tiếp nhận và khởi động auth flow; gọi `release()` để hoàn trả lượt khi host bận hoặc từ chối.
  3. Duy trì bất biến `BillingOperationContext` xuyên suốt vòng đời: từ lúc phát sinh lỗi auth 401, lưu giữ trong continuation (`VipLoginContinuationHandler`), chuyển giao qua kết quả đăng nhập thành công đến khâu khôi phục receipt (`BillingManager.restorePurchases(..., opContext)`).
  4. Đảm bảo luồng khôi phục receipt thực hiện phục hồi receipt đã có với **0 extra launchBillingFlow**, tuyệt đối không mở giao dịch mua mới.
  5. Bảo toàn 100% các hành vi nâng cấp/khôi phục VIP thông thường của Guest và người dùng hợp lệ, không gây hồi quy (regressions).

---

## 2. Chi Tiết Các File Can Thiệp

### 2.1. `app/src/main/java/com/tscanner/app/utils/VipLoginContinuationHandler.kt`
- Mở rộng data class `ContinuationState` với trường `originatingOperationContext: BillingOperationContext? = null`.
- Cập nhật hàm `requestContinuation`: tiếp nhận và lưu trữ `originatingOperationContext`.
- Cập nhật hàm `reset()`: đặt lại `originatingOperationContext = null`.
- Bổ sung overload cho `onSignInSuccessWithAction`:
  ```kotlin
  fun onSignInSuccessWithAction(
      account: GoogleSignInAccount?,
      onExecuteAction: (VipContinuationAction, String?, BillingOperationContext?) -> Unit
  )
  ```
  Truyền đầy đủ action, targetSku và `originatingOperationContext` đã lưu tới callback điều phối.

### 2.2. `app/src/main/java/com/tscanner/app/utils/BillingManager.kt`
- Cập nhật hàm `restorePurchases`:
  ```kotlin
  fun restorePurchases(activity: Activity? = null, opContext: BillingOperationContext? = null)
  ```
- Truyền `opContext` vào `performReconciliation(activity, opContext)`.
- Trong `performReconciliation`: ưu tiên tái sử dụng `opContext` được chuyển tiếp từ quá trình reauth để tiếp tục ngữ cảnh thao tác của người dùng, không tạo mới nếu đã có.

### 2.3. `app/src/main/java/com/tscanner/app/ui/dialogs/VipUpgradeDialog.kt`
- Bổ sung thuộc tính callback chuyên biệt cho recovery:
  ```kotlin
  var onRequestSignInForRecovery: ((action: VipContinuationAction, opContext: BillingOperationContext?, onStarted: () -> Unit, onRefused: () -> Unit) -> Unit)? = null
  ```
- Trong `executePurchaseAuthDecision(decision: PurchaseAuthDecision)`:
  - Khi `decision is PurchaseAuthDecision.RequestReauth`:
    - Nếu `onRequestSignInForRecovery != null`: chuyển giao cho host với `onStarted = { decision.confirmStarted() }` và `onRefused = { decision.release() }`.
    - Nếu không có callback riêng: fallback về `onRequestSignIn(decision.action)` kèm `decision.confirmStarted()`.

### 2.4. Các Host Production (`MoreFragment`, `IdCardComposeActivity`, `PdfViewerActivity`)
- **`MoreFragment.kt`**:
  - Nâng cấp `startSignInForVipContinuation` nhận `(action, opContext, onStarted, onRefused)`.
  - Kiểm tra trạng thái sẵn sàng của auth launcher: nếu bận hoặc từ chối gọi `onRefused?.invoke()`; nếu tiếp nhận thành công gọi `onStarted?.invoke()`.
  - Trong `showVipUpgradeDialog()`: nối `onRequestSignInForRecovery` vào `startSignInForVipContinuation`.
  - Trong callback `onSignInSuccessWithAction`: nhận `(action, sku, opContext)` và gọi `billingManager.restorePurchases(requireActivity(), opContext)`.
- **`IdCardComposeActivity.kt`** & **`PdfViewerActivity.kt`**:
  - Trong `showVipUpgradeDialog()`: cấu hình `onRequestSignInForRecovery` với logic handshake `onStarted` / `onRefused` tương ứng và chuyển tiếp `opContext` vào `executeRestorePurchases(opContext)`.

---

## 3. Kiểm Thử Tích Hợp Chuyên Sâu (`VipPurchaseRecoveryIntegrationTest.kt`)

Đã xây dựng suite tích hợp gồm 7 kịch bản hợp đồng bắt buộc theo kế hoạch V02:
Lệnh chạy: `.\gradlew.bat :app:testDebugUnitTest --tests "com.tscanner.app.VipPurchaseRecoveryIntegrationTest" --offline --console=plain`  
Kết quả: **7 tests completed, 7 passed, 0 failed, 0 errors**.

| Test Contract | Nội dung kiểm chứng | Kết quả |
|---|---|---|
| `contract1_inactiveUiThenActive_acceptedStartsZeroToOne` | UI inactive trả Defer không tiêu lượt; khi UI active trở lại acceptedStarts tăng từ 0 lên 1. | **PASS** |
| `contract2_providerBusyRefusalThenAvailable_acceptedStartsZeroToOne` | Provider bận/từ chối giải phóng reservation; khi provider sẵn sàng chấp nhận recovery một lần duy nhất. | **PASS** |
| `contract3_duplicateConsumers_onlyOneProviderInvocationAllowed` | Nhiều consumer đồng thời xử lý cùng một operation: atomic reservation chỉ cho phép đúng 1 consumer khởi chạy provider. | **PASS** |
| `contract4_acceptedReauthToSuccess_restoresOnceWithZeroExtraLaunch` | Khôi phục sau auth thành công phục hồi receipt 1 lần, bảo đảm 0 extra billing flow launch; 401 lặp lại trong cùng operation bị chặn (Stop). | **PASS** |
| `contract5_cancelOrFailureThenNewOperation_allowsRetryAndStaleCallbackSafe` | Người dùng hủy/thất bại rồi thực hiện thao tác mới: được cấp lượt retry mới; callback trễ từ attempt cũ không phá vỡ reservation mới. | **PASS** |
| `contract6_ownerOrGenerationChange_suppressesStaleAuthAndRestore` | Thay đổi tài khoản hoặc session generation vô hiệu hóa sự kiện cũ, chặn auth/restore sai owner. | **PASS** |
| `contract7_guestUpgradeToFirstAccount_validContinuation` | Guest nâng cấp lên tài khoản Google hợp lệ tiếp tục luồng mua/khôi phục bình thường. | **PASS** |

---

## 4. Bằng Chứng Probe Regression (25 Probes)

Lệnh chạy: `powershell -NoProfile -File docs/vip-session-round5-fix/run-probes.ps1`  
Mã thoát: `GRADLE_EXIT=0` — **BUILD SUCCESSFUL**.

- `VipSessionRound5ProbeTest`: **4/4 PASS** (P01, P02, P03, C01)
- `VipSessionRound4ProbeTest`: **7/7 PASS**
- `VipSessionRound3ProbeTest`: **7/7 PASS**
- `VipSessionReauditProbeTest`: **7/7 PASS**
- **Tổng cộng: 25/25 probes PASS (100%)**.

---

## 5. Kết Luận Gói V02

- Hoàn thành đầy đủ việc kết nối handshake, atomic reservation và chuyển giao `BillingOperationContext` trên toàn bộ các host thực tế.
- Tất cả các hợp đồng tích hợp và 25 probes kiểm định đều đạt 100%.
- Sẵn sàng tiến hành gói cuối cùng **V03** (Kiểm chứng toàn diện, đối soát build artifacts và lập báo cáo bàn giao).
