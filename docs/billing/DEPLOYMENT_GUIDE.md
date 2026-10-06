# Google Play Billing Verifier — Deployment & Configuration Guide

**Ngày phát hành:** 26/09/2026  
**Áp dụng:** `backend/billing-verifier`

---

## 1. Yêu Cầu Lưu Trữ Bền Vững (Durable Storage Contract)

1. **Cấm bộ nhớ tạm (`:memory:`) trong môi trường Production:**
   - Khi `NODE_ENV=production`, server bắt buộc phải có `DATABASE_URL` hoặc `SQLITE_PATH` trỏ tới đường dẫn file vật lý trên persistent volume.
   - Nếu thiếu cấu hình lưu trữ bền vững, server sẽ dừng khởi động ngay lập tức (fail-closed) với lỗi:
     `FATAL: Missing DATABASE_URL or SQLITE_PATH in production environment.`
2. **Persistent Volume & Single-Writer Model:**
   - Khi sử dụng SQLite cục bộ, volume lưu trữ phải là Persistent Volume Claim (PVC) gắn cố định với instance hoặc StatefulSet với `replicas: 1` để đảm bảo độc quyền ghi (single-writer).
   - Nếu triển khai mô hình multi-instance (scaling horizontally), bắt buộc sử dụng cơ sở dữ liệu dùng chung (PostgreSQL) thông qua `DATABASE_URL` tương thích.

---

## 2. Các Biến Môi Trường Bắt Buộc (Production Environment Variables)

| Biến | Ý nghĩa | Mẫu giá trị |
|---|---|---|
| `NODE_ENV` | Môi trường thực thi | `production` |
| `PORT` | Cổng HTTP lắng nghe | `8080` |
| `SQLITE_PATH` | Đường dẫn file cơ sở dữ liệu bền vững | `/data/billing/entitlements.db` |
| `GOOGLE_CLIENT_ID` | Web Client ID để xác thực ID Token người dùng Android | `123456789-xxxx.apps.googleusercontent.com` |
| `GOOGLE_JWKS_URI` | URI JWKS public keys của Google (tự động xoay khóa) | `https://www.googleapis.com/oauth2/v3/certs` |
| `PUBSUB_AUDIENCE` | Target URL được Pub/Sub push tới (đối soát claim `aud`) | `https://verifier.tscanner.app/api/v1/billing/rtdn` |
| `PUBSUB_SERVICE_ACCOUNT` | Email Service Account Google Cloud thực hiện push RTDN | `pubsub-push@t-scanner-prod.iam.gserviceaccount.com` |
| `GOOGLE_APPLICATION_CREDENTIALS` | Đường dẫn file JSON Service Account Google Play Console | `/secrets/google/service-account.json` |
| `ALLOW_HMAC_FALLBACK` | Cho phép HMAC | `false` (Bắt buộc `false` ở Production) |

---

## 3. Endpoints Sức Khỏe & Sẵn Sàng (Health & Readiness)

- **Liveness probe (`GET /health`):**
  - Trả về HTTP 200 `{ "status": "UP", "timestamp": ... }` khi tiến trình HTTP server đang hoạt động.
- **Readiness probe (`GET /readiness` hoặc `GET /ready`):**
  - Kiểm tra điều kiện vận hành thực tế:
    - `checks.storage`: Kiểm tra storage bền vững (không phải in-memory).
    - `checks.auth`: Kiểm tra Google Client ID / JWKS hoặc auth credentials đã được cấu hình.
  - Phản hồi:
    - HTTP 200 `{ "status": "READY", "checks": { "storage": "OK", "auth": "OK" } }` khi đủ điều kiện nhận lưu lượng.
    - HTTP 503 `{ "status": "NOT_READY", ... }` khi thiếu cấu hình hoặc lưu trữ không bền vững.

---

## 4. Cơ Chế Atomic Grant + Ack Outbox & Khôi Phục Sau Crash

1. **Giao dịch nguyên tử (Atomic Transaction):**
   - Lưu trữ quyền `BillingEntitlement` và hàng đợi `ack_retry_queue` được thực hiện trong cùng một transaction `BEGIN IMMEDIATE ... COMMIT`.
   - Bao phủ toàn bộ các giao dịch active và canceled-active còn hạn nhưng chưa acknowledge (`acknowledgementState == 0`).
2. **Tiến trình AckWorker nền:**
   - Tự động thăm dò hàng đợi và thực hiện acknowledge tới Google Play Developer API.
   - Thao tác là idempotent (xử lý an toàn khi Play báo `already acknowledged`).
   - Tự động phục hồi toàn bộ job còn dở dang sau khi tiến trình server khởi động lại.
