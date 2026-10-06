# Báo Cáo VIP Vòng 8 — Gói Y10: Chặn SDK/Listener Callbacks Của Manager Đã Dispose (G06)

**Mã gói:** Y10  
**Lỗi giải quyết:** G06 (A807, A808, A811)  
**Thời gian thực hiện:** 01/10/2026 16:00 – 16:15  
**Môi trường:** `E:\DU AN AI\T-Scanner` | Windows pwsh | Gradle offline  

---

## 1. Mục tiêu & Scope
- **Mục tiêu:** Đảm bảo khi `BillingManager` bị destroy hoặc coroutine scope bị cancel, mọi asynchronous callbacks từ Google Play Billing SDK (`PurchasesUpdatedListener`), background `processPurchase` worker, hoặc pending runnables trong Android Main Looper đều bị huỷ hoặc dập tắt triệt để, không trigger listener callbacks muộn hoặc gây crash/memory leak.
- **Phạm vi file:**
  - `app/src/main/java/com/scanner/app/data/billing/BillingManager.kt`
- **Không thay đổi:** Không phá vỡ luồng mua hàng thông thường, không can thiệp lifecycle ngoài BillingManager.

---

## 2. Hiện trạng trước khi sửa & Regressions (A807, A808, A811)
- Trước khi sửa:
  - `BillingManager.destroy()` không gọi `purchaseCallbacks.clear()`, dẫn tới callback listeners vẫn còn lưu lại trong danh sách đăng ký.
  - Khi Play SDK gọi `onPurchasesUpdated` sau khi manager đã destroy hoặc scope đã cancel, manager vẫn tiếp tục xử lý danh sách purchase và dispatch tới listeners.
  - `processPurchase()` và `notifyCallbacks()` không kiểm tra điều kiện `isDestroyed || !scope.isActive` trước khi đưa runnable vào `mainHandler.post` cũng như bên trong chính Runnable khi thực thi trên Main Thread.
  - Hậu quả: Nếu looper trễ hơn lệnh destroy, listener vẫn nhận callback mua hàng/lỗi của instance đã chết (vi phạm invariant G06).

---

## 3. Chi tiết các thay đổi trong `BillingManager.kt`
1. **Làm sạch callbacks khi destroy:**
   - Trong `destroy()`: Gọi `purchaseCallbacks.clear()`, giải phóng các tham chiếu callback ngay lập tức.
2. **Kiểm tra trạng thái huỷ tại cửa ngõ `onPurchasesUpdated`:**
   - Thêm guard: `if (isDestroyed || !scope.isActive) return` ngay đầu hàm `onPurchasesUpdated(billingResult, purchases)`.
3. **Kiểm tra trạng thái huỷ trong `processPurchase`:**
   - Thêm guard: `if (isDestroyed || !scope.isActive) return` ngay đầu hàm `processPurchase(purchase)`.
4. **Kiểm tra trạng thái huỷ hai lớp trong `notifyCallbacks`:**
   - Lớp 1 (trước khi post): `if (isDestroyed || !scope.isActive) return`.
   - Lớp 2 (bên trong Runnable trên Main Thread): `if (isDestroyed || !scope.isActive) return` trước khi lặp qua `purchaseCallbacks`.

---

## 4. Kết quả kiểm thử
Lệnh chạy kiểm thử:
```pwsh
./gradlew testDebugUnitTest --tests "com.scanner.app.data.billing.VipRound8RegressionTest" --offline
```

Kết quả:
- **17 / 17 tests PASSED (100%)**, 0 failures, 0 errors.
- Trong đó các tests cho G06:
  - `a807_billingManagerDestroyClearsCallbacksAndSuppressesAsyncCallbacks`: **PASS**
  - `a808_coroutineScopeCancellationSuppressesLatePurchasesUpdatedCallbacks`: **PASS**
  - `a811_disposedBillingManagerDoesNotProcessOrEmitLatePlayPurchases`: **PASS**

---

## 5. Đánh giá tiêu chí chấp nhận (Acceptance)
- [x] Không còn invocation nào được gửi tới `purchaseCallbacks` sau khi `destroy()` được gọi.
- [x] Cancellation của `scope` ngăn chặn mọi late `onPurchasesUpdated` và `processPurchase`.
- [x] Không có rò rỉ bộ nhớ từ callback listeners.
- [x] Toàn bộ test suite regression Android Round 8 đều đạt kết quả xanh.

Tự động chuyển tiếp sang **Y11 (G10: Xử lý voided full refund lifetime bằng RTDN)**.
