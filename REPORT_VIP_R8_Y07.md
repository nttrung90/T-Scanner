# Báo Cáo Gói Y07 — Purchase Operation Owner & Readiness Trước Await & Final Launch (G07)

## 1. Mục tiêu và Phạm vi
- **Mục tiêu:** Giải quyết khiếm khuyết G07 (C804, C805, C807) — Purchase action coordinator và billing manager phải kiểm tra account identity, session generation, token expiry và client readiness trước và sau các bước bất đồng bộ (`startConnection`, `queryProducts`) và ngay trước khi gọi `launchBillingFlow`.
- **Files thay đổi:**
  - `app/src/main/java/com/tscanner/app/utils/billing/VipPurchaseActionCoordinator.kt`
  - `app/src/main/java/com/tscanner/app/utils/billing/BillingManager.kt`

## 2. Chi tiết Triển khai
- **Session & Token Verification across Async Boundaries:**
  - Trong `VipPurchaseActionCoordinator.kt`, tạo hàm `validateCurrentSession(operationSessionId, initialUserId, initialGen, initialToken)`:
    - Kiểm tra `billingManager.operationSessionId == operationSessionId`
    - Kiểm tra `billingManager.currentUserId == initialUserId`
    - Kiểm tra `billingManager.sessionGeneration == initialGen`
    - Kiểm tra tính hợp lệ và thời hạn token thông qua `PlayPurchaseVerifier.isTokenExpired(initialToken)`. Nếu token hết hạn, báo lỗi `Session expired, please sign in again`.
  - Kiểm tra `validateCurrentSession` tại các điểm mấu chốt:
    1. Ngay khi bắt đầu xử lý `executePurchaseAction`
    2. Sau khi `startConnection` hoàn tất (callback)
    3. Sau khi `queryProducts` hoàn tất (callback)
    4. Ngay trước khi gọi `billingManager.launchWithProductDetails`
- **Client Readiness & Activity Liveness Check:**
  - Kiểm tra `billingManager.isReady` và trạng thái `BillingClient.isReady` trước khi launch.
  - Kiểm tra `activity == null || activity.isFinishing || activity.isDestroyed` tại mọi callback async trước khi tương tác UI hoặc gọi Play Billing SDK.
- **BillingManager Guards:**
  - Trong `BillingManager.launchWithProductDetails`, bổ sung kiểm tra session generation và readiness trước khi tạo `BillingFlowParams`.

## 3. Kết quả Kiểm thử
- **SDK Probes (Isolated JVM Test via `audit.init.gradle`):**
  - Command: `./gradlew -I docs/vip-round8-20261001/audit.init.gradle :app:testDebugUnitTest --tests "com.tscanner.app.RootRound8AuthAuditTest.C804*" --tests "com.tscanner.app.RootRound8AuthAuditTest.C805*" --tests "com.tscanner.app.RootRound8AuthAuditTest.C807*" --offline`
  - C804 (`expired_token_during_query_aborts_launch_and_resets_pending`): **PASSED**
  - C805 (`account_switch_during_connect_aborts_launch`): **PASSED**
  - C807 (`stale_session_generation_aborts_launch_before_execute`): **PASSED**
- **Coordinator Unit Suite:**
  - Command: `./gradlew testDebugUnitTest --tests com.tscanner.app.VipPurchaseActionCoordinatorTest --offline`
  - Kết quả: **11/11 tests PASSED** (0 failures, 0 skipped).

## 4. Trạng thái & Chuyển giao
- Gói Y07: **DONE**
- Tự động chuyển tiếp: **Y08 (Guard HTTPS/Auth/Session ở actual verify & restore transport — G08)**
