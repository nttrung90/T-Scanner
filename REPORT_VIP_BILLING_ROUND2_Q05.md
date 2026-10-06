# Báo cáo kết quả VIP Billing Vòng 2 — Gói Q05

**Ngày thực hiện:** 26/09/2026  
**Kho mã:** `E:\DU AN AI\T-Scanner`  
**Mục tiêu gói Q05:** Thực thi Xác thực HTTP User và Pub/Sub RTDN (Defect R07 — Hardening Authentication & Identity on Verifier Service).

---

## 1. Tóm tắt nguyên nhân gốc & Giải pháp kỹ thuật

### Nguyên nhân gốc (Defect R07)
1. **Endpoint backend không kiểm tra ủy quyền người gọi**:
   - `backend/billing-verifier/src/index.ts` trước đây tiếp nhận các request `POST /api/v1/billing/verify`, `POST /api/v1/billing/restore`, và `POST /api/v1/billing/acknowledge` mà không hề kiểm tra header `Authorization`.
   - Caller có thể gửi bất kỳ giá trị `ownerAppUserId` nào trong JSON body. Server tin tưởng hoàn toàn danh tính tự khai này và trả về toàn bộ dữ liệu VIP/entitlement đã lưu của người dùng đó (HTTP 200 OK).
2. **Không xác thực Webhook Google Cloud Pub/Sub RTDN**:
   - Route `/api/v1/billing/rtdn` không xác thực push identity, audience hoặc token, khiến kẻ tấn công có thể giả mạo các thông báo RTDN để thao túng trạng thái entitlement của người dùng.
3. **Endpoint acknowledge cho phép bypass xác thực và chiếm dụng token**:
   - `/api/v1/billing/acknowledge` cho phép bất kỳ caller nào gửi `purchaseToken` và gọi Google Play API xác nhận mà không kiểm tra token đó thuộc về ai hoặc đã được xác thực hợp lệ hay chưa.

### Giải pháp kỹ thuật trong Q05
1. **Dịch vụ xác thực tập trung (`backend/billing-verifier/src/auth.ts`)**:
   - Xây dựng `AuthService` và các lớp lỗi chuyên biệt `AuthenticationError` (HTTP 401) và `AuthorizationError` (HTTP 403).
   - Kiểm tra định dạng `Authorization: Bearer <token>`, phân tích cấu trúc 3 phần chuẩn của JWT.
   - Xác thực chữ ký số bằng thuật toán mã hóa (HMAC-SHA256 với secret hoặc Google OIDC public certs), cấm tuyệt đối thuật toán mất an toàn `alg: "none"`.
   - Kiểm tra hạn dùng của token (`exp`), nhà phát hành (`iss`), và đối tượng hợp lệ (`aud`).
   - Hỗ trợ xác thực webhook Pub/Sub qua OIDC Bearer token (kiểm tra `aud` webhook URL và service account email) hoặc khóa bí mật dùng chung `X-PubSub-Secret` / tham số `?secret=`.
   - Cung cấp hàm tiện ích `createTestJwt()` phục vụ kiểm thử toàn diện.
2. **Trích xuất danh tính từ Principal và Chống giả mạo chủ sở hữu (`sub`)**:
   - Trong `src/index.ts`:
     - Danh tính người dùng (`ownerAppUserId`) luôn được trích xuất trực tiếp từ trường `principal.sub` của token đã qua xác thực chữ ký số.
     - Nếu request body có truyền `ownerAppUserId`, server đối chiếu `body.ownerAppUserId === principal.sub`. Nếu có sự sai lệch (ví dụ User B mạo danh User A), server trả về ngay lập tức `403 Forbidden` (`Caller identity does not match requested ownerAppUserId`).
3. **Bảo vệ Endpoint Acknowledge**:
   - Trước khi gửi lệnh acknowledge tới Google Play, server tra cứu `store.getRecordByToken(body.purchaseToken)`.
   - Token phải tồn tại trong store (đã qua bước verify trước đó) và chủ sở hữu phải khớp với caller (`record.ownerAppUserId === principal.sub`). Nếu không, trả về `403 Forbidden`.
4. **Nguyên tắc Fail-Closed & Bảo vệ Store / Google API**:
   - Nếu xác thực thất bại (401 hoặc 403), request bị từ chối ngay lập tức tại tầng gateway HTTP, tuyệt đối không gọi `verifierService`, `store`, `ackService`, hay `googlePlayApi`.
5. **Cập nhật Hợp đồng Entitlement**:
   - Cập nhật tài liệu `docs/billing/ENTITLEMENT_CONTRACT.md` bổ sung quy định chi tiết về Authorization Bearer header, Acknowledge ownership guard, và Pub/Sub RTDN push authentication.

---

## 2. Danh sách tệp chỉnh sửa & tạo mới (Whitelist Q05)

| STT | Đường dẫn tệp | Loại thay đổi | Trách nhiệm |
|---|---|---|---|
| 1 | `backend/billing-verifier/src/auth.ts` | Tạo mới | Dịch vụ xác thực JWT Bearer token người dùng và OIDC/Secret Pub/Sub webhook. |
| 2 | `backend/billing-verifier/src/types.ts` | Chỉnh sửa | Bổ sung `UserPrincipal`, `PubSubPrincipal`, `AuthConfig`, `AcknowledgeRequest`, làm tùy chọn `ownerAppUserId` trong request body. |
| 3 | `backend/billing-verifier/src/index.ts` | Chỉnh sửa | Tích hợp `AuthService`, kiểm tra auth trên verify/restore/ack/rtdn, derive canonical owner từ principal, guard server listen khi chạy test. |
| 4 | `docs/billing/ENTITLEMENT_CONTRACT.md` | Chỉnh sửa | Đóng băng hợp đồng xác thực, giao thức `/acknowledge`, `/rtdn`, và quy tắc phân quyền người dùng. |
| 5 | `backend/billing-verifier/test/http-auth.test.ts` | Tạo mới | Bộ kiểm thử HTTP thực 10 kịch bản bao phủ toàn bộ các trường hợp auth, bypass, mismatch, và Pub/Sub. |
| 6 | `REPORT_VIP_BILLING_ROUND2_Q05.md` | Tạo mới | Báo cáo kiểm định gói Q05. |

---

## 3. Bằng chứng kiểm thử: Trước đỏ (Red) / Sau xanh (Green)

### 3.1. Probe Regression Vòng 2 — Chuyển từ Đỏ sang Xanh

Lệnh thực thi:
```powershell
node --experimental-strip-types --test test/round2-regression.test.ts
```

Kết quả:
- **`probe HTTP restore endpoint must require authentication`**:
  - *Trước sửa:* **FAIL** (`AssertionError: Unauthenticated request returned 200`).
  - *Sau sửa:* **✔ PASS (71.0287ms)** (Unauthenticated request nhận HTTP 401 Unauthorized, store không bị rò rỉ dữ liệu).

### 3.2. Bộ kiểm thử mới `test/http-auth.test.ts` (10/10 PASS — 100% GREEN)

Lệnh thực thi:
```powershell
node --experimental-strip-types --test test/http-auth.test.ts
```

Danh sách ca kiểm thử:
1. `HTTP Auth: Missing Authorization header returns 401 on /verify, /restore, /acknowledge`: **PASS** (Thiếu header nhận 401, Google API không bị gọi).
2. `HTTP Auth: Malformed header and malformed token format return 401`: **PASS** (Header không phải Bearer, Bearer rỗng, hoặc JWT không đủ 3 phần đều nhận 401).
3. `HTTP Auth: Disallowed alg=none returns 401`: **PASS** (Cấm thuật toán `alg: 'none'`).
4. `HTTP Auth: Expired token returns 401`: **PASS** (Token hết hạn bị từ chối 401 với thông báo rõ ràng).
5. `HTTP Auth: Tampered or invalid signature returns 401`: **PASS** (Chữ ký bị sửa đổi hoặc sai secret bị từ chối 401).
6. `HTTP Auth: Audience and Issuer mismatch return 401`: **PASS** (Sai lệch cấu hình audience hoặc issuer bị từ chối 401).
7. `HTTP Auth: Ownership spoofing (User B claiming User A) returns 403 Forbidden`: **PASS** (User B cố tình khai `ownerAppUserId: 'usr_alice'` bị từ chối 403 Forbidden).
8. `HTTP Auth: Canonical owner derived from principal when ownerAppUserId omitted`: **PASS** (Client không truyền owner trong body, server tự động trích xuất từ token và lưu đúng chủ sở hữu vào store).
9. `HTTP Auth: Acknowledge endpoint enforces token ownership and rejects unverified or foreign tokens`: **PASS** (User B không thể ack token của User A; token chưa verify không được ack; User A ack thành công token của mình).
10. `HTTP Auth: RTDN Pub/Sub webhook authentication`: **PASS** (Thiếu auth nhận 401; sai audience/service account nhận 403; token OIDC hoặc secret hợp lệ nhận 200 OK).

### 3.3. Tổng thể kiểm thử Backend Verifier (33 PASS, 2 FAIL chờ Q07-Q08)

Lệnh thực thi:
```powershell
npm test
```

Kết quả:
- **33/35 tests PASS** (Toàn bộ 15 test B04a, 6 test B04b, 10 test HTTP Auth mới, 2 test Regression Round 2).
- **2 test FAIL có chủ đích:** `probe authoritative expired verification must not restore stale active receipt` (Defect R10 -> phân bổ cho Q07) và `probe RTDN retries same event after temporary Google API failure` (Defect R09 -> phân bổ cho Q08).

### 3.4. Kiểm tra hồi quy toàn diện Android Billing (70/70 PASS)

Lệnh thực thi:
```powershell
$env:GRADLE_USER_HOME='C:\Users\nguye\.gradle'
.\gradlew.bat :app:testDebugUnitTest `
  --tests com.tscanner.app.BillingRound2RegressionTest `
  --tests com.tscanner.app.BillingSnapshotPersistenceTest `
  --tests com.tscanner.app.BillingRevocationReconciliationTest `
  --tests com.tscanner.app.BillingOperationSessionTest `
  --tests com.tscanner.app.BillingGuestOwnershipTest `
  --tests com.tscanner.app.BillingPurchaseFixtureTest `
  --tests com.tscanner.app.BillingEntitlementStoreTest `
  --tests com.tscanner.app.BillingManagerTest
```

Kết quả: **70/70 PASS** (0 failed, 0 skipped). Phía client Android duy trì độ ổn định tuyệt đối 100%.

---

## 4. Bàn giao gói tiếp theo (Handoff sang Q06)

- **Mục tiêu Q06:** Ownership Store bền vững và Version qua Restart (Defect R08).
- **Phạm vi tệp whitelist Q06:**
  - `backend/billing-verifier/src/store.ts`
  - Thư mục lưu trữ bền vững (storage/persistence adapter & schema migrations nếu cần)
  - `backend/billing-verifier/src/index.ts` (wiring storage)
  - `backend/billing-verifier/package.json` (nếu cần thêm dependency storage/driver đã được duyệt)
  - `backend/billing-verifier/test/storage-integration.test.ts` (mới)
  - `REPORT_VIP_BILLING_ROUND2_Q06.md`
- **Yêu cầu cốt lõi:**
  - Lưu trữ bền vững (durable persistence) cho token ownership và version.
  - Sống sót qua restart và crash recovery (không dùng `new Map()` đơn thuần làm bằng chứng durability).
  - Khóa giao dịch duy nhất `purchaseToken-owner` (CAS / atomic monotonic version sequence), chống race condition khi nhiều process/worker cùng xử lý.
