# Báo cáo VIP R6 — Gói W03: Android parser ràng buộc type/source theo catalog (R06)

## 1. Mục tiêu và phạm vi
- Khắc phục **R06** (Probes **A07**, **A08**):
  - Parser Android (`PlayPurchaseVerifier.kt`) không được tự suy đoán hoặc chấp nhận `productType` sai lệch từ phản hồi backend/server; kiểu sản phẩm phải được xác định dựa trên danh mục (`BillingManager.ALL_SUBSCRIPTION_IDS` -> `subs`, `ALL_INAPP_IDS` -> `inapp`).
  - Kiểm tra nghiêm ngặt `source`: chỉ chấp nhận đúng provider tương ứng (`GOOGLE_PLAY_SUBSCRIPTION` cho subscription, `GOOGLE_PLAY_INAPP` cho in-app). Nếu `source` bị thiếu/rỗng, áp dụng default an toàn theo catalog; nếu `source` có giá trị không hợp lệ hoặc sai lệch provider (như `PROMOTIONAL`, `LEGACY_LOCAL`), phải từ chối (`null`) thay vì âm thầm ép kiểu thành công.
- Bảo toàn toàn bộ 9/9 probe vòng 5 (`VipRound5RegressionTest`) và 99/99 test backend.

## 2. File thay đổi
- `app/src/main/java/com/tscanner/app/utils/billing/PlayPurchaseVerifier.kt`:
  - Trong `parseEntitlementStrict`:
    - Tính toán `expectedProductType` theo danh mục: nếu thuộc `ALL_SUBSCRIPTION_IDS` thì là `"subs"`, thuộc `ALL_INAPP_IDS` thì là `"inapp"`.
    - Kiểm tra `prodType`: nếu rỗng hoặc khác `expectedProductType` -> từ chối (`return null`).
    - Tính toán `expectedSource` theo `isSub`: `GOOGLE_PLAY_SUBSCRIPTION` hoặc `GOOGLE_PLAY_INAPP`.
    - Nếu `rawSource` khác rỗng: `EntitlementSource.valueOf(rawSource)` và kiểm tra `parsedSource == expectedSource`. Nếu sai provider (ví dụ `PROMOTIONAL`) hoặc parse lỗi -> từ chối (`return null`).
  - Trong `restorePurchases`:
    - Xác định `expectedType` từ `productId` dựa trên `ALL_SUBSCRIPTION_IDS` / `ALL_INAPP_IDS` khi tạo `VerificationRequest` để validate đối chứng với từng item entitlement.

## 3. Kết quả kiểm tra
- **Trước khi sửa (W00):**
  - A07 FAIL: `RestoreResult.Success` được trả về dù subscription có `productType: "inapp"`
  - A08 FAIL: `RestoreResult.Success` được trả về dù subscription có `source: "PROMOTIONAL"`
- **Sau khi sửa (W03):**
  - Probes A07, A08 (`VipRound6RegressionTest`):
    - ✔ A07RestoreWrongCatalogTypeMustFail (PASS)
    - ✔ A08RestoreWrongProviderSourceMustFail (PASS)
  - Suite vòng 5 Android (`VipRound5RegressionTest`):
    - **9/9 PASS (100%)**
  - Suite vòng 6 Android (`VipRound6RegressionTest`):
    - 3 PASS (A07, A08, A09) / 6 FAIL (A01–A06 là mục tiêu của các gói tiếp theo W04–W08).

## 4. Contract đã cập nhật
- Bắt buộc tính nhất quán giữa danh mục sản phẩm (catalog SKU) và `productType`/`source`.
- Phản hồi chứa SKU subscription nhưng khai báo `inapp`, hoặc khai báo `source` khác `GOOGLE_PLAY_SUBSCRIPTION` (như `PROMOTIONAL`) đều bị fail closed, bảo vệ tính toàn vẹn của profile entitlement.

## 5. Bàn giao gói tiếp theo
- Chuyển sang **W04**: Restore/sync thuộc scope và operation có thể hủy (R03 - Probe **A04**).
