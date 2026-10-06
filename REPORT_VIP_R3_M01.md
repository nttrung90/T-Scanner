# Báo Cáo Triển Khai VIP Vòng 3 — Gói M01: Google User & Pub/Sub Auth Thật (F01)

**Thời điểm:** 26/09/2026  
**Mục tiêu:** Loại bỏ phát hiện F01 (auth production dùng HMAC/dev secret), triển khai xác thực chữ ký số Google Identity (RS256 qua JWKS key rotation) và Google Cloud Pub/Sub OIDC authentication thật, chặn cấu hình thiếu theo nguyên tắc fail-closed.

---

## 1. File Thay Đổi & Tạo Mới

- `backend/billing-verifier/src/auth.ts`:
  - Triển khai `GoogleJwksClient` với in-memory key cache, TTL và cơ chế key rotation tự động khi gặp `kid` mới.
  - Loại bỏ hoàn toàn fallback bí mật phát triển `'tscanner-dev-secret'`; chặn đứng token khai `RS256` nhưng ký bằng HMAC (ngăn chặn tấn công Algorithm Confusion).
  - Bắt buộc kiểm tra `exp` (thời hạn sống của token không được để trống hoặc hết hạn), `sub` (canonical app user id), `iss` (Google accounts), `aud` (Google Client ID hoặc Pub/Sub target endpoint).
  - Đối với Google Cloud Pub/Sub push notification: kiểm tra `email_verified == true`, khớp `aud` endpoint và `email` của Service Account được ủy quyền.
  - Cung cấp `createTestRsaJwt` phục vụ kiểm thử RSA tổng hợp nội bộ không cần gọi mạng ra Google thật.
- `backend/billing-verifier/src/types.ts`:
  - Bổ sung `email_verified` trong `UserPrincipal` và `PubSubPrincipal`.
  - Cập nhật `AuthConfig` với `allowHmacFallback`, `googleClientId`, `jwksUri`, `jwksFetchFn`, `keyRotationTtlMs`, `pubsubExpectedAudience`, `pubsubExpectedServiceAccount`.
- `backend/billing-verifier/test/http-auth.test.ts`:
  - Mở rộng bộ kiểm thử HTTP tích hợp thêm 10 kịch bản Google Identity RS256: synthetic positive, invalid RSA signature, algorithm mismatch, missing/expired `exp`, wrong `iss`/`aud`, unknown `kid`, lỗi refresh JWKS, Pub/Sub OIDC positive, wrong service account, unverified service account email.
- `REPORT_VIP_R3_M01.md`: Báo cáo nội bộ gói M01.

---

## 2. Bằng Chứng Red -> Green & Thực Thi Thực Tế

### 2.1. Hai Probe Đỏ M00 Chuyển Xanh
- Test 1: `auth must reject development-key JWT without required identity claims`
  - Trước (M00): **FAIL** (`Missing expected exception`)
  - Sau (M01): **PASS** (Bắt buộc `exp` và cấm phát triển secret `tscanner-dev-secret`)
- Test 2: `auth must reject unsupported algorithm even when HMAC matches`
  - Trước (M00): **FAIL** (`Missing expected exception`)
  - Sau (M01): **PASS** (Chặn đứng token `RS256` khi verifier chỉ có cấu hình HMAC)

### 2.2. Kiểm Tra Focused HTTP Auth Suite
- Lệnh: `node --experimental-strip-types --test backend/billing-verifier/test/http-auth.test.ts`
- Kết quả: **11/11 PASS**, 0 fail, thời gian: ~616ms.

### 2.3. Kiểm Tra Toàn Bộ Backend Suite
- Lệnh: `node --experimental-strip-types --test backend/billing-verifier/test/*.test.ts`
- Kết quả: **67/72 PASS** (5 fail còn lại là probe có chủ đích của các gói M02, M03, M04, M05). Không phát sinh bất kỳ regression nào trong 64 test baseline gốc.

---

## 3. Handoff Contract Credential & Biến Môi Trường (Cho Vận Hành)

- Không lưu bất kỳ secret key nào trong mã nguồn.
- Biến môi trường yêu cầu cho backend verifier production:
  - `GOOGLE_CLIENT_ID`: Web Client ID của ứng dụng để đối soát `aud`.
  - `PUBSUB_AUDIENCE`: URL webhook nhận RTDN (ví dụ: `https://<DOMAIN>/api/v1/billing/rtdn`).
  - `PUBSUB_SERVICE_ACCOUNT`: Email Google Service Account được cấp quyền push Pub/Sub (ví dụ: `billing-pubsub@<PROJECT>.iam.gserviceaccount.com`).
  - `GOOGLE_JWKS_URI`: URI JWKS của Google (mặc định `https://www.googleapis.com/oauth2/v3/certs`).
  - `ALLOW_HMAC_FALLBACK`: `false` ở production (chỉ dùng cho môi trường test khi cần).

---

## 4. Việc Gói Sau (M02) Cần Biết

- F01 đã được giải quyết triệt để ở tầng Auth.
- Gói M02 tiếp nhận: Xử lý F06 phía backend (`googlePlayClient.ts` và `types.ts`), parse Google Play API authoritative state (subscriptionsv2/current state), sửa lỗi `{}` HTTP 200 tự động cấp lifetime VIP, map chính xác các trạng thái subscription và inapp, xử lý 401/403/429/5xx và timeout.
- Chuyển tiếp tự động sang M02.
