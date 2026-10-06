# Báo Cáo Triển Khai VIP Vòng 4 — Gói T01: Auth Config & Claims Fail-Closed (R02)

**Thời điểm:** 27/09/2026  
**Mục tiêu:** Loại bỏ phát hiện R02, chuẩn hóa cấu hình `googleClientId` và `expectedAudience`, bắt buộc kiểm tra `aud` đối với mọi user token RS256, áp dụng cơ chế fail-closed cho route Pub/Sub push khi thiếu cấu hình audience hoặc service account, bổ sung kiểm tra issuer Google accounts cho Pub/Sub tokens, và phân định tính sẵn sàng riêng biệt giữa user auth và push auth trong endpoint `/readiness`.

---

## 1. File Thay Đổi & Tạo Mới

- `backend/billing-verifier/src/auth.ts`:
  - Chuẩn hóa `resolvedAudience = config.expectedAudience || config.googleClientId || process.env.JWT_AUDIENCE || process.env.GOOGLE_CLIENT_ID`, đảm bảo khi truyền `googleClientId` vào cấu hình thì `expectedAudience` được gán giá trị tương đương một cách nhất quán.
  - Bắt buộc kiểm tra `aud` cho token RS256; nếu hệ thống chưa được cấu hình audience, ném lỗi fail-closed `AuthenticationError`.
  - Trong `authenticatePubSub`:
    1. Kiểm tra cấu hình: nếu thiếu `pubsubExpectedAudience` hoặc `pubsubExpectedServiceAccount`, ném lỗi fail-closed `AuthorizationError`.
    2. Bổ sung kiểm tra `iss`: bắt buộc thuộc Google accounts (`https://accounts.google.com` hoặc `accounts.google.com`), từ chối các issuer không hợp lệ.
    3. Kiểm tra bắt buộc khớp cả `aud` lẫn `email` Service Account được ủy quyền.
  - Tách các hàm kiểm tra khả năng sẵn sàng: `isUserAuthConfigured()` và `isPubSubConfigured()`.
- `backend/billing-verifier/src/index.ts`:
  - Cập nhật endpoint `/readiness`: kiểm tra độc lập `userAuth` và `pushAuth`, trả về HTTP 200 `READY` khi cả hai đều sẵn sàng, hoặc HTTP 503 `NOT_READY` nếu thiếu một trong các cấu hình.
- `backend/billing-verifier/test/ack-restart.test.ts`:
  - Cập nhật bài test readiness phù hợp với cấu trúc phân tách `userAuth` và `pushAuth`.
- `backend/billing-verifier/test/http-auth.test.ts`:
  - Bổ sung test case `HTTP Auth: Readiness differentiates user auth vs push auth capability`.
- `REPORT_VIP_R4_T01.md`: Báo cáo nội bộ gói T01.

---

## 2. Bằng Chứng Red -> Green & Thực Thi Thực Tế

### 2.1. Ba Probe Đỏ T00 Chuyển Xanh
1. Test: `configured googleClientId must enforce matching audience`
   - Trước (T00): **FAIL** (`Missing expected rejection`)
   - Sau (T01): **PASS** (`googleClientId` kích hoạt kiểm tra `aud`, token khác audience bị từ chối)
2. Test: `PubSub must fail closed without audience and service account configuration`
   - Trước (T00): **FAIL** (`Missing expected rejection`)
   - Sau (T01): **PASS** (Thiếu audience/service account bị từ chối `AuthorizationError` fail-closed)
3. Test: `PubSub must validate issuer in addition to audience and email`
   - Trước (T00): **FAIL** (`Missing expected rejection`)
   - Sau (T01): **PASS** (Issuer `https://invalid.example` bị từ chối `AuthenticationError`)

### 2.2. Kiểm Tra Focused Http Auth Suite
- Lệnh: `node --experimental-strip-types --test backend/billing-verifier/test/http-auth.test.ts`
- Kết quả: **12 / 12 PASS (100% GREEN)**, thời gian: ~448ms.

---

## 3. Handoff Cho Gói Sau (T02)

- Xác thực danh tính và Pub/Sub push đã được khóa chặt, fail-closed an toàn.
- Gói T02 tiếp nhận: Sửa R03 (Đường query V2 và state/product contract):
  - File: `backend/billing-verifier/src/googlePlayClient.ts`, `types.ts`, `verifier.ts`, `rtdnHandler.ts`.
  - Chuyển URL truy vấn của `ProductionGooglePlayBillingApi` sang endpoint `purchases/subscriptionsv2/tokens/{token}` (v2) chính thức của Google Play.
  - Xử lý 3 probe đỏ:
    1. `V2 SUBSCRIPTION_STATE_PAUSED must not become active paid entitlement` (PAUSED không được cấp active paid).
    2. `V2 SUBSCRIPTION_STATE_UNKNOWN must not become active paid entitlement` (UNKNOWN phải bị từ chối).
    3. `V2 product mismatch must not grant requested VIP` (khớp chính xác productId của lineItem, không fallback `lineItems[0]` của product khác).
- Chuyển tiếp tự động sang T02.
