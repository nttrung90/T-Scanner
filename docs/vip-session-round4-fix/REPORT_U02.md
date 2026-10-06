# Báo Cáo Kiểm Thử & Bàn Giao Gói U02 (K02: Thẩm Tra Event Tại Thời Điểm Tiêu Thụ)

**Ngày thực hiện:** 05/10/2026  
**Workspace:** `E:\DU AN AI\T-Scanner`  
**Gói công việc:** U02 (Theo `PLAN_FIX_VIP_SESSION_ROUND4_GEMINI_2026-10-05.md`)

---

## 1. Mục Tiêu & Phạm Vi (Scope)

- **Mục tiêu:**
  - Khắc phục khiếm khuyết **K02**: Sự kiện xác thực cũ có thể đến consumer sau khi phiên làm việc đã thay đổi (chuyển tài khoản, đăng xuất, đổi session generation hoặc process epoch).
  - Bảo đảm event origin là immutable và được thẩm định nghiêm ngặt tại:
    1. Đầu Runnable trên Main Thread ngay trước khi gửi tới bất kỳ listener nào.
    2. Trước mỗi lần gọi từng listener trong vòng lặp phân phối (để ngăn chặn tình huống listener đầu tiên làm đổi session nhưng listener tiếp theo vẫn nhận sự kiện cũ của phiên trước).
    3. Ngay tại `VipPurchaseAuthConsumer.evaluate` trước khi Dialog kích hoạt bất kỳ hiệu ứng UI, Toast, Dismiss hay callback re-auth/restore nào.

---

## 2. Thay Đổi Mã Nguồn Sản Xuất

1. **`app/src/main/java/com/tscanner/app/utils/BillingManager.kt`:**
   - Trong `notifyPurchaseAuthRequired`:
     - Kiểm tra trạng thái phiên (`runOwner`, `runGen`, `runEpoch`) ngay đầu `action = Runnable`:
       Nếu có sự sai lệch với `event.targetOwnerId`, `event.sessionGeneration`, `event.processEpoch` hoặc `opContext.isStale`, hủy bỏ thực thi ngay (`return@Runnable`).
     - Trong vòng lặp `for (listener in authRequiredListeners)`:
       Trước khi `listener.invoke(event)`, kiểm tra lại `nowOwner`, `nowGen`, `nowEpoch`. Nếu phiên bị thay đổi (ví dụ listener trước kích hoạt đăng xuất hoặc chuyển user), lập tức dừng phân phối (`break`), bảo đảm không có người nhận sau bị nhận nhầm sự kiện của user cũ.
     - Áp dụng cơ chế tương tự cho danh sách `purchaseCallbacks`.
2. **`app/src/main/java/com/tscanner/app/ui/dialogs/VipPurchaseAuthConsumer.kt`:**
   - Được thiết kế và tích hợp từ U00/U01: kiểm tra toàn diện `targetOwnerId`, `sessionGeneration`, `processEpoch` và `opContext.isStale`.
   - Nếu phát hiện không khớp phiên: trả về `PurchaseAuthDecision.Ignore` mà không sinh ra Toast, không dismiss dialog sai lệch và không kích hoạt reauth callback.

---

## 3. Bằng Chứng Kiểm Thử (Evidence)

- **Lệnh thực thi:**
  ```powershell
  $env:JAVA_HOME = 'C:\Users\nguye\.jdks\openjdk-21.0.1'; $env:GRADLE_USER_HOME = 'C:\Users\nguye\.gradle'
  .\gradlew.bat :app:testDebugUnitTest --offline -I docs/vip-session-round4-reaudit-20261005/audit.init.gradle --tests 'com.tscanner.app.VipSessionRound4ProbeTest.P04*' --console=plain
  ```
- **Kết quả: PASSED (BUILD SUCCESSFUL)**:
  - `P04_sessionChangeDuringDispatchMustSuppressRemainingOldEvents`: **PASSED** (Khi listener 1 đổi session từ A sang B, listener 2 không nhận sự kiện cũ của A, `staleDelivered == 0`).
- **Chạy tổng hợp P01 – P04 & C01:** 5/5 **PASSED** (0 failures, 0 errors).

---

## 4. Nghiệm thu & Chuyển giao
- Tiêu chí gói U02: **ĐẠT (PASS)**.
- Tự động chuyển sang gói **U03** để giải quyết **K03** (thẩm tra session generation cho guest navigation request).
