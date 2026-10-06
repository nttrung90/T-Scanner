# Báo cáo VIP Round 6 — W05: Hợp nhất toàn bộ account receipts cho mọi nhánh Play (R01)

## 1. Mục tiêu và phạm vi
- Khắc phục khiếm khuyết R01 (Probes A01, A02).
- Loại bỏ logic bỏ qua remote restore khi thiết bị có giao dịch pending hoặc khi danh mục thiết bị trả về các gói đã mua (`validPurchasedItems.isNotEmpty()`).
- Đảm bảo reconciliation luôn đồng bộ và hợp nhất hai nguồn dữ liệu:
  1. Các giao dịch Google Play hiện có trên thiết bị (`validPurchasedItems`), được xác thực và áp dụng vào store.
  2. Toàn bộ các entitlement và biên lai đã biết của tài khoản ứng dụng trên máy chủ (`verifier.restorePurchases`), đảm bảo các gói như lifetime hoặc quyền mua từ thiết bị khác được bảo toàn hoặc thu hồi đúng theo thẩm quyền server.

## 2. Các file thay đổi
- `app/src/main/java/com/tscanner/app/utils/billing/BillingReconciliation.kt`
  - Bỏ kiểm tra ngắt sớm `if (hasPendingItems) return` khi `validPurchasedItems` rỗng. Cho phép gọi `executeRemoteRestore` để làm mới các quyền đã biết trên tài khoản ngay cả khi thiết bị có giao dịch local đang PENDING (giải quyết A01).
  - Khi `validPurchasedItems` không rỗng, sau khi hoàn tất xử lý và xác thực từng item trên thiết bị, tiếp tục gọi `executeRemoteRestore` để truy vấn backend cập nhật snapshot quyền của tài khoản (giải quyết A02).
  - Sử dụng `store.applySnapshotTyped` để merge tự nhiên snapshot server vào store theo monotonic versioning, bảo đảm không bị ghi đè hay mất mát quyền hợp lệ.
  - Thay thế `AppAuthManager.applyEntitlementSnapshot` bằng `AppAuthManager.projectSnapshotToProfile` với kiểm tra kết quả lưu profile, loại bỏ hoàn toàn double-commit.

## 3. Kết quả kiểm tra
- **Trước khi sửa:**
  - `A01PendingPlayPurchaseMustNotSuppressKnownAccountRefresh` FAILED (0 lần restore backend).
  - `A02NonemptyPlayListMustStillRefreshOtherServerReceipts` FAILED (0 lần restore backend, quyền lifetime đã bị hoàn tiền trên server vẫn duy trì VIP trong cache local).
- **Sau khi sửa:**
  - Chạy `gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.VipRound6RegressionTest.A01* --tests com.tscanner.app.VipRound6RegressionTest.A02*`:
    - **BUILD SUCCESSFUL, 2 passed**.
  - Chạy `VipRound5RegressionTest`:
    - **BUILD SUCCESSFUL, 9/9 passed**.
  - Chạy toàn bộ `VipRound6RegressionTest`:
    - **8 passed / 1 failed** (chỉ còn A06 cho W08).

## 4. Contract và cam kết
- Không cấp quyền trả phí cho giao dịch PENDING.
- Không tự động thu hồi quyền của tài khoản khi danh mục thiết bị rỗng hoặc lỗi mạng.
- Luôn tôn trọng tính thẩm quyền (authoritative) của server đối với các entitlement thuộc về tài khoản người dùng (`ownerAppUserId`).

## 5. Bước tiếp theo
- Chuyển sang hoàn tất thủ tục checkpoint cho **W06** và **W07** (đã được bao hàm và verify trong quá trình chuẩn hóa architecture của `executeRemoteRestore`), sau đó tiến hành **W08** (R05 - Probe A06).
