# Kế Hoạch Nghiệm Thu VIP Round 4 — Chi Tiết Từng Acceptance Clause (T01–T13)

**Tài liệu:** `docs/billing/ROUND4_ACCEPTANCE.md`  
**Ngày cập nhật:** 27/09/2026 (Hoàn tất T13)  
**Cơ sở kiểm toán:** `RECHECK_VIP_FULL_ROUND4_2026-09-27.md`  
**Kế hoạch thực thi:** `PLAN_VIP_GEMINI_3_8_FLASH_ROUND4_AUTORUN_2026-09-27.md`  
**Trạng thái:** **19/19 ACCEPTANCE CLAUSES VERIFIED & PASSED (100% GREEN)**

---

## 1. Bảng Khung Nghiệm Thu Từng Acceptance Clause

| Gói | Test ID | Lỗi đối soát | Điểm thâm nhập Production (Entrypoint) | Kỳ vọng bất biến (Invariant) | Kết quả Baseline T00 | Kết quả T13 | Đường dẫn bằng chứng | Trạng thái kỹ thuật |
|---|---|:---:|---|---|:---:|:---:|---|:---:|
| **T01** | `auth_google_client_id_aud` | R02 | `AuthService.verifyUserTokenAsync` | `googleClientId` cấu hình phải enforce `aud` khớp | **FAIL** | **PASS** | `backend/billing-verifier/test/round4-regression.test.ts` | **RESOLVED & VERIFIED** |
| **T01** | `auth_pubsub_fail_closed` | R02 | `AuthService.authenticatePubSub` | Thiếu cấu hình audience/service-account phải fail closed | **FAIL** | **PASS** | `backend/billing-verifier/test/round4-regression.test.ts` | **RESOLVED & VERIFIED** |
| **T01** | `auth_pubsub_issuer_check` | R02 | `AuthService.authenticatePubSub` | Phải kiểm tra `iss` của Google accounts bên cạnh aud/email | **FAIL** | **PASS** | `backend/billing-verifier/test/round4-regression.test.ts` | **RESOLVED & VERIFIED** |
| **T01** | `auth_user_valid_control` | R02 | `AuthService.verifyUserTokenAsync` | Token RSA hợp lệ đúng audience được chấp nhận | **PASS** | **PASS** | `backend/billing-verifier/test/round4-regression.test.ts` | **CONTROL MAINTAINED** |
| **T02** | `v2_state_paused_not_active` | R03 | `googlePlayClient.ts` / `verifier.ts` | `SUBSCRIPTION_STATE_PAUSED` không thành active paid | **FAIL** | **PASS** | `backend/billing-verifier/test/round4-regression.test.ts` | **RESOLVED & VERIFIED** |
| **T02** | `v2_state_unknown_rejected` | R03 | `googlePlayClient.ts` / `verifier.ts` | `SUBSCRIPTION_STATE_UNKNOWN` không cấp VIP | **FAIL** | **PASS** | `backend/billing-verifier/test/round4-regression.test.ts` | **RESOLVED & VERIFIED** |
| **T02** | `v2_product_mismatch_rejected`| R03 | `googlePlayClient.ts` / `verifier.ts` | Product lineItems khác product yêu cầu không được grant | **FAIL** | **PASS** | `backend/billing-verifier/test/round4-regression.test.ts` | **RESOLVED & VERIFIED** |
| **T03** | `hash_check_before_mutation` | R05 | `BillingVerifierService.verifyPurchase` | Obfuscated hash và owner phải kiểm trước khi bind inactive | **FAIL** | **PASS** | `backend/billing-verifier/test/round4-regression.test.ts` | **RESOLVED & VERIFIED** |
| **T04** | `cas_concurrency_all_states` | R04 | `sqliteDriver.ts` / `verifier.ts` | CAS tổng quát hai chiều, ACTIVE cũ không hồi sinh EXPIRED | **FAIL** | **PASS** | `backend/billing-verifier/test/round4-regression.test.ts` | **RESOLVED & VERIFIED** |
| **T05** | `restore_refresh_known_tokens`| R01 | `BillingVerifierService.restorePurchases` | Candidate set = known records + caller purchases, refresh Play | **FAIL** | **PASS** | `backend/billing-verifier/test/round4-regression.test.ts` | **RESOLVED & VERIFIED** |
| **T06** | `android_response_token_match`| R06 | `PlayPurchaseVerifier.verifyPurchase` | Response mang token khác request phải bị từ chối | **FAIL** | **PASS** | `app/src/test/java/com/tscanner/app/VipRound4RegressionTest.kt` | **RESOLVED & VERIFIED** |
| **T07** | `equal_version_different_id` | R08 | `BillingEntitlement.mergeNewerSnapshot` | Cùng token khác ID không được bỏ qua equal-version conflict | **FAIL** | **PASS** | `app/src/test/java/com/tscanner/app/VipRound4RegressionTest.kt` | **RESOLVED & VERIFIED** |
| **T08** | `rejection_no_mint_version` | R07 | `BillingManager.processPurchase` | Rejection không có snapshot không được tự tăng version | **FAIL** | **PASS** | `app/src/test/java/com/tscanner/app/VipRound4RegressionTest.kt` | **RESOLVED & VERIFIED** |
| **T08** | `rejection_sku_mismatch_safe` | R07 | `BillingManager.processPurchase` | Rejection của token X không được thu hồi token Y cùng SKU | **FAIL** | **PASS** | `app/src/test/java/com/tscanner/app/VipRound4RegressionTest.kt` | **RESOLVED & VERIFIED** |
| **T09** | `expired_session_not_ready` | R09 | `PlayPurchaseVerifier.isConfigured` / preflight | Token phiên hết hạn không được coi là purchase-ready | **FAIL** | **PASS** | `app/src/test/java/com/tscanner/app/VipRound4RegressionTest.kt` | **RESOLVED & VERIFIED** |
| **T10** | `empty_catalog_remote_restore`| R01 | `BillingReconciliation.reconcile` | Device catalog rỗng vẫn phải gọi remote `/restore` theo user | **FAIL** | **PASS** | `app/src/test/java/com/tscanner/app/VipRound4RegressionTest.kt` | **RESOLVED & VERIFIED** |
| **T11** | `durable_grant_redundant_ack` | R10 | `BillingManager.processPurchase` | Quyền đã xác thực bền vững ở server không bị block bởi ack SDK | **FAIL** | **PASS** | `app/src/test/java/com/tscanner/app/VipRound4RegressionTest.kt` | **RESOLVED & VERIFIED** |
| **T11** | `control_canceled_active_grant`| R10 | `BillingManager.processPurchase` | Gói canceled-active còn hạn vẫn cấp VIP thành công | **PASS** | **PASS** | `app/src/test/java/com/tscanner/app/VipRound4RegressionTest.kt` | **CONTROL MAINTAINED** |
| **T12** | `drive_worker_cooperative_cancel`| R11 | `GoogleDriveBackupWorker.performBackup` | Coroutine bị hủy sau khi chờ token không được bắt đầu upload | **FAIL** | **PASS** | `app/src/test/java/com/tscanner/app/VipRound4RegressionTest.kt` | **RESOLVED & VERIFIED** |
| **T13** | `full_verification_release_gate`| R12 | Toàn bộ hệ thống | Chạy đủ 928 Android + 89 Backend tests, lint 0 errors | Chờ T01–T12 | **PASS** | Toàn bộ test suites, lint, assemble | **RESOLVED & COMPLETED** |

---

## 2. Kết Quả Kiểm Thử Thực Tế Hoàn Tất

- **Android Test Suite:** **928 / 928 PASS (100% GREEN)**, 0 failures, 0 errors, 0 skipped.
- **Backend Test Suite:** **89 / 89 PASS (100% GREEN)**, 0 failures, 0 errors.
- **Permanent Probes Round 4:** **19 / 19 PASS (100% GREEN)** (10 Backend, 9 Android).
- **Permanent Probes Round 3:** **16 / 16 PASS (100% GREEN)** (7 Backend, 9 Android).
- **Android Lint Quality Gate:** **0 ERRORS**, 757 warnings.
- **Android Assemble Gate:** **BUILD SUCCESSFUL**, APK sinh thành công.

---

## 3. Ranh Giới Nghiệm Thu & Cổng Bên Ngoài (External Gates)

Các bài test trên vận hành hoàn toàn trong môi trường hermetic (fake HTTP, fake SharedPreferences, fake SQLite `:memory:`, synthetic RSA/JWKS, fake BillingClient).
Các cổng môi trường thật (Google Play Console internal track, production Service Account JSON, live Pub/Sub push webhook, real Drive upload) được phân loại **EXTERNAL GATES (NOT RUN)** và không làm gián đoạn việc hoàn tất các acceptance clauses cục bộ:

1. **Google Play Console License Testing:** Chưa thử nghiệm giao dịch thẻ test thật trên thiết bị vật lý qua Play Store Internal Track (**NOT RUN**).
2. **Production Google Identity & Service Account:** Chưa cung cấp file JSON Service Account thật và `GOOGLE_CLIENT_ID` thật trên hạ tầng máy chủ production (**NOT RUN**).
3. **Google Cloud Pub/Sub Webhook:** Chưa kết nối Cloud Pub/Sub topic với endpoint `/api/v1/billing/rtdn` trên public domain (**NOT RUN**).
4. **Persistent Volume Deployment:** Chưa deploy container lên hạ tầng production với Persistent Volume Claim vật lý (**NOT RUN**).
5. **Kiểm Thử Đa Thiết Bị Thực Tế:** Chưa thực hiện kiểm thử khôi phục chéo giữa 2 thiết bị vật lý với các tài khoản Google Play khác nhau (**NOT RUN**).

---

## 4. Kết Luận Chung Vòng 4

Toàn bộ các yêu cầu kỹ thuật và 19 acceptance clauses đã được thực hiện đầy đủ, kiểm chứng bằng kiểm thử thật và đạt trạng thái:
**LOCAL IMPLEMENTED & LOCALLY VALIDATED 100%**.
