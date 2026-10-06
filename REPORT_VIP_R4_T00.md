# Báo Cáo Triển Khai VIP Vòng 4 — Gói T00: Khóa Bằng Chứng, Regression và Checklist Đầy Đủ

**Thời điểm:** 27/09/2026  
**Mục tiêu:** Thiết lập các permanent regression test suites độc lập cho Vòng 4 (`VipRound4RegressionTest.kt` và `round4-regression.test.ts`), lập bảng khung nghiệm thu chi tiết từng acceptance clause (`docs/billing/ROUND4_ACCEPTANCE.md`), tái hiện chính xác 17 lỗi đỏ và 2 đối chứng xanh trên baseline trước khi bắt đầu sửa code production.

---

## 1. File Thay Đổi & Tạo Mới

- `app/src/test/java/com/tscanner/app/VipRound4RegressionTest.kt`: Permanent test suite Android chứa 9 probe độc lập tái hiện các khiếm khuyết R01, R06, R07, R08, R09, R10, R11 và 1 đối chứng kiểm soát (control).
- `backend/billing-verifier/test/round4-regression.test.ts`: Permanent test suite Node.js chứa 10 probe độc lập tái hiện các khiếm khuyết R01, R02, R03, R04, R05 và 1 đối chứng kiểm soát (control).
- `docs/billing/ROUND4_ACCEPTANCE.md`: Bảng khung nghiệm thu chi tiết từng acceptance clause (test ID, production entrypoint, kỳ vọng invariant, trạng thái baseline, đường dẫn bằng chứng và việc còn lại cần xử lý).
- `PROGRESS_VIP_R4_AUTORUN.md`: Nhật ký tiến độ chạy tự động Round 4.
- `REPORT_VIP_R4_T00.md`: Báo cáo nội bộ gói T00.

---

## 2. Bằng Chứng Thực Thi & Kết Quả Tái Hiện Baseline (Red Probes Evidence)

### 2.1. Backend Probes (9 FAIL, 1 PASS)
- Lệnh: `node --experimental-strip-types --test backend/billing-verifier/test/round4-regression.test.ts`
- Kết quả: **9 FAIL, 1 PASS**, thời gian: ~273ms.
  1. `configured googleClientId must enforce matching audience` -> **FAIL** (R02: `config.googleClientId` không được normalize vào `expectedAudience`)
  2. `PubSub must fail closed without audience and service account configuration` -> **FAIL** (R02: route Pub/Sub thiếu config vẫn nhận token)
  3. `PubSub must validate issuer in addition to audience and email` -> **FAIL** (R02: thiếu kiểm tra `iss` của Google accounts ở Pub/Sub)
  4. `control valid RSA user token with explicit audience is accepted` -> **PASS** (Đối chứng)
  5. `V2 SUBSCRIPTION_STATE_PAUSED must not become active paid entitlement` -> **FAIL** (R03: state PAUSED vẫn nhận active paid do chỉ kiểm timestamp)
  6. `V2 SUBSCRIPTION_STATE_UNKNOWN must not become active paid entitlement` -> **FAIL** (R03: state UNKNOWN không bị từ chối)
  7. `V2 product mismatch must not grant requested VIP` -> **FAIL** (R03: product khác product yêu cầu vẫn được cấp VIP do fallback lineItems[0])
  8. `late ACTIVE verification must not resurrect newer EXPIRED snapshot` -> **FAIL** (R04: CAS 1 chiều khiến ACTIVE cũ hồi sinh EXPIRED mới)
  9. `expired receipt owned by another Play hash must not bind to caller` -> **FAIL** (R05: receipt hết hạn bị bind cho caller B trước khi kiểm tra hash của A)
  10. `empty-candidate restore must refresh known lifetime receipt before success` -> **FAIL** (R01 backend: restore `[]` trả cache trọn đời cũ mà không refresh Play khi Play đã refund)

### 2.2. Android Probes (8 FAIL, 1 PASS)
- Lệnh: `.\gradlew.bat :app:testDebugUnitTest --tests com.tscanner.app.VipRound4RegressionTest --offline --console=plain`
- Kết quả: **8 FAIL, 1 PASS**, biên dịch thành công độc lập không cần init script audit, thời gian: ~11s.
  1. `responseDifferentTokenMustBeRejected` -> **FAIL** (R06: response trả token Y khác request token X vẫn được coi là SUCCESS)
  2. `expiredSessionMustNotBePurchaseReady` -> **FAIL** (R09: token phiên có `exp=1` vẫn vượt qua preflight mua hàng)
  3. `emptyCatalogMustReachRemoteRestore` -> **FAIL** (R01 client: danh mục Play rỗng không gọi endpoint server `/restore`)
  4. `rejectionWithoutSnapshotMustNotMintServerVersion` -> **FAIL** (R07: rejection không có snapshot tự động tăng server version lên 11)
  5. `durableVerifiedEntitlementMustSurviveRedundantClientAckFailure` -> **FAIL** (R10: server grant bền vững bị giữ lại vì lỗi acknowledge của SDK client)
  6. `controlCanceledActiveStillGrants` -> **PASS** (Đối chứng)
  7. `equalVersionDifferentIdMustNotResurrectRevokedToken` -> **FAIL** (R08: cùng token nhưng khác ID vượt qua kiểm tra equal-version conflict)
  8. `rejectionOfOldTokenMustNotRevokeAnotherTokenForSameProduct` -> **FAIL** (R07: rejection của token cũ thu hồi token mới còn hạn do match theo SKU)
  9. `canceledDriveWorkerMustNotStartUploadAfterTokenWait` -> **FAIL** (R11: coroutine bị hủy trong khi chờ token vẫn bắt đầu upload lên Google Drive)

---

## 3. Danh Sách Lỗi & Handoff Cho Gói Sau (T01)

- R01–R12 đã được map đầy đủ vào checklist và harness.
- Gói T01 tiếp nhận: Sửa `backend/billing-verifier/src/auth.ts`, `types.ts`, `index.ts`, `test/http-auth.test.ts` để normalize `googleClientId` thành `expectedAudience`, bắt buộc fail-closed ở nhánh Pub/Sub nếu thiếu audience và service-account, kiểm tra `iss` của Google accounts cho Pub/Sub push tokens, và làm xanh 3 auth probes đỏ đầu tiên.
- Trạng thái: T00 hoàn tất. Chuyển thẳng sang T01.
