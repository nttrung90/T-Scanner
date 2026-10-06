# Báo cáo Checkpoint VIP Round 7 — Gói X03

## 1. Mục tiêu và phạm vi gói X03
- **Mục tiêu:** Khắc phục lỗi F03 — Khi nhận thông báo RTDN cho token pending bị hủy chưa từng bind vào store DB (`SUBSCRIPTION_STATE_PENDING_PURCHASE_CANCELED`), hệ thống trả về `TOKEN_UNKNOWN` ngay lập tức mà không truy vấn Google Play để kiểm tra metadata và token liên kết (`linkedPurchaseToken`), khiến receipt cũ vẫn giữ nguyên trạng thái `ACTIVE` lỗi thời.
- **Phạm vi file:**
  - `backend/billing-verifier/src/rtdnHandler.ts`
  - `backend/billing-verifier/src/verifier.ts`
  - `backend/billing-verifier/test/round7-regression.test.ts`
  - `PROGRESS_VIP_R7_AUTORUN.md`
  - `REPORT_VIP_R7_X03.md`

## 2. Thay đổi chi tiết trong code sản phẩm
- **Trong `verifier.ts`:**
  - Chuyển `resolveLinkedSubscriptionToken` thành phương thức `public` để `RtdnHandler` có thể tái sử dụng logic an toàn của X01 (CAS + explicit absent sentinel) và X02 (chuẩn xác SKU + typed resolution).
- **Trong `rtdnHandler.ts`:**
  - Bổ sung `verifier: BillingVerifierService` vào constructor của `RtdnHandler`.
  - Khi `existingRecord` chưa tồn tại trong store: Không return `TOKEN_UNKNOWN` ngay; thực hiện truy vấn Google Play Developer API authoritatively (`getSubscription`).
  - Nếu Google Play trả về `linkedPurchaseToken`:
    - Tìm bản ghi của linked token trong `store`.
    - Nếu linked token thuộc về một người dùng hợp lệ, xác thực mã băm tài khoản `obfuscatedExternalAccountId`.
    - Làm mới trạng thái của linked token thông qua `verifier.resolveLinkedSubscriptionToken`.
    - Nếu token mới có trạng thái `SUBSCRIPTION_STATE_PENDING_PURCHASE_CANCELED`, lưu tombstone `REVOKED` cho token mới.
    - Cập nhật `lastProcessedEventTimes` và trả về `status: 'PROCESSED'`.
  - Trên luồng token đã biết (`existingRecord` tồn tại): Bổ sung kiểm tra `playResult.linkedPurchaseToken` để làm mới token liên kết nếu có.
  - Lỗi truy vấn tạm thời khi giải quyết linked token sẽ trả về `status: 'ERROR'` để Pub/Sub có thể retry, không coi là `PROCESSED`.

## 3. Kết quả kiểm thử trước và sau sửa

### 3.1 Probes mục tiêu
- **B704 (`RTDN after a real pending verification must refresh the canceled purchases linked old receipt`):**
  - Trước: ✖ FAIL (`AssertionError: Real pending token remained unbound; RTDN returned TOKEN_UNKNOWN; canceledNewQueries=0, oldRefreshQueries=0; linked old ACTIVE remained stale`)
  - Sau: ✔ **PASS** (2.31ms)
- **B701, B702, B703, B709, B710, B711:**
  - Đều tiếp tục ✔ **PASS**.

### 3.2 Bộ test Backend tổng thể
- **Lệnh chạy:** `node --experimental-strip-types --test backend/billing-verifier/test/*.test.ts`
- **Kết quả:** 108 tests (106 PASS, 2 FAIL — 2 test còn lại là B705, B706 thuộc phạm vi X04).
- Toàn bộ 99 tests baseline giữ vững không hồi quy.

## 4. Hợp đồng và Giới hạn
- RTDN không tự gán unknown token cho bất kỳ user nào nếu không có linked receipt chứng minh quyền sở hữu trong store.
- Nếu không tìm thấy linked token và không có user sở hữu trong store, RTDN giữ nguyên trạng thái `TOKEN_UNKNOWN`.
- Sự kiện out-of-order và trùng lặp vẫn được bảo vệ bởi deduplication map và version CAS.

## 5. Chuyển giao gói tiếp theo
- **Gói kế tiếp:** **X04 — Backend restore contract đầy đủ theo từng token (F06 backend)**.
- **Mục tiêu X04:** Giải quyết B705 và B706 trong `backend/billing-verifier/src/verifier.ts` và `src/types.ts`: bảo toàn trạng thái `PENDING` theo từng token trong `results[]` (thay vì map sang `REJECTED`), làm rõ hợp đồng trạng thái tổng thể khi tất cả candidate bị từ chối/không có active.
