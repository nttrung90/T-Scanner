# BÁO CÁO TỔNG KẾT VÀ BÀN GIAO VIP VÒNG 5 (VIP ROUND 5 FINAL REPORT)
*Ngày hoàn tất: 30/09/2026*  
*Người thực hiện: Gemini (Antigravity) — Chế độ Autorun tuần tự U00 → U11*

---

## 1. Tóm tắt điều hành (Executive Summary)

Đợt rà soát và khắc phục VIP Vòng 5 được thực hiện tự động và tuần tự từ gói **U00 đến U11** theo đúng kế hoạch `PLAN_VIP_GEMINI_ANTIGRAVITY_ROUND5_AUTORUN_2026-09-30.md` và tài liệu đối chiếu `RECHECK_VIP_FULL_ROUND5_2026-09-30.md`.

- **Mục tiêu đạt được**: Sửa đổi toàn diện, triệt để 8 nhóm lỗi **F01–F08** xuyên suốt hai tầng Backend và Android Client.
- **Tiến trình**:
  - **Khởi đầu (U00)**: Thiết lập bộ regression test cố định vĩnh viễn (`round5-regression.test.ts` và `VipRound5RegressionTest.kt`). Xác nhận Red Baseline: **14 FAIL / 2 PASS** khớp 100% với tài liệu kiểm toán độc lập.
  - **Thực thi các gói U01–U08**: Lần lượt giải quyết từng nhóm lỗi F01–F08, mỗi gói đều có báo cáo nghiệm thu riêng (`REPORT_VIP_R5_U01.md` đến `REPORT_VIP_R5_U08.md`).
  - **Kiểm định độc lập (U09)**: **16/16 Probes XANH (100%)**; toàn bộ 96/96 tests backend XANH; toàn bộ suite unit test Android XANH; `lintDebug` và `assembleDebug` BUILD SUCCESSFUL.
  - **Minh bạch môi trường thật (U10)**: Phân loại trung thực các kịch bản phụ thuộc môi trường bên ngoài (`BLOCKED_EXTERNAL` / `NOT_RUN`), tuyệt đối không giả mạo kết quả.
  - **Bảo toàn dữ liệu**: Giữ nguyên toàn bộ uncommitted changes của người dùng; không commit, không push, không deploy, không thực hiện giao dịch tiền thật.

---

## 2. Ma trận đối chiếu lỗi và kết quả nghiệm thu (Acceptance Matrix)

| Nhóm lỗi | Probe ID | Mô tả lỗi gốc | File sửa đổi | Biện pháp kỹ thuật đã áp dụng | Kết quả Baseline (U00) | Kết quả Hiện tại (U11) |
|---|---|---|---|---|:---:|:---:|
| **F01** (P1) | **B01** | First-binding query late active làm sống lại token đã EXPIRED trong DB. | `storage/types.ts`<br>`storage/sqliteDriver.ts`<br>`verifier.ts` | Phân biệt rõ `expectedVersion = null` (bắt buộc absent) với `undefined`. Trả về `casConflict: true` khi có xung đột thay vì nuốt vào `staleIgnored`. Bounded retry loop (tối đa 5 lần) trong `verifyPurchase`. | **FAIL** | **PASS** ✔ |
| **F01** (P1) | **B02** | Xung đột đồng thời nuốt phản hồi EXPIRED và trả về cache ACTIVE. | `storage/sqliteDriver.ts`<br>`verifier.ts` | Áp dụng CAS guard trên cả 2 chiều trạng thái; khi CAS conflict, refetch snapshot mới nhất và truy vấn lại Google Play API. | **FAIL** | **PASS** ✔ |
| **F01** (P1) | **B07** | Late RTDN ghi đè trạng thái hết hạn mới hơn từ verify do thiếu CAS xuyên tác vụ. | `rtdnHandler.ts` | Chụp `expectedVersion` trước khi truy vấn Play API; áp dụng CAS guard `{ eventTimeMillis, expectedVersion }`; bounded retry loop (tối đa 3 lần). Giữ event un-processed khi conflict thất bại. | **FAIL** | **PASS** ✔ |
| **F02** (P1) | **B03** | Google Play V2 trả `ON_HOLD` nhưng adapter hạ cấp thành `paymentState = 0` và verifier trả PENDING, không hủy VIP. | `googlePlayClient.ts`<br>`verifier.ts` | Đánh giá `subscriptionState` chuẩn enum chuỗi của V2 trước V1. Không gán `paymentState = 0` cho `ON_HOLD`. Lưu trạng thái `ON_HOLD`/`PAUSED` ngưng quyền VIP. | **FAIL** | **PASS** ✔ |
| **F02** (P1) | **B04** | V2 thiếu `subscriptionState` nhưng vẫn cấp VIP ACTIVE qua đường fallback autoRenewing. | `verifier.ts`<br>`googlePlayClient.ts` | Validate nghiêm ngặt lifecycle V2; thiếu subscriptionState hoặc không nằm trong danh sách active states sẽ không được cấp quyền active. | **FAIL** | **PASS** ✔ |
| **F03** (P1) | **B05** | Restore purchases bị metadata rác từ client ghi đè SKU của token đã biết, làm bỏ qua refresh REVOKED. | `verifier.ts` | Giữ bản ghi DB (`knownEntitlements`) làm authoritative cho token đã biết. Không cho phép payload client ghi đè SKU/productType của token đã tồn tại trong DB. | **FAIL** | **PASS** ✔ |
| **F03** (P1) | **B06** | Đối chứng: Restore rỗng làm mới refund đúng khi không có metadata xung đột. | `verifier.ts` | Bảo toàn tính nhất quán khi danh sách purchases từ client rỗng. | **PASS** | **PASS** ✔ |
| **F04** (P1) | **A01** | Restore Android chấp nhận snapshot owner B dù request của owner A (`dummyReq` tự chế từ response). | `PlayPurchaseVerifier.kt` | Ràng buộc `responseOwner`: từ chối với `OWNERSHIP_CONFLICT` nếu owner trả về không khớp với `request.ownerAppUserId`. | **FAIL** | **PASS** ✔ |
| **F04** (P1) | **A02** | Restore Android chấp nhận SKU ngoài catalog (`unrelated-product`). | `PlayPurchaseVerifier.kt` | Kiểm tra allowlist `BillingManager.ALLOWED_PRODUCT_IDS.contains(...)` trong cả `parseRestoreResponse` và `parseEntitlementStrict`. | **FAIL** | **PASS** ✔ |
| **F04** (P1) | **A03** | Item restore có state `UNKNOWN` bị bỏ qua âm thầm, đổi thành `Success` với snapshot rỗng. | `PlayPurchaseVerifier.kt` | Nếu có bất kỳ entitlement nào bị lỗi / malformed, không nuốt lỗi mà trả về `RestoreResult.TransientError`. | **FAIL** | **PASS** ✔ |
| **F04** (P1) | **A04** | HTTP 400 nhưng trả body SUCCESS vẫn được Android verifier chấp nhận. | `PlayPurchaseVerifier.kt` | Kiểm tra nghiêm ngặt `statusCode == 200`. Mọi mã lỗi HTTP đều bị từ chối thích hợp, không nhận payload SUCCESS giả mạo. | **FAIL** | **PASS** ✔ |
| **F04** (P1) | **A07** | Đối chứng: Snapshot hợp lệ được parse thành Success. | `PlayPurchaseVerifier.kt` | Parse thành công các snapshot hợp chuẩn contract. | **PASS** | **PASS** ✔ |
| **F05** (P1) | **A05** | Late restore trả về sau khi chuyển tài khoản vẫn ghi đè store của tài khoản cũ. | `BillingReconciliation.kt` | Chụp `initialUserId` & `initialGen` trước khi suspend; re-check `operationContext.isStale` ngay sau khi await `restorePurchases`. Bỏ qua toàn bộ side effect nếu phiên thay đổi. | **FAIL** | **PASS** ✔ |
| **F06** (P2) | **A06** | Restore báo thành công dựa trên response mạng thay vì snapshot đã merge/commit trong store. | `BillingReconciliation.kt` | Sử dụng `store.applySnapshotTyped` và lấy `committedSnapshot` đã merge làm Single Source of Truth duy nhất để quyết định trạng thái và phát sự kiện `Restored`. | **FAIL** | **PASS** ✔ |
| **F07** (P1) | **A08** | Restore `PARTIAL` lưu VIP vào store nhưng không project vào user profile cho Watermark & Drive. | `BillingReconciliation.kt` | Đồng bộ hóa `AppAuthManager.applyEntitlementSnapshot` cho cả `Success` và `Partial`. Chỉ phát `Restored` khi `committedSnapshot.isVipActive() == true`. | **FAIL** | **PASS** ✔ |
| **F08** (P2) | **A09** | Session ID token hết hạn bị biến thành lỗi dịch vụ không thể retry (`canRetry = false`). | `PlayPurchaseVerifier.kt`<br>`VipUpgradeDialog.kt`<br>`VipPurchaseActionCoordinator.kt` | Tách biệt cấu hình backend (`isBackendConfigured`) và tính hợp lệ phiên (`isAuthReady`). Khi token hết hạn, điều hướng sang flow đăng nhập lại (`onRequestSignIn`/`onShowSignInPrompt`) thay vì báo lỗi dịch vụ. | **FAIL** | **PASS** ✔ |

---

## 3. Tổng hợp danh sách File đã chỉnh sửa

### Backend (`backend/billing-verifier/`):
1. `src/storage/types.ts`: Bổ sung `expectedVersion: number | null`, `expectedAbsent?: boolean`, `casConflict?: boolean` cho `BindResult`.
2. `src/storage/sqliteDriver.ts`: Hiện thực hóa CAS guard kiểm tra `expectedVersion === null` (bắt buộc absent) và `casConflict: true` khi version mismatch.
3. `src/googlePlayClient.ts`: Validate enum chuỗi Subscriptions V2 strictly, không hạ cấp `paymentState = 0` cho `ON_HOLD`.
4. `src/verifier.ts`: Chụp version snapshot trước khi gọi Google Play, bổ sung bounded CAS retry loop (5 lần), bảo toàn authoritative record trong `restorePurchases`.
5. `src/rtdnHandler.ts`: Chụp `expectedVersion` và áp dụng CAS guard cho RTDN, bọc trong bounded retry loop (3 lần), không làm mất event khi conflict.
6. `test/googlePlayTransport.test.ts`: Cập nhật assertion cho `ON_HOLD`.
7. `test/round5-regression.test.ts`: Bộ kiểm thử hồi quy 7 probe backend cố định (B01–B07).

### Android Client (`app/`):
1. `src/main/java/com/tscanner/app/utils/billing/PlayPurchaseVerifier.kt`: Kiểm tra HTTP status 200, ràng buộc owner response, SKU catalog allowlist, từ chối item hỏng, tách biệt `isBackendConfigured` và `isAuthReady`, công khai `isTokenExpired`.
2. `src/main/java/com/tscanner/app/utils/billing/BillingReconciliation.kt`: Chốt chặn stale session guard sau await `restorePurchases`, dùng `committedSnapshot` từ `applySnapshotTyped` làm nguồn chân lý, project `Partial` sang `AppAuthManager`.
3. `src/main/java/com/tscanner/app/ui/dialogs/VipUpgradeDialog.kt`: Phân giải `resolveUpgradeAction` sang `RequestSignIn`/`ShowSignInRequiredPrompt` khi session token hết hạn.
4. `src/main/java/com/tscanner/app/utils/VipPurchaseActionCoordinator.kt`: Bổ sung kiểm tra token hết hạn để điều hướng re-authentication kịp thời.
5. `src/test/java/com/tscanner/app/VipRound5RegressionTest.kt`: Bộ kiểm thử hồi quy 9 probe Android cố định (A01–A09).

---

## 4. Báo cáo các Gate môi trường thật (U10 Audit)

Theo đúng quy định của vòng kiểm toán, các hạng mục phụ thuộc môi trường bên ngoài được phân loại trung thực:
- **Google Play License Tester**: `BLOCKED_EXTERNAL` (cần tài khoản cấu hình trên Play Console).
- **OAuth Google Sign-In SDK thật**: `BLOCKED_EXTERNAL` (cần Google Play Services trên thiết bị thật).
- **Cloud Pub/Sub Push Endpoint trực tiếp**: `BLOCKED_EXTERNAL` (cần Google Cloud subscription thật; đã pass 100% trong bộ test mô phỏng).
- **Thiết bị vật lý / ADB daemon**: `NOT_RUN` (môi trường host không có thiết bị thật kết nối).

---

## 5. Kết luận bàn giao
Hệ thống T-Scanner VIP Billing Verifier và Android Client đã hoàn thành toàn bộ mục tiêu Vòng 5:
- **16/16 Probe hồi quy Vòng 5: ĐẠT 100%**.
- **96/96 Backend Unit & Integration Tests: ĐẠT 100%**.
- **100% Android Unit Tests: ĐẠT 100%**.
- **Toàn bộ mã nguồn biên dịch sạch, không có lỗi lint hoặc build blocker**.
- **Toàn bộ mã nguồn người dùng được bảo toàn nguyên vẹn**.
