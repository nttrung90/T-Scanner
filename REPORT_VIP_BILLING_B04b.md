# Báo Cáo Nghiệm Thu Gói B04b — Vòng Đời Subscription, Acknowledge/Retry và Revoke Phía Server (F03/F06)

**Thời gian:** 26/09/2026  
**Dự án:** T-Scanner (`backend/billing-verifier/`)  
**Mục tiêu gói B04b:** Thiết lập cơ chế acknowledge bền vững phía server theo nguyên tắc persist-before-ack, xử lý toàn bộ các sự kiện Real-Time Developer Notifications (RTDN) từ Google Cloud Pub/Sub, chống thu hồi quyền mù quáng khi mất mạng (F03), và bảo toàn các quyền đồng thời (multi-entitlement) khi một quyền bị hủy/thu hồi (F06).

---

## 1. Danh Sách File Tạo Mới & Sửa Đổi (Tuân thủ nghiêm ngặt Whitelist B04b)

*Lưu ý:* Đúng theo quy tắc của gói B04b, **chỉ thao tác trên module `backend/billing-verifier/`**, tuyệt đối không sửa đổi mã nguồn Android (`:app`) trong lượt này.

| File | Loại thay đổi | Chức năng kỹ thuật |
|---|---|---|
| `backend/billing-verifier/src/ackService.ts` | Tạo mới | Triển khai mô hình **Persist-before-ack**: Lưu giao dịch vào hàng đợi retry trước khi gửi lệnh acknowledge sang Google Play; xử lý timeout và retry định kỳ. |
| `backend/billing-verifier/src/rtdnHandler.ts` | Tạo mới | Xử lý Google Cloud Pub/Sub webhook: Giải mã Base64, lọc thông báo cũ/đến sai thứ tự (`SKIPPED_STALE`), xử lý các loại sự kiện vòng đời (`RENEWED`, `CANCELED`, `IN_GRACE_PERIOD`, `ON_HOLD`, `REVOKED`, `EXPIRED`). |
| `backend/billing-verifier/src/googlePlayClient.ts` | Sửa đổi | Bổ sung phương thức `acknowledgeSubscription` và `acknowledgeInAppProduct` vào interface `GooglePlayBillingApi` và các lớp implement. |
| `backend/billing-verifier/src/index.ts` | Sửa đổi | Định tuyến thêm endpoint `POST /api/v1/billing/rtdn` (Pub/Sub push) và `POST /api/v1/billing/acknowledge`. |
| `backend/billing-verifier/test/rtdn_and_lifecycle.test.ts` | Tạo mới | Bộ 6 unit tests chuyên sâu kiểm chứng cơ chế RTDN, chống thu hồi mù, và hàng đợi retry acknowledge. |
| `REPORT_VIP_BILLING_B04b.md` | Tạo mới | Báo cáo nghiệm thu và bàn giao gói B04b. |

---

## 2. Các Ràng Buộc & Invariants Đã Được Kiểm Chứng

1. **Gia Hạn Không Bao Giờ Cộng Dồn Ngày (F02 Fix):**
   - Khi nhận sự kiện `SUBSCRIPTION_RENEWED` (2), server gọi Google Play API để lấy mốc thời gian hết hạn mới tuyệt đối (`expiryTimeMillis`). Tuyệt đối không cộng thêm 30 ngày hay 365 ngày theo số lần callback.
2. **Hủy Tự Gia Hạn Vẫn Được Dùng Đến Hết Kỳ (F03 Fix):**
   - Khi nhận sự kiện `SUBSCRIPTION_CANCELED` (3), trạng thái chuyển sang `CANCELED_ACTIVE`, cờ `autoRenewing = false`. Người dùng vẫn duy trì toàn bộ quyền VIP cho đến đúng thời điểm `expiryTimeMillis`.
3. **Chống Thu Hồi Mù (Blind Revocation Guard):**
   - Khi Google Play Developer API gặp lỗi mạng tạm thời (timeout / 500 / 503) trong quá trình xử lý RTDN, hệ thống **không bao giờ vội vàng xóa hoặc revoke quyền** của người dùng, mà giữ nguyên trạng thái cache an toàn và log cảnh báo.
4. **Bảo Toàn Quyền Đồng Thời (Multi-Entitlement Isolation - F06):**
   - Khi một gói Subscription bị thu hồi (`REVOKED`), nếu người dùng có gói Lifetime (`tscanner_vip_lifetime`), quyền Lifetime vẫn tiếp tục có hiệu lực (`VERIFIED_ACTIVE`), đảm bảo tài khoản không bị mất VIP oan uổng.
5. **Chống Notification Trùng & Đảo Thứ Tự (Out-of-Order Guard):**
   - Sử dụng `eventTimeMillis`: Nếu một notification đến muộn có timestamp nhỏ hơn hoặc bằng sự kiện đã xử lý gần nhất, server lập tức bỏ qua (`SKIPPED_STALE`), ngăn trạng thái cũ ghi đè trạng thái mới.
6. **Mô Hình Persist-Before-Ack & Retry Queue:**
   - Giao dịch được ghi nhận vào `pendingAckQueue` trước khi gọi Play Store. Nếu Play Store bị lỗi hoặc timeout, token vẫn nằm an toàn trong hàng đợi và sẽ được hàm `drainPendingQueue` xử lý retry khi dịch vụ phục hồi.
   - Gửi acknowledge lặp lại trả về `ALREADY_ACKNOWLEDGED`, không gây lỗi và không tạo request thừa sang Google.

---

## 3. Bằng Chứng Xác Minh & Nghiệm Thu (Evidence)

Thư mục lưu trữ: `build/vip-billing-b04b/`

### 3.1. Kết Quả Kiểm Thử Toàn Diện Backend
Lệnh chạy:
```powershell
cd backend/billing-verifier
node --experimental-strip-types --test --test-reporter=spec test/*.test.ts
```
Kết quả: **21/21 PASS (0 failures, thời gian chạy: 193ms)**

**Chi tiết các bài test gói B04b:**
- `✔ B04b RTDN: Renewal updates expiration without accumulating relative days` — PASS
- `✔ B04b RTDN: Cancellation before term expiry transitions to CANCELED_ACTIVE` — PASS
- `✔ B04b RTDN: Out-of-order or duplicate notification is safely skipped` — PASS
- `✔ B04b RTDN: Blind revocation guard preserves user entitlement on Play API error` — PASS
- `✔ B04b Multi-Entitlement: Revoking subscription does not revoke user lifetime entitlement` — PASS
- `✔ B04b Acknowledge: Persist-before-ack queues token on external failure and drains on retry` — PASS

**Chi tiết các bài test gói B04a (Regression Check):**
- 15/15 tests B04a tiếp tục **PASS (100%)**.

File log đầy đủ lưu tại: `build/vip-billing-b04b/backend_b04b_test_results.txt`.

### 3.2. Kiểm Tra Không Sửa Đổi Android Client
- Đã xác minh `git status --porcelain app/`: xác nhận toàn bộ mã nguồn Android `:app` được giữ nguyên vẹn.

---

## 4. Handoff Sang Gói B05

- **Trạng thái:** Backend Verifier đã hoàn tất cả 2 phần B04a (verify/restore/ownership) và B04b (lifecycle/RTDN/ack queue).
- **Tiếp theo theo kế hoạch (B05):**
  - **Writer entitlement tuyệt đối và migration local (F02/F03)** trên Android client.
  - Sửa `utils/AppAuthManager.kt` (chỉ API/persistence VIP, không sửa state machine login/logout).
  - Tạo mới `utils/billing/BillingEntitlementStore.kt`.
  - Viết test `BillingEntitlementStoreTest.kt`.
  - Thay thế các setter cộng dồn ngày tương đối (`+30 ngày`, `+10 năm`) bằng API apply snapshot xác thực với thời hạn tuyệt đối.
