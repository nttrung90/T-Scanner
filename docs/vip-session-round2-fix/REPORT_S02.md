# Báo Cáo Gói S02 — Áp Dụng Owner/Action Contract Cho PDF Và ID-Card (G02)

**Thời gian:** 04/10/2026  
**Workspace:** `E:\DU AN AI\T-Scanner`  
**Trạng thái gói:** **PASS** (Hoàn thành đầy đủ contract G02 cho PdfViewerActivity và IdCardComposeActivity; tests host đạt 100%)

---

## 1. Mục tiêu và phạm vi gói S02

- Khắc phục triệt để lỗi G02:
  1. Trong `PdfViewerActivity` và `IdCardComposeActivity`, việc gọi `signInWithGoogle` không truyền `expectedOwnerId` dẫn tới nguy cơ chọn tài khoản Google B sẽ ghi đè tài khoản A của người dùng khi đang thao tác dở tài liệu / ảnh thẻ.
  2. Cả hai host khởi tạo `VipUpgradeDialog` (hoặc thông qua `CreatePdfDialog`) chỉ truyền callback `onRequestSignIn` chung chung mà thiếu `onRequestSignInForAction`. Khi người dùng bấm Khôi phục (`RESTORE`) trong dialog, hành động bị thoái lui thành `UPGRADE` (mua gói).
  3. `vipContinuationHandler.requestContinuation()` được gọi mà không truyền ngữ cảnh (`action`, `sessionGeneration`, `initialOwnerId`, `processEpoch`).
  4. Sau khi đăng nhập thành công, `handleSignInSuccess` luôn mở lại dialog nâng cấp, không thực hiện khôi phục khi hành động ban đầu là `RESTORE`.
- Ràng buộc và bảo vệ:
  - Nếu đã có profile A: truyền `expectedOwnerId = currentUser?.id` trước khi gọi provider.
  - Khách (guest): truyền `expectedOwnerId = null` để cho phép đăng nhập tài khoản bất kỳ lần đầu.
  - Cả Credential Manager và Intent fallback đều bọc trong cùng một attempt có `requestId`.
  - Giữ nguyên draft PDF (tên file, trang scan) và draft ID-card (mặt trước, mặt sau, watermark, layout) khi cùng owner; sai owner bị từ chối trước khi làm biến đổi trạng thái.
  - Hủy đăng nhập hoặc đóng host không làm mất draft và không cấp VIP trái phép.

---

## 2. Bảng Call Site VIP Trước và Sau S02

| Host / Screen | Call Site Thành Phần | Trước S02 | Sau S02 (Đã sửa) |
|---|---|---|---|
| **PdfViewerActivity** | `performGoogleSignIn()` | `signInWithGoogle(expectedOwnerId = null)` | `signInWithGoogle(expectedOwnerId = currentUser?.id)` |
| **PdfViewerActivity** | `startSignInForVipContinuation` | Không nhận `action`, không truyền owner/epoch | Nhận `action: VipContinuationAction`, truyền `currentGen`, `currentOwner`, `currentEpoch` |
| **PdfViewerActivity** | `handleSignInSuccess` | `onSignInSuccess { showVipUpgradeDialog() }` | `onSignInSuccessWithAction`: UPGRADE $\to$ dialog, RESTORE $\to$ `executeRestorePurchases()` |
| **PdfViewerActivity** | `showVipUpgradeDialog()` | Chỉ truyền `onRequestSignIn` | Truyền cả `onRequestSignIn` và `onRequestSignInForAction` |
| **PdfViewerActivity** | `CreatePdfDialog` (2 vị trí) | Chỉ truyền `onRequestSignIn` | Truyền `onRequestSignInForAction = { action, name -> startSignInForVipContinuation(action, name) }` |
| **CreatePdfDialog** | `binding.layoutWatermarkStatus` | Nút khôi phục trong dialog không chuyển action | Chuyển tiếp `onRequestSignInForAction` đầy đủ từ host vào `VipUpgradeDialog` |
| **IdCardComposeActivity**| `performGoogleSignIn()` | `signInWithGoogle(expectedOwnerId = null)` | `signInWithGoogle(expectedOwnerId = currentUser?.id)` |
| **IdCardComposeActivity**| `startSignInForVipContinuation` | Không nhận `action`, không truyền owner/epoch | Nhận `action: VipContinuationAction`, truyền `currentGen`, `currentOwner`, `currentEpoch` |
| **IdCardComposeActivity**| `handleSignInSuccess` | `onSignInSuccess { showVipUpgradeDialog() }` | `onSignInSuccessWithAction`: UPGRADE $\to$ dialog, RESTORE $\to$ `executeRestorePurchases()` |
| **IdCardComposeActivity**| `showVipUpgradeDialog()` | Chỉ truyền `onRequestSignIn` | Truyền cả `onRequestSignIn` và `onRequestSignInForAction` |

---

## 3. Giải thích về Probe P03

- **Bản chất của P03:** Probe P03 trong `docs/vip-session-reaudit-20261004/VipSessionReauditProbeTest.kt` kiểm tra API `AppAuthManager.createSignInAttemptForTesting()` và `commitSignedInAccount` khi truyền attempt không có `expectedOwnerId` (unbound attempt).
- Khi một host VIP vô tình gọi đăng nhập không kèm `expectedOwnerId`, attempt tạo ra là unbound, khiến `commitSignedInAccount` không kiểm tra identity của tài khoản B so với tài khoản A hiện tại.
- **Khắc phục ở S02:**
  - `PdfViewerActivity` và `IdCardComposeActivity` hiện đều truyền `expectedOwnerId = AppAuthManager.getCurrentUser()?.id` một cách bắt buộc.
  - Khi `expectedOwnerId` có giá trị, `AppAuthManager` kích hoạt bảo vệ reauthentication nghiêm ngặt: nếu người dùng chọn tài khoản B, `commitSignedInAccount` ném `AccountMismatchException`, bảo vệ tài khoản A nguyên vẹn (đã được xác minh bởi probe control `C02_boundReauthRejectsOtherOwner` PASS).
  - Các host test mới được bổ sung trực tiếp kiểm tra:
    - `testViewerContinuation_sameOwnerReauthSucceeds_differentOwnerRejected`
    - `testViewerContinuation_restoreActionRoutesToRestore_notUpgrade`
    - `testIdCardContinuation_sameOwnerReauthSucceeds_differentOwnerRejected`
    - `testIdCardContinuation_restoreActionRoutesToRestore_notUpgrade`

---

## 4. Kết quả kiểm thử (Verification Evidence)

Lệnh thực thi:
```powershell
$env:JAVA_HOME = 'C:\Users\nguye\.jdks\openjdk-21.0.1'
$env:GRADLE_USER_HOME = 'C:\Users\nguye\.gradle'
.\gradlew.bat :app:testDebugUnitTest --tests "*VipViewerLoginContinuationTest*" --tests "*VipIdCardLoginContinuationTest*" --tests "*VipSessionExpiryRegressionTest*" --offline --console=plain
```

### Kết quả: **BUILD SUCCESSFUL** (Tất cả test passes 100%)
- `VipViewerLoginContinuationTest`: PASS (bao gồm các test bảo toàn trang, tên file draft, watermark, RESTORE action routing, same-owner vs different-owner reauth).
- `VipIdCardLoginContinuationTest`: PASS (bao gồm các test bảo toàn ảnh mặt trước/sau, draft layout, RESTORE action routing, same-owner vs different-owner reauth).
- `VipSessionExpiryRegressionTest`: PASS (10/10 tests regression các lỗi F01–F06).

---

## 5. Hợp đồng bàn giao cho Gói S03 (Contract Handover)

1. **Vấn đề cần giải quyết ở S03:** G03 (Nhánh Restore & Navigation):
   - Khi backend Billing trả về HTTP 401 (hoặc `AuthRequired`), cần truyền lý do auth-required / force-reauth có gắn với owner và operation identity qua navigation giữa `HomeFragment`, `MainActivity`, `MoreFragment`.
   - Nếu backend đã báo 401, ứng dụng phải yêu cầu reauth ngay cả khi hạn của token cục bộ (`exp` trong JWT) chưa hết hạn.
   - Giới hạn retry: sau khi reauth chỉ restore tối đa 1 lần cho operation; nếu tiếp tục gặp auth-required thì dừng và hiển thị thông báo lỗi, không redirect lặp vô hạn.
