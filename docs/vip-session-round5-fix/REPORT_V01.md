# Báo Cáo Gói V01 — Quyền Nhận Recovery Và Vòng Đời Reservation

Ngày thực hiện: 06/10/2026. Workspace: `E:\DU AN AI\T-Scanner`.

---

## 1. Mục Tiêu & Phạm Vi Gói V01

- Khắc phục triệt để defect R01 tại tầng admission / reservation:
  1. Loại bỏ việc tiêu sớm lượt recovery trong `BillingManager` khi listener chỉ mới nhận event mà UI chưa sẵn sàng (`Defer`) hoặc provider đang bận (`busy / refused`).
  2. Bổ sung máy trạng thái atomic reservation trong `VipPurchaseAuthConsumer` đảm bảo tính độc quyền: chỉ đúng 1 consumer nhận được quyền phục hồi (`RequestReauth`) cho mỗi `recoveryKey` (`$opId:$owner:$sessionGen:$epoch:$purchaseToken`).
  3. Cung cấp API bắt tay hai chiều trên `PurchaseAuthDecision.RequestReauth`: `confirmStarted()` (cam kết đã nhận) và `release()` (hoàn lại lượt khi provider bận/từ chối).
  4. Đảm bảo stale callback hoàn tất từ attempt cũ không thể xóa hoặc làm hỏng reservation mới (bảo vệ bằng `reservationToken`).
  5. Đảm bảo thao tác người dùng mới (`BillingOperationContext` với `operationId` mới) được cấp lượt recovery mới dù cùng một receipt token.

---

## 2. Chi Tiết Các File Can Thiệp

### 2.1. `app/src/main/java/com/tscanner/app/utils/BillingManager.kt`
- Bổ sung `onReleaseAttempt: (() -> Unit)? = null` và thuộc tính `recoveryKey: String` kèm helper `releaseAttempt()` vào data class `PurchaseAuthRequiredEvent`.
- Trong `processPurchase`: truyền `onReleaseAttempt = { attemptedRecoveryKeys.remove(recoveryKey) }` khi tạo `PurchaseAuthRequiredEvent`.
- Thêm các phương thức hỗ trợ kiểm thử và điều phối: `releaseRecoveryAttempt(recoveryKey: String)` và `isRecoveryAttempted(recoveryKey: String)`.

### 2.2. `app/src/main/java/com/tscanner/app/ui/dialogs/VipPurchaseAuthConsumer.kt`
- Mở rộng `PurchaseAuthDecision.RequestReauth` với:
  - `operationContext: BillingOperationContext?`
  - `reservationToken: String`
  - `onStarted: () -> Unit` / `confirmStarted()`
  - `onReleased: () -> Unit` / `release()`
- Xây dựng state machine `VipPurchaseAuthConsumer`:
  - `ReservationState`: `RESERVED`, `STARTED`, `COMPLETED`.
  - Quản lý `ConcurrentHashMap<String, ReservationRecord>` với `putIfAbsent` để bảo đảm nguyên tử (atomic claim).
  - Khi UI inactive (`!isUiActive`): giải phóng `event.releaseAttempt()`, trả `PurchaseAuthDecision.Defer` mà không tiêu lượt.
  - Khi session mismatch (owner, generation, epoch, stale context): giải phóng `event.releaseAttempt()`, trả `PurchaseAuthDecision.Ignore`.
  - Khi event đến trong lúc đã `STARTED` hoặc `COMPLETED`: trả `PurchaseAuthDecision.Stop`.
  - Khi event đến trong lúc đang `RESERVED`: trả `PurchaseAuthDecision.Ignore`.
  - Khi `confirmStarted()`: chuyển trạng thái sang `STARTED` chỉ khi `token` khớp.
  - Khi `release()`: gỡ bỏ reservation và gọi `event.releaseAttempt()` chỉ khi `token` khớp (bảo vệ chống stale callback).
  - Tự động dọn dẹp các bản ghi cũ quá 10 phút khi số lượng vượt quá 100 entries.

### 2.3. Cập nhật Probe Adapter & Unit Tests
- `docs/vip-session-round5-fix/VipSessionRound5ProbeTest.kt`: cập nhật adapter trong `P02` để biên provider giả lập phản hồi kết quả thực tế qua `decision.confirmStarted()` khi khả dụng và `decision.release()` khi bận, giữ nguyên 100% kịch bản và assertion.
- `app/src/test/java/com/tscanner/app/VipPurchaseAuthConsumerTest.kt`: tạo mới 8 unit test chuyên sâu kiểm chứng toàn bộ hợp đồng reservation:
  1. `inactiveUi_defersAndReleasesAttempt`
  2. `ownerMismatch_ignoresAndReleasesAttempt`
  3. `sessionGenMismatch_ignoresAndReleasesAttempt`
  4. `atomicClaim_secondConsumerGetsIgnored`
  5. `releaseReservation_allowsNextConsumerToClaim`
  6. `confirmStarted_blocksSubsequentAttemptsForSameOperation`
  7. `staleToken_cannotCorruptNewReservation`
  8. `newOperation_getsFreshReservationEvenForSameReceipt`

---

## 3. Bằng Chứng Kiểm Thử Thực Tế

### 3.1. Kết Quả Chạy Probe (25 Probes Độc Lập)
Lệnh: `powershell -NoProfile -File docs/vip-session-round5-fix/run-probes.ps1`  
Mã thoát: `GRADLE_EXIT=0` — **BUILD SUCCESSFUL**.

| Suite | Tổng số | PASS | FAIL | Trạng thái |
|---|---|---|---|---|
| **Round 5 Probes** (`VipSessionRound5ProbeTest`) | 4 | 4 | 0 | **ALL PASS** (P01, P02, P03, C01) |
| **Round 4 Regressions** (`VipSessionRound4ProbeTest`) | 7 | 7 | 0 | **ALL PASS** (P01–P05, C01, C02) |
| **Round 3 Regressions** (`VipSessionRound3ProbeTest`) | 7 | 7 | 0 | **ALL PASS** (P01–P04, C01–C03) |
| **Round 2 Regressions** (`VipSessionReauditProbeTest`) | 7 | 7 | 0 | **ALL PASS** (P01, P02, P04–P06, C01, C02) |
| **Tổng cộng** | **25** | **25** | **0** | **100% PASS** |

### 3.2. Kết Quả Chạy Suite Mới (`VipPurchaseAuthConsumerTest`)
Lệnh: `.\gradlew.bat :app:testDebugUnitTest --tests "com.tscanner.app.VipPurchaseAuthConsumerTest" --offline --console=plain`  
Kết quả: **8 tests completed, 8 passed, 0 failed, 0 errors**.

---

## 4. Kết Luận Gói V01

- 3 probe đỏ từ baseline V00 (P01, P02, P03) đã chuyển thành **GREEN (PASS)**.
- Toàn bộ 21 regression cũ từ các vòng 2, 3, 4 tiếp tục được bảo toàn 100%.
- Cơ chế atomic reservation token và hoàn trả budget đã vận hành chính xác.
- Sẵn sàng chuyển tiếp sang gói **V02** để nối nhận/từ chối và continuation vào các host thực tế.
