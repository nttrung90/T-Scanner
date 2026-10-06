# Hợp Đồng Entitlement, Ownership và Backend Xác Thực VIP Google Play (B03)

**Tài liệu:** `docs/billing/ENTITLEMENT_CONTRACT.md`  
**Ngày ban hành:** 26/09/2026  
**Áp dụng:** Ứng dụng Android T-Scanner (`:app`) và Server Xác thực Google Play (`backend/billing-verifier/`)  
**Khắc phục lỗi kiểm toán:** F02 (Cộng dồn hạn vô hạn), F03 (Xóa VIP khi restore rỗng), F04 (Thiếu backend verifier), F05 (Không kiểm soát quyền sở hữu receipt).

---

## 1. Mục Đích & Nguyên Tắc Cốt Lõi

1. **Một Nguồn Chân Lý Thẩm Quyền (Single Source of Truth):**
   - Chỉ có Google Play Developer API (thông qua Backend Verifier) mới có thẩm quyền xác nhận một giao dịch mua là hợp lệ (`VERIFIED`), xác định thời hạn hết hạn chính xác (`expiryTimeMillis`), và trạng thái gia hạn (`autoRenewing`).
   - Client Android tuyệt đối **không tự quyết định thời hạn** bằng cách cộng `+30 ngày` hay `+10 năm`.
2. **Bất Biến Idempotency (Không Cộng Dồn):**
   - Áp dụng lại một giao dịch hoặc restore nhiều lần phải cho ra cùng một thời điểm hết hạn tuyệt đối (`expiryTimeMillis`). Tuyệt đối không cộng dồn thời gian khi replay receipt.
3. **Ràng Buộc Sở Hữu Chặt Chẽ (Ownership Binding):**
   - Một `purchaseToken` chỉ được liên kết với một tài khoản ứng dụng canonical (`ownerAppUserId`).
   - Khi thực hiện mua, ứng dụng truyền `setObfuscatedAccountId(sha256(canonicalUserId))` vào `BillingFlowParams`. Server xác thực kiểm tra mã hash này để ngăn chặn tài khoản B sử dụng receipt của tài khoản A.
   - Trường hợp người dùng mua ở chế độ Khách (Guest / Unbound): Receipt được lưu ở trạng thái `UNBOUND`. Khi người dùng đăng nhập tài khoản thực sự, quy trình gắn kết (`bind`) một chiều sẽ gán quyền sở hữu vĩnh viễn cho tài khoản đó.
4. **Không Giả Lập Thành Công (No Fake Verified):**
   - Khi chưa có backend xác thực hoặc thiếu kết nối xác thực, client chỉ lưu trạng thái `UNVERIFIED_CLIENT` hoặc báo `MissingBackendGate`. Không bao giờ đánh dấu `VERIFIED` khi chưa có phản hồi từ server.
5. **Kháng Lỗi Mạng & Trực Giao:**
   - Lỗi kết nối mạng tạm thời (`TransientError`) không được làm mất quyền VIP đã lưu trong cache hợp lệ, và ngược lại cũng không được tự ý cấp VIP vô hạn.

---

## 2. Mô Hình Dữ Liệu Entitlement (Data Model Contract)

### 2.1. Trạng Thái Vòng Đời Entitlement (`EntitlementState`)

| Trạng thái | Diễn giải | VIP Active? | Ghi chú |
|---|---|:---:|---|
| `VERIFIED_ACTIVE` | Đã xác thực với Play Console, đang trong chu kỳ thanh toán hợp lệ. | **CÓ** | Cấp toàn bộ quyền VIP. |
| `IN_GRACE_PERIOD` | Đang trong thời gian ân hạn do thẻ ngân hàng lỗi trừ tiền. | **CÓ** | Vẫn cho dùng VIP nhưng hiển thị cảnh báo cập nhật phương thức thanh toán. |
| `CANCELED_ACTIVE` | Người dùng đã hủy tự động gia hạn, nhưng chu kỳ hiện tại vẫn còn hạn. | **CÓ** | Cho dùng VIP đến đúng `expiryTimeMillis`. |
| `PENDING_PAYMENT` | Giao dịch chậm (chờ thanh toán tiền mặt/chuyển khoản). | **KHÔNG** | Chờ Google Play xử lý xong và gửi thông báo mới. |
| `ON_HOLD` | Hết ân hạn mà chưa trả tiền, tài khoản bị tạm khóa. | **KHÔNG** | Tạm ngưng quyền VIP cho đến khi thanh toán thành công. |
| `PAUSED` | Người dùng chủ động tạm dừng gói đăng ký. | **KHÔNG** | Tạm ngưng quyền VIP trong thời gian pause. |
| `EXPIRED` | Đã quá hạn chu kỳ thanh toán và không được gia hạn tiếp. | **KHÔNG** | Hết hạn hoàn toàn. |
| `REVOKED` | Giao dịch bị hoàn tiền (refund), chargeback hoặc bị Google hủy. | **KHÔNG** | Thu hồi quyền VIP ngay lập tức. |
| `UNVERIFIED_CLIENT` | Client Play SDK báo PURCHASED nhưng server chưa xác thực. | **KHÔNG** | Chờ xác thực backend, không tự động cấp VIP. |

### 2.2. Cấu Trúc Bản Ghi Entitlement (`BillingEntitlement`)

```json
{
  "id": "GOOGLE_PLAY_SUBS_inapp_token_xyz123",
  "ownerAppUserId": "usr_canonical_987654",
  "productId": "tscanner_vip_yearly",
  "productType": "subs",
  "purchaseToken": "inapp_token_xyz123",
  "orderId": "GPA.3344-5566-7788-99000",
  "source": "GOOGLE_PLAY_SUBSCRIPTION",
  "state": "VERIFIED_ACTIVE",
  "purchaseTimeMillis": 1727330000000,
  "expiryTimeMillis": 1758866000000,
  "autoRenewing": true,
  "verifiedAtMillis": 1727330005000,
  "snapshotVersion": 1
}
```

*Đặc thù gói Lifetime:*
- `productId`: `"tscanner_vip_lifetime"`
- `productType`: `"inapp"`
- `expiryTimeMillis`: `null` (không có ngày hết hạn; biểu diễn quyền sở hữu vĩnh viễn chừng nào `state == VERIFIED_ACTIVE`).

---

## 3. Giao Thức API Giữa Android Client và Backend Verifier

Thư mục backend dự kiến: `backend/billing-verifier/`  
Chuẩn giao thức: REST / JSON qua HTTPS với Bearer JWT Authentication (tài khoản Firebase/AppAuth của người dùng).

### 3.1. Endpoint Xác Thực Giao Dịch Mua Mới (`POST /api/v1/billing/verify`)

**Headers:**
- `Authorization: Bearer <ID_TOKEN_CỦA_USER>`
- `Content-Type: application/json`

**Request Body:**
```json
{
  "productId": "tscanner_vip_yearly",
  "productType": "subs",
  "purchaseToken": "token_abc123",
  "orderId": "GPA.1234-5678-9012-34567",
  "obfuscatedAccountId": "6a8b...sha256",
  "clientPurchaseTimeMillis": 1727330000000
}
```

**Response 200 OK (Thành công):**
```json
{
  "status": "SUCCESS",
  "entitlement": {
    "id": "GOOGLE_PLAY_SUBS_token_abc123",
    "ownerAppUserId": "usr_canonical_987654",
    "productId": "tscanner_vip_yearly",
    "productType": "subs",
    "purchaseToken": "token_abc123",
    "orderId": "GPA.1234-5678-9012-34567",
    "source": "GOOGLE_PLAY_SUBSCRIPTION",
    "state": "VERIFIED_ACTIVE",
    "purchaseTimeMillis": 1727330000000,
    "expiryTimeMillis": 1758866000000,
    "autoRenewing": true,
    "verifiedAtMillis": 1727330005000,
    "snapshotVersion": 1
  }
}
```

**Response 409 Conflict (Tranh chấp sở hữu):**
```json
{
  "status": "REJECTED",
  "reason": "OWNERSHIP_CONFLICT",
  "message": "Giao dịch này đã được liên kết với một tài khoản người dùng khác."
}
```

**Response 400 Bad Request (Giao dịch không hợp lệ / Refund):**
```json
{
  "status": "REJECTED",
  "reason": "PURCHASE_REVOKED",
  "message": "Giao dịch đã bị hoàn tiền hoặc hủy trên Google Play."
}
```

### 3.2. Endpoint Khôi Phục Quyền Mua (`POST /api/v1/billing/restore`)

**Headers:**
- `Authorization: Bearer <ID_TOKEN_CỦA_USER>`
- `Content-Type: application/json`

**Request Body:**
```json
{
  "purchases": [
    {
      "productId": "tscanner_vip_yearly",
      "productType": "subs",
      "purchaseToken": "token_abc123"
    },
    {
      "productId": "tscanner_vip_lifetime",
      "productType": "inapp",
      "purchaseToken": "token_life_999"
    }
  ]
}
```

**Response 200 OK:**
```json
{
  "status": "SUCCESS",
  "snapshot": {
    "ownerAppUserId": "usr_canonical_987654",
    "entitlements": [
      {
        "id": "GOOGLE_PLAY_SUBS_token_abc123",
        "ownerAppUserId": "usr_canonical_987654",
        "productId": "tscanner_vip_yearly",
        "productType": "subs",
        "purchaseToken": "token_abc123",
        "orderId": "GPA.1234-5678-9012-34567",
        "source": "GOOGLE_PLAY_SUBSCRIPTION",
        "state": "VERIFIED_ACTIVE",
        "purchaseTimeMillis": 1727330000000,
        "expiryTimeMillis": 1758866000000,
        "autoRenewing": true,
        "verifiedAtMillis": 1727330005000,
        "snapshotVersion": 2
      }
    ],
    "computedAtMillis": 1727330006000
  }
}
```

### 3.3. Endpoint Xác Nhận Giao Dịch Mua (`POST /api/v1/billing/acknowledge`)

**Headers:**
- `Authorization: Bearer <ID_TOKEN_CỦA_USER>`
- `Content-Type: application/json`

**Request Body:**
```json
{
  "purchaseToken": "token_abc123",
  "productId": "tscanner_vip_yearly",
  "productType": "subs"
}
```

**Quy Tắc Phân Quyền (`403 Forbidden`):**
- Caller phải là chủ sở hữu hợp pháp của `purchaseToken` đã được xác thực trước đó trong store (`record.ownerAppUserId === principal.sub`).
- Không cho phép acknowledge giao dịch của người dùng khác hoặc giao dịch chưa qua xác thực (`ack không bypass verify/owner`).

### 3.4. Endpoint Webhook Google Cloud Pub/Sub RTDN (`POST /api/v1/billing/rtdn`)

**Headers:**
- `Authorization: Bearer <OIDC_GOOGLE_PUBSUB_TOKEN>` hoặc `X-PubSub-Secret: <SHARED_SECRET>`
- `Content-Type: application/json`

**Quy Tắc Xác Thực:**
- Xác thực chữ ký số, audience (`aud`), issuer (`iss`) và service account push identity.
- Trả `401 Unauthorized` hoặc `403 Forbidden` nếu thiếu auth hoặc giả mạo danh tính push.

### 3.5. Quy Tắc Xác Thực & Danh Tính Người Dùng (Hardened Auth - Q05 / R07)

1. **Ủy Quyền Thẩm Quyền (Principal Derivation):**
   - Danh tính người dùng (`ownerAppUserId`) luôn được trích xuất trực tiếp từ trường `sub` của JWT token đã qua xác thực chữ ký số, thời hạn (`exp`), nhà phát hành (`iss`), và đối tượng (`aud`).
   - Trường `ownerAppUserId` trong payload client (nếu có) chỉ dùng để đối chiếu. Nếu khác `principal.sub`, server từ chối ngay lập tức với mã `403 Forbidden`.
2. **Fail-Closed:**
   - Mọi request thiếu token, token hết hạn, sai chữ ký số, hoặc sai lệch cấu hình audience/issuer đều bị chặn ở tầng HTTP gateway/middleware với mã `401 Unauthorized` hoặc `403 Forbidden`.
   - Tuyệt đối không gọi sang `EntitlementStore` hoặc `GooglePlayBillingApi` khi chưa vượt qua bước xác thực danh tính.

---

## 4. Quyết Định Công Nghệ Cho Backend Verifier (B04a / B04b)

1. **Vị Trí Module:**
   - Tạo thư mục riêng biệt tại gốc repo: `backend/billing-verifier/`.
   - Hoàn toàn tách biệt khỏi mã nguồn Android `:app`.
2. **Công Nghệ Đề Xuất:**
   - **Lựa chọn 1 (Khuyến nghị cho server nhẹ/Cloud Functions):** Node.js 20+ với TypeScript, Express/Fastify, tích hợp thư viện chính thức `googleapis` (`google.androidpublisher('v3')`).
   - **Lựa chọn 2 (Thống nhất sinh thái Kotlin):** Kotlin Ktor Server hoặc Spring Boot, tích hợp Google API Client Library for Java (`google-api-services-androidpublisher`).
3. **Quản Lý Bí Mật (Credentials & Security):**
   - Google Service Account Key (JSON) **tuyệt đối không commit vào git**. Cung cấp thông qua biến môi trường `GOOGLE_APPLICATION_CREDENTIALS`.
   - Khi chạy unit tests ở local, backend giả lập (mock boundary) Google Developer API để kiểm tra toàn bộ logic ràng buộc ownership, replay idempotency, và package validation mà không cần kết nối mạng bên ngoài.

---

## 5. Quy Tắc Chuyển Đổi Dữ Liệu Cũ (Legacy Migration Policy)

- Trường hợp người dùng đang có cờ SharedPreferences `billing_vip_active = true` cũ:
  - Bản ghi này được phân loại là `EntitlementSource.LEGACY_LOCAL` với `state = EntitlementState.UNVERIFIED_CLIENT`.
  - Không tự ý biến `LEGACY_LOCAL` thành `VERIFIED_ACTIVE` mà không có `purchaseToken` hợp lệ từ Play Store hoặc xác thực backend.
  - Khi người dùng mở app có mạng, ứng dụng kích hoạt quy trình reconcile chạy ngầm để lấy lại purchase token chính thức từ Play Store và nâng cấp thành verified entitlement.
