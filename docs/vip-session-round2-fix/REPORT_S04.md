# Báo Cáo Gói S04 — Auth Recovery Sau Receipt, Không Coi 401 Là Mạng (G03, Nhánh Verify)

**Thời gian:** 04/10/2026  
**Workspace:** `E:\DU AN AI\T-Scanner`  
**Trạng thái gói:** **PASS** (Probe P06 chuyển ĐỎ $\to$ XANH; typed `VerificationResult.AuthRequired` hoạt động chính xác; bảo toàn receipt, không gọi lại `launchBillingFlow`)

---

## 1. Mục tiêu và phạm vi gói S04

- Khắc phục lỗi G03 (nhánh Verify receipt):
  1. Trong `PlayPurchaseVerifier.kt`, khi backend xác thực trả về HTTP 401 hoặc 403, logic cũ nuốt lỗi và bọc thành `VerificationResult.TransientError(null, msg)`.
  2. Việc coi 401 là `TransientError` (lỗi mạng/tạm thời) khiến ứng dụng hiểu nhầm là mất kết nối, hiển thị toast lỗi mạng và không kích hoạt quy trình re-authentication.
  3. Cần bổ sung biến thể `VerificationResult.AuthRequired(val message: String)` vào sealed class `VerificationResult`.
  4. Trong `PlayPurchaseVerifier.parseBackendResponse`:
     - Phân tách rõ ràng: HTTP 401 trả về `VerificationResult.AuthRequired`.
     - HTTP 403 trả về `VerificationResult.Rejected(RejectionReason.OWNERSHIP_CONFLICT)`.
     - HTTP 429 và 500..599 giữ nguyên là `VerificationResult.TransientError` (retry mạng).
  5. Khi token của phiên bị thiếu hoặc hết hạn trước khi gọi backend xác thực (`token.isNullOrBlank() || isTokenExpired(token)`), không coi giao dịch là `Rejected` (tránh xóa nhầm entitlement hợp lệ), mà trả về `AuthRequired`.
  6. Trong `BillingManager.kt`:
     - Xử lý `VerificationResult.AuthRequired` mà KHÔNG gọi lại `launchBillingFlow` (người dùng đã thanh toán trên Google Play và receipt đã tồn tại).
     - Biên lai được bảo toàn trên Google Play và bộ nhớ đệm, cho phép verify/reconcile lại sau khi người dùng reauth thành công.

---

## 2. Bằng chứng kiểm thử trước và sau sửa đổi (Red $\to$ Green)

### 2.1 Bộ Probes độc lập (`VipSessionReauditProbeTest`)

Lệnh thực thi:
```powershell
$env:JAVA_HOME='C:\Users\nguye\.jdks\openjdk-21.0.1'
$env:GRADLE_USER_HOME='C:\Users\nguye\.gradle'
.\gradlew.bat :app:testDebugUnitTest --offline -I docs/vip-session-reaudit-20261004/audit.init.gradle --tests 'com.tscanner.app.VipSessionReauditProbeTest.P06_backend401MustRemainAnAuthFailureNotNetworkFailure' --console=plain
```

| Probe ID | Test Name | Trước S04 | Sau S04 | Ý nghĩa |
|---|---|---|---|---|
| **P06** | `P06_backend401MustRemainAnAuthFailureNotNetworkFailure` | **FAIL** (nhận `TransientError`) | **PASS** (nhận `AuthRequired`) | Backend 401 được định tuyến chính xác sang luồng Auth Recovery thay vì nhánh lỗi mạng |

### 2.2 Suite kiểm thử Verifier (`PlayPurchaseVerifierHttpTest`)
- Chạy toàn bộ suite `PlayPurchaseVerifierHttpTest`: **BUILD SUCCESSFUL** (100% PASS).
- Đảm bảo các kịch bản:
  - 503 / timeout / network unreachable vẫn là `TransientError`.
  - 400 / tampered JSON vẫn là `Rejected`.
  - Thành công với subscriptions và lifetime inapp.

---

## 3. Chi tiết các file đã sửa

1. **`app/src/main/java/com/tscanner/app/utils/billing/PurchaseVerifier.kt`**:
   - Thêm biến thể `data class AuthRequired(val message: String) : VerificationResult()` vào sealed class `VerificationResult`.

2. **`app/src/main/java/com/tscanner/app/utils/billing/PlayPurchaseVerifier.kt`**:
   - Trong `parseBackendResponse`:
     - Khi `statusCode == 401`: trả về `VerificationResult.AuthRequired(msg)`.
     - Khi `statusCode == 403`: trả về `VerificationResult.Rejected(RejectionReason.OWNERSHIP_CONFLICT, msg)`.
   - Trong `verifyViaRemoteBackend`:
     - Khi token trống hoặc đã hết hạn trước khi gửi request: trả về `VerificationResult.AuthRequired(...)` thay vì coi là token giả mạo `Rejected`.

3. **`app/src/main/java/com/tscanner/app/utils/BillingManager.kt`**:
   - Trong nhánh xử lý `result` của `processPurchase`:
     - Bổ sung `is VerificationResult.AuthRequired -> tryNotifyPurchaseFailure(origin, result.message, purchase, opContext); onComplete?.invoke(false)`.
     - Không gọi `launchBillingFlow`, bảo toàn nguyên vẹn receipt trên Google Play cho lần khôi phục/đồng bộ kế tiếp.

---

## 4. Hợp đồng bàn giao cho Gói S05 (Contract Handover)

1. **Vấn đề cần giải quyết ở S05:** G04 (Khóa terminal theo từng thao tác mua của Coordinator):
   - Trong `VipPurchaseActionCoordinator.kt`:
     - Hiện tại query product callback và launch billing flow không có ID phân biệt theo từng thao tác mua cụ thể (`operationId`).
     - Callback query sản phẩm cũ bị trễ có thể kết thúc hoặc làm sai lệch cờ `isActionInProgress` của thao tác mua mới (Probe P05).
     - Callback lặp có thể kích hoạt `launchBillingFlow` lần thứ 2 (Probe P04).
   - Mục tiêu: Chuyển Probe **P04** và **P05** từ FAIL $\to$ PASS.
