# Báo cáo Hiện trạng Triển khai Đa ngôn ngữ (Language Rollout Status)

**Ngày cập nhật:** 2026-09-18  
**Lộ trình thực hiện:** `PLAN_8_LANGUAGES_AUTO_SYSTEM_2026-09-18.md` (Gói E01 — E07)  
**Mục tiêu đạt được:** 8 Ngôn ngữ UI Giao diện Tự động theo Hệ thống (Primary-Only Matching) + Tách biệt Độc lập OCR Catalog (36 ngôn ngữ tài liệu).

---

## 1. Tóm tắt Kiến trúc & Chính sách Triển khai

1. **Chế độ Tự động theo Hệ thống (Auto-System Only):**
   - Ứng dụng luôn đồng bộ ngôn ngữ giao diện hiển thị theo ngôn ngữ của hệ điều hành máy người dùng.
   - Đã loại bỏ hoàn toàn bộ chọn ngôn ngữ thủ công trong app (`LanguageSelectionDialog`) và thuộc tính `android:localeConfig` trong `AndroidManifest.xml` (ngăn Android 13+ gán đè per-app locale).
   - `MoreFragment` cung cấp một mục hiển thị trạng thái ngôn ngữ chỉ đọc (Read-Only Status Item) cho biết ngôn ngữ đang áp dụng và giải thích cơ chế tự động.

2. **Chính sách Khớp Ngôn ngữ Chính (Primary-Only Matching Policy):**
   - Chỉ đánh giá mã ngôn ngữ chính (primary language code) của thiết bị (`LocaleListCompat.getDefault().get(0)`).
   - Nếu ngôn ngữ chính thuộc 8 nhóm hỗ trợ (`en, vi, es, pt, fr, in/id, de, ja`), ứng dụng hiển thị ngôn ngữ đó.
   - Nếu ngôn ngữ chính nằm ngoài 8 nhóm trên (ví dụ: `ko, zh, ru, ar, th...`), ứng dụng lập tức fallback về Tiếng Anh (`en`), **tuyệt đối không kiểm tra ngôn ngữ thứ hai** trong danh sách ưu tiên của hệ thống.
   - Hỗ trợ alias 2 chiều `in` <-> `id` cho tiếng Indonesia theo chuẩn BCP-47 / Java Locale.

3. **Tách biệt Độc lập Danh mục OCR (OCR Catalog Decoupling):**
   - Danh mục 36 ngôn ngữ nhận dạng tài liệu OCR (`OcrModels.kt`) hoạt động hoàn toàn độc lập với 8 ngôn ngữ giao diện UI.
   - Người dùng có thể quét và nhận dạng tài liệu tiếng Hàn, tiếng Trung, tiếng Ả Rập, tiếng Thái... bình thường ngay cả khi giao diện app đang hiển thị tiếng Việt, tiếng Anh hay tiếng Pháp.
   - Lựa chọn ngôn ngữ tài liệu OCR được lưu trữ riêng trong `ocr_document_language` preference, không bị ghi đè khi đổi ngôn ngữ hệ thống.

4. **Thu hẹp Tài nguyên 8 Thư mục Canonical:**
   - 34 thư mục ngôn ngữ ngoài phạm vi đã được lưu trữ an toàn tại `locale_archive/res/`.
   - Thư mục tài nguyên hoạt động trong `app/src/main/res/` chỉ gồm đúng 8 thư mục: `values`, `values-vi`, `values-es`, `values-pt`, `values-fr`, `values-in`, `values-de`, `values-ja`.
   - Khóa cứng danh mục tài nguyên ở mức: **625 strings và 7 plurals**.

---

## 2. Ma trận Trạng thái 8 Ngôn ngữ UI

> **Quy ước trạng thái:**
> - `đạt kỹ thuật & nội dung`: 100% key đã dịch, không có thẻ rỗng, không dính lỗi dịch máy, định dạng số nhiều và placeholder đúng chuẩn, kiểm thử tự động pass 100%.

| STT | Mã Locale | Thư mục Res | Tên ngôn ngữ | Trạng thái | Số strings / plurals | Điểm lưu ý đã xử lý |
|:---:|:---:|:---:|:---:|:---:|:---:|:---|
| 1 | `en` | `values/` | English | **Đạt kỹ thuật & nội dung** | 625 strings / 7 plurals | Ngôn ngữ gốc, khóa cứng catalog, trích xuất 4 tiền tố file. |
| 2 | `vi` | `values-vi/` | Tiếng Việt | **Đạt kỹ thuật & nội dung** | 625 strings / 7 plurals | Loại bỏ hoàn toàn hardcoded tiếng Việt trong code Kotlin và layout. |
| 3 | `es` | `values-es/` | Español | **Đạt kỹ thuật & nội dung** | 625 strings / 7 plurals | Dịch ~90 chuỗi tiếng Anh tồn đọng, sửa "Palabra", "Sobresalir", "Rebautizar", "Permiso de conducción", "Afilar", plurals chuẩn. |
| 4 | `pt` | `values-pt/` | Português (Brasil) | **Đạt kỹ thuật & nội dung** | 625 strings / 7 plurals | Dịch ~95 chuỗi tiếng Anh tồn đọng, sửa "Cair pra trás", "Palavra", "Permissão de condução", "Afiado", plurals chuẩn. |
| 5 | `fr` | `values-fr/` | Français | **Đạt kỹ thuật & nội dung** | 625 strings / 7 plurals | Sửa "Mot", "Exceller", "Rebaptiser", "Autorisation de conduire", "Aiguiser", "Retomber", đảo ngữ số đếm, plurals chuẩn. |
| 6 | `id` | `values-in/` | Bahasa Indonesia | **Đạt kỹ thuật & nội dung** | 625 strings / 7 plurals | Dịch ~95 chuỗi tồn đọng, sửa "Kata", "Unggul", "Izin Mengemudi", "Mengasah", "Kilatan", plurals chuẩn. |
| 7 | `de` | `values-de/` | Deutsch | **Đạt kỹ thuật & nội dung** | 625 strings / 7 plurals | Điền thẻ rỗng `menu_rename` ("Umbenennen"), sửa "Wort", "Fahrberechtigung", "Zurückgreifen", bỏ gạch nối số đếm, plurals chuẩn. |
| 8 | `ja` | `values-ja/` | 日本語 | **Đạt kỹ thuật & nội dung** | 625 strings / 7 plurals | Điền `ocr_no_pages_found`, tách dính chuỗi `ocr_recognizing_page_progress`, sửa "運転許可", "後退する", "切れ味", dùng lượng từ 枚/件. |

---

## 3. Nhật ký Hoàn thành các Gói Thực hiện (E01 — E07)

### Gói E01 — Bộ phân giải ngôn ngữ chính & Unit Test (Hoàn thành: 2026-09-18)
- **Files sửa/tạo:** `AppLanguageManager.kt`, `AppLanguageManagerTest.kt`
- **Nội dung:** Xây dựng thuật toán Primary-Only Matching 8 ngôn ngữ, hỗ trợ alias `in` <-> `id`. Viết 16 kịch bản unit test bao quát mọi trường hợp fallback, biến thể vùng miền và danh sách ưu tiên.

### Gói E02 — Tách biệt tên tài liệu OCR khỏi UI (Hoàn thành: 2026-09-18)
- **Files sửa/tạo:** `OcrModels.kt`, `TesseractOcrHelper.kt`, `PaddleOcrEngine.kt`, `OcrRoutingResolver.kt`
- **Nội dung:** Tách toàn bộ 36 ngôn ngữ tài liệu OCR ra khỏi UI catalog, lưu trữ độc lập trong `OcrModels.kt`. Viết unit test `OcrRoutingTest.kt`.

### Gói E03 — Chế độ tự động theo máy & Dọn dẹp điều hướng (Hoàn thành: 2026-09-18)
- **Files sửa:** `TScannerApplication.kt`, `MoreFragment.kt`, `AndroidManifest.xml`
- **Nội dung:** Thực hiện migration v3 idempotent xóa sạch preference cũ, biến mục ngôn ngữ trong `MoreFragment` thành thẻ trạng thái chỉ đọc, gỡ bỏ `android:localeConfig`.

### Gói E04 — Thu hẹp tài nguyên về 8 ngôn ngữ (Hoàn thành: 2026-09-18)
- **Files chuyển:** 34 thư mục sang `locale_archive/res/`, cập nhật `locales_config.xml` và `app/build.gradle`.
- **Nội dung:** Giữ lại đúng 8 thư mục tài nguyên. Cập nhật validator CI `scripts/audit_localization.py` để kiểm soát chặt chẽ 8 ngôn ngữ.

### Gói E05 — Khóa cứng danh mục & Hoàn thiện nguồn Anh - Việt (Hoàn thành: 2026-09-18)
- **Files sửa:** `values/strings.xml`, `values-vi/strings.xml`, `ManagedFileItem.kt`, `OcrResultActivity.kt`, `IdCardComposeActivity.kt`
- **Nội dung:** Trích xuất 4 tiền tố file (`default_doc_filename_prefix`...), dọn dẹp sạch hardcoded tiếng Việt trong code Kotlin, khóa cứng catalog ở 625 strings và 7 plurals.

### Gói E06a — Sửa và kiểm duyệt bản dịch tiếng Tây Ban Nha (`values-es`) (Hoàn thành: 2026-09-18)
- **Files sửa:** `values-es/strings.xml`
- **Nội dung:** Dịch toàn bộ ~90 chuỗi tiếng Anh tồn đọng, sửa lỗi dịch máy, chuẩn hóa plurals `one` / `other`.

### Gói E06b — Sửa và kiểm duyệt bản dịch tiếng Bồ Đào Nha (`values-pt`) (Hoàn thành: 2026-09-18)
- **Files sửa:** `values-pt/strings.xml`
- **Nội dung:** Chuẩn hóa theo phương ngữ Bồ Đào Nha Brazil (pt-BR), sửa toàn diện lỗi dịch máy và plurals `one` / `other`.

### Gói E06c — Sửa và kiểm duyệt bản dịch tiếng Pháp (`values-fr`) (Hoàn thành: 2026-09-18)
- **Files sửa:** `values-fr/strings.xml`
- **Nội dung:** Rà soát ngữ pháp tiếng Pháp, sửa các từ sai lệch ngữ cảnh nghiêm trọng, chuẩn hóa plurals.

### Gói E06d — Sửa và kiểm duyệt bản dịch tiếng Indonesia (`values-in`) (Hoàn thành: 2026-09-18)
- **Files sửa:** `values-in/strings.xml`
- **Nội dung:** Dịch ~95 chuỗi tồn đọng, chuẩn hóa thuật ngữ scan/export theo Bahasa Indonesia chuẩn.

### Gói E06e — Sửa và kiểm duyệt bản dịch tiếng Đức (`values-de`) (Hoàn thành: 2026-09-18)
- **Files sửa:** `values-de/strings.xml`
- **Nội dung:** Khắc phục thẻ rỗng `menu_rename`, loại bỏ lỗi dùng dấu nối gạch ngang `-` trong số đếm, chuẩn hóa plurals `one` / `other`.

### Gói E06f — Sửa và kiểm duyệt bản dịch tiếng Nhật (`values-ja`) (Hoàn thành: 2026-09-18)
- **Files sửa:** `values-ja/strings.xml`
- **Nội dung:** Khắc phục lỗi thẻ rỗng `ocr_no_pages_found` và dính chuỗi `ocr_recognizing_page_progress`, sửa các lỗi dịch máy ngô nghê ("運転許可", "切れ味", "後退する"), chuẩn hóa lượng từ 枚 / 件.

### Gói E07 — Kiểm thử tổng thể & Hướng dẫn phát hành (Hoàn thành: 2026-09-18)
- **Files sửa:** `PluralsAndFormattingTest.kt`, `LANGUAGE_ROLLOUT_STATUS.md`, `walkthrough.md`
- **Nội dung:** Mở rộng unit test plurals bao quát 100% 8 ngôn ngữ, xác thực thành công App Bundle (`bundleDebug`), hoàn thiện tài liệu phát hành.

---

## 4. Báo cáo Kết quả Kiểm thử Toàn diện (Quality Gates)

| Cổng kiểm thử | Lệnh thực thi | Kết quả | Chi tiết |
|---|---|:---:|---|
| **CI Localization Validator** | `python scripts/audit_localization.py` | **PASS (exit 0)** | 625 strings, 7 plurals, 7 localized folders + base, 0 key thừa/thiếu, 0 placeholder mismatch, 0 hardcoded. |
| **Validator Unit Tests** | `python scripts/test_audit_localization.py` | **PASS (exit 0)** | 12/12 unit tests pass in 0.29s. |
| **Android Unit Tests** | `./gradlew :app:testDebugUnitTest` | **PASS (exit 0)** | Toàn bộ 130+ unit tests pass 100% (bao gồm `PluralsAndFormattingTest`, `AppLanguageManagerTest`, `OcrRoutingTest`...). |
| **Debug Assembly & AAPT2** | `./gradlew :app:assembleDebug` | **PASS (exit 0)** | BUILD SUCCESSFUL, biên dịch tài nguyên XML và layout hoàn toàn sạch lỗi. |
| **Android App Bundle (AAB)** | `./gradlew :app:bundleDebug` | **PASS (exit 0)** | BUILD SUCCESSFUL, đóng gói AAB thành công và tương thích hoàn toàn việc phân phối split apk ngôn ngữ. |

---

## 5. Hướng dẫn Phát hành & Kiểm thử Thực tế trên Thiết bị

### A. Kịch bản Kiểm thử Thủ công trên Thiết bị (Manual QA Checklist)
1. **Kiểm thử Ngôn ngữ Hỗ trợ:**
   - Đổi ngôn ngữ máy sang lần lượt 8 ngôn ngữ: Tiếng Việt, English, Español, Português, Français, Bahasa Indonesia, Deutsch, 日本語.
   - Mở ứng dụng: Kiểm tra giao diện màn hình chính, thanh điều hướng dưới, nhãn nút bấm, màn hình More (thẻ trạng thái đọc đúng ngôn ngữ máy).
2. **Kiểm thử Ngôn ngữ Fallback:**
   - Đổi ngôn ngữ máy sang tiếng Hàn (`ko`), tiếng Trung (`zh`), tiếng Nga (`ru`), tiếng Ả Rập (`ar`), hoặc tiếng Thái (`th`).
   - Mở ứng dụng: Xác nhận ứng dụng hiển thị Tiếng Anh (`en`) hoàn toàn, không hiển thị ngôn ngữ thứ hai trong danh sách máy.
3. **Kiểm thử Độc lập Nhận dạng OCR:**
   - Đặt ngôn ngữ máy là Tiếng Việt (`vi`).
   - Mở công cụ OCR, nhấn chọn ngôn ngữ tài liệu -> Chọn Tiếng Nhật (`ja`) hoặc Tiếng Trung (`zh`).
   - Tiến hành quét: Xác nhận OCR tải model và nhận dạng chính xác ký tự Nhật/Trung mà không làm thay đổi ngôn ngữ giao diện tiếng Việt.
4. **Kiểm thử Plurals & Số đếm:**
   - Chọn 1 file -> Hiển thị dạng số ít (ví dụ: `1 Datei`, `1 arquivo`, `1 file`).
   - Chọn nhiều file -> Hiển thị dạng số nhiều tương ứng theo từng ngôn ngữ (ví dụ: `2 Dateien`, `2 arquivos`, `2件のファイル`).

### B. Lưu ý khi Build Release và Đăng tải Google Play Store
1. **Lệnh tạo bản phát hành Release Bundle:**
   ```bash
   ./gradlew :app:bundleRelease
   ```
2. **Lưu ý chữ ký (Signing):**
   - Đảm bảo cấu hình file `keystore.properties` hoặc biến môi trường `KEYSTORE_PASSWORD`, `KEY_ALIAS` chính xác trước khi build release.
   - Không commit file keystore cá nhân lên git repository.
3. **Mô tả trên Google Play Store:**
   - Ghi rõ thông tin hỗ trợ 8 ngôn ngữ giao diện tự động: English, Tiếng Việt, Español, Português (Brasil), Français, Bahasa Indonesia, Deutsch, 日本語.
   - Nêu rõ tính năng OCR nhận dạng đa ngôn ngữ (hỗ trợ tới 36 ngôn ngữ tài liệu).
