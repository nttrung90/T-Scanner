# Báo Cáo Nghiệm Thu Gói B04a — Verifier Server và Ràng Buộc Receipt (F04/F05)

**Thời gian:** 26/09/2026  
**Dự án:** T-Scanner (`backend/billing-verifier/`)  
**Mục tiêu gói B04a:** Xây dựng module Verifier Server độc lập, giải quyết triệt để F04 (Thiếu backend verifier) và F05 (Không kiểm soát quyền sở hữu receipt), kiểm tra chữ ký/thời hạn từ Google Play Developer API, thực thi ràng buộc một receipt chỉ thuộc một tài khoản app, và bảo đảm tính lũy đẳng (idempotency) khi xử lý giao dịch.

---

## 1. Danh Sách File Tạo Mới (Tuân thủ nghiêm ngặt Whitelist B04a)

*Lưu ý:* Đúng theo quy tắc của gói B04a, **chỉ thao tác trên thư mục mới `backend/billing-verifier/`**, tuyệt đối không sửa đổi mã nguồn Android (`:app`) trong lượt này.

| File | Chức năng kỹ thuật |
|---|---|
| `backend/billing-verifier/package.json` | Cấu hình module Node.js/TypeScript (ESM, zero-bloat, tương thích Node 20+ và 24). |
| `backend/billing-verifier/src/types.ts` | Khai báo toàn bộ Types và Contract data models khớp 100% với `docs/billing/ENTITLEMENT_CONTRACT.md`. |
| `backend/billing-verifier/src/store.ts` | Lớp `EntitlementStore` lưu trữ giao dịch và ràng buộc sở hữu `purchaseToken -> ownerAppUserId`, xử lý tranh chấp sở hữu (ownership conflict) và versioning lũy đẳng. |
| `backend/billing-verifier/src/googlePlayClient.ts` | Interface `GooglePlayBillingApi`, `ProductionGooglePlayBillingApi` (chặn bằng gate `MissingCredentialsError` khi thiếu file Service Account thật), và `MockGooglePlayBillingApi` phục vụ unit test. |
| `backend/billing-verifier/src/verifier.ts` | `BillingVerifierService`: Kiểm tra allowlist catalog/package, truy vấn Play Developer API, hash `obfuscatedAccountId` bằng SHA-256, che giấu token nhạy cảm trong log (`maskToken`), và ràng buộc quyền sở hữu. |
| `backend/billing-verifier/src/index.ts` | HTTP REST Server cung cấp endpoint `POST /api/v1/billing/verify`, `POST /api/v1/billing/restore`, và `GET /health`. |
| `backend/billing-verifier/test/verifier.test.ts` | Bộ 15 unit tests hồi quy bao phủ toàn bộ các kịch bản ngoại lệ, bảo mật, và concurrency. |
| `REPORT_VIP_BILLING_B04a.md` | Báo cáo nghiệm thu và bàn giao gói B04a. |

---

## 2. Các Ràng Buộc & Invariants Đã Được Kiểm Chứng

1. **Ràng Buộc Sở Hữu Một Chiều (F05 Ownership Binding):**
   - Một receipt/token khi đã gắn với tài khoản User A thì User B không thể đòi quyền (trả về mã lỗi `OWNERSHIP_CONFLICT` và HTTP 409).
   - Kiểm tra mã băm bảo mật: Đối chiếu `obfuscatedExternalAccountId` do Google Play ghi nhận với mã băm `sha256(ownerAppUserId)`. Nếu phát hiện bất đồng bộ (khác tài khoản), giao dịch bị từ chối ngay lập tức.
2. **Nguồn Chân Lý Về Thời Hạn (F04 Verification):**
   - Hạn dùng `expiryTimeMillis` lấy trực tiếp từ phản hồi của Google Play, không tự sinh hoặc cộng dồn.
   - Gói Lifetime trả về `expiryTimeMillis = null` và trạng thái `VERIFIED_ACTIVE`.
3. **An Toàn Concurrency & Idempotency:**
   - 5 yêu cầu xác thực đồng thời (concurrent replay) cho cùng một giao dịch trả về kết quả đồng nhất, không phát sinh race condition và không làm lệch phiên bản snapshot.
4. **Phân Loại Trạng Thái Chính Xác:**
   - Giao dịch hết hạn -> `PURCHASE_EXPIRED`.
   - Giao dịch bị hoàn tiền / hủy -> `PURCHASE_REVOKED`.
   - Giao dịch đang chờ thanh toán -> `PENDING` (chưa cấp VIP).
   - Lỗi mạng / Google Play 503/429 -> `TRANSIENT_ERROR` (không xóa cache của người dùng, không cấp VIP vô hạn).
5. **Bảo Mật Thông Tin & An Toàn Log:**
   - `purchaseToken` được che giấu (`tok_12...7890`) trước khi ghi log; không bao giờ in nguyên văn token đầy đủ ra console/file log.
6. **Cổng Kiểm Tra Thực Tế (Integration Gate Status):**
   - Khi không có biến môi trường `GOOGLE_APPLICATION_CREDENTIALS`, lớp production ném ra ngoại lệ `MissingCredentialsError`.
   - Unit tests: **PASS (100%)**.
   - Live Play Integration: **NOT RUN** (giữ đúng cổng kỹ thuật, không trả `VERIFIED` giả).

---

## 3. Bằng Chứng Xác Minh & Nghiệm Thu (Evidence)

Thư mục lưu trữ: `build/vip-billing-b04a/`

### 3.1. Kết Quả Kiểm Thử Backend
Lệnh chạy:
```powershell
cd backend/billing-verifier
node --experimental-strip-types --test --test-reporter=spec test/verifier.test.ts
```
Kết quả: **15/15 PASS (0 failures, thời gian chạy: 163ms)**

```text
✔ B04a Verifier: Disallowed product ID is rejected (2.2653ms)
✔ B04a Verifier: Package name mismatch is rejected (0.3512ms)
✔ B04a Verifier: Invalid token not found in Google Play is rejected (0.4943ms)
✔ B04a Verifier: Valid subscription returns VERIFIED_ACTIVE with exact Play expiry (1.4849ms)
✔ B04a Verifier: Valid Lifetime in-app product returns null expiryTimeMillis (0.6058ms)
✔ B04a Verifier: Ownership conflict rejects User B claiming User A token (0.7211ms)
✔ B04a Verifier: ObfuscatedAccountId mismatch from Play is rejected (0.589ms)
✔ B04a Verifier: Concurrent replay is idempotent without race condition (0.8587ms)
✔ B04a Verifier: Expired subscription returns PURCHASE_EXPIRED (0.6039ms)
✔ B04a Verifier: Revoked/refunded in-app purchase returns PURCHASE_REVOKED (0.5278ms)
✔ B04a Verifier: Pending payment returns status PENDING (0.396ms)
✔ B04a Verifier: Google Play transient error returns TRANSIENT_ERROR (0.4452ms)
✔ B04a Verifier: Sensitive token is masked in logs (0.5228ms)
✔ B04a Verifier: Restore aggregates active entitlements for owner (0.846ms)
✔ B04a Verifier Gate: Production client without credentials throws MissingCredentialsError (0.7634ms)
ℹ tests 15
ℹ suites 0
ℹ pass 15
ℹ fail 0
```
File log đầy đủ lưu tại: `build/vip-billing-b04a/backend_test_results.txt`.

### 3.2. Kiểm Tra Không Sửa Đổi Android Client
- Kiểm tra `git status --porcelain app/`: xác nhận không có bất kỳ dòng mã nào trong `:app` bị sửa đổi trong gói B04a.

---

## 4. Handoff Sang Gói B04b

- **Trạng thái:** Backend Verifier đã hoàn thành nền tảng xác thực giao dịch, kiểm soát quyền sở hữu và hợp đồng dữ liệu.
- **Tiếp theo theo kế hoạch (B04b):**
  - Vòng đời subscription, acknowledge/retry và revoke phía server (F03/F06).
  - Xử lý Real-Time Developer Notifications (RTDN) webhook hoặc reconciliation job, gia hạn subscription với linked token, xử lý hủy gói và thu hồi quyền.
