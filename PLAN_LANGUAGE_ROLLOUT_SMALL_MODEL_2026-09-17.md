# Kế hoạch đa ngôn ngữ theo từng đợt — dành cho mô hình nhỏ

Ngày: 2026-09-17. Trạng thái: **kế hoạch, chưa triển khai sửa code**.

## 1. Mục tiêu và ranh giới

Hoàn thiện Việt–Anh làm nền; sửa locale/OCR một lần cho toàn ứng dụng; sau đó nghiệm thu từng ngôn ngữ bổ sung. Không dịch hoặc sửa đồng loạt 37 ngôn ngữ trong một lượt.

Kế hoạch này thay thứ tự triển khai của `PLAN_MULTILINGUAL_2026-09-16.md`. Dùng `REVIEW_MULTILINGUAL_2026-09-17.md` làm danh sách bằng chứng R01–R10; đọc lại file thực tế trước khi sửa vì working tree có thể đã thay đổi.

Các quyết định đã chốt để mô hình nhỏ không phải tự suy đoán:

- Giữ bản dịch, 37 lựa chọn hiện tại và preference của người dùng. **Không xóa, ẩn, loại khỏi APK/AAB hoặc ép người dùng ngôn ngữ khác về English** trong đợt này.
- Thu hẹp *phạm vi cam kết chất lượng*: Việt–Anh phải đạt toàn bộ tiêu chí trước; ngôn ngữ còn lại được ghi nội bộ là chưa nghiệm thu. Không thêm nhãn kỹ thuật vào UI và không tự sửa nội dung Store.
- Không đổi số lượng ngôn ngữ để làm tests xanh. Các resources còn đóng gói vẫn phải hợp lệ và qua Lint, dù chưa được nghiệm thu nội dung.
- Ngôn ngữ UI và ngôn ngữ tài liệu OCR độc lập. Người dùng UI Việt/Anh vẫn có thể quét tài liệu Nhật/Trung nếu engine hiện có hỗ trợ. Giảm phạm vi QA UI không được giảm năng lực OCR đã có bằng cách vô tình lọc chung danh sách.
- Không thêm engine/model, dịch vụ dịch đám mây, dependency lớn, billing hoặc kiến trúc mới không cần thiết. Không gửi tài liệu người dùng ra ngoài.
- Không sửa thuật toán ảnh/crop/draft/file IO ngoài chỗ cần bảo toàn trạng thái khi đổi locale. Không ghi đè thay đổi chưa commit, không commit/push/phát hành.

## 2. Cách giao việc

Một lượt = một gói hoặc một tiểu gói. Tối đa khoảng 3–5 file logic liên quan; resource/test đi kèm được phép, nhưng nếu diff quá lớn phải chia tiếp. Không mở nhiều tác vụ cùng sửa `strings.xml`, manager hay helper.

Quy trình bắt buộc mỗi lượt:

1. Đọc mục gói, bằng chứng Rxx và `git diff` của file sắp sửa; xác nhận lỗi vẫn còn.
2. Ghi phạm vi, sửa nhỏ nhất đủ đạt hành vi mục tiêu. Nếu đã được sửa đúng thì xác minh rồi đánh dấu, không viết lại.
3. Với lỗi logic, thêm regression test chứng minh hành vi mong muốn và bắt được code cũ; không assert lại hành vi sai để test pass.
4. Chạy kiểm tra gói. Không coi lỗi cache/thiết bị là lỗi code; không coi kiểm tra chưa chạy là pass.
5. Ghi kết quả vào `LANGUAGE_ROLLOUT_STATUS.md`: gói, file, lệnh, kết quả, chưa kiểm tra, việc tiếp theo. Không sửa bản audit lịch sử.

## 3. Các mốc nghiệm thu

| Mốc | Phạm vi | Điều kiện qua |
|---|---|---|
| A — Nền tài nguyên hợp lệ | Tất cả locale đang đóng gói | Lint không còn lỗi; validator không báo quá phạm vi; thiếu quantity được sửa đúng |
| B — Cơ chế ngôn ngữ/OCR đúng | Toàn ứng dụng | Locale system/migration đúng; chọn ngôn ngữ tài liệu được; routing/error/export không báo thành công giả |
| C — Việt–Anh hoàn chỉnh | Toàn bộ UI, kể cả lỗi/empty state | Nội dung nhất quán, không hardcode ngoài allowlist, kiểm thử thiết bị đạt |
| D — Thêm một locale | Một locale và alias của nó | Kế thừa A/B, review dịch + layout + plural + workflow đạt như Việt–Anh |

Không gọi mốc C là “37 ngôn ngữ hoàn chỉnh”. Mốc D lặp lại từng locale; không chọn thứ tự dựa trên suy đoán về lượng người dùng.

## 4. Đợt A — Sửa nền tài nguyên và kiểm tra

### A0 — Chốt baseline và bảng theo dõi

**File đọc:** báo cáo R01–R10, `app/build.gradle`, script audit, tests locale/OCR, diff hiện tại. **File tạo:** `LANGUAGE_ROLLOUT_STATUS.md`.

- Chạy audit, unit tests, debug assemble và Lint với môi trường phù hợp.
- Baseline của lượt review trước: 575 strings, 7 plurals, 68 tests pass; assemble pass; Lint 84 errors/2064 warnings. Đây là kết quả đã ghi nhận, **không giả định vẫn đúng sau thay đổi mới**.
- Bảng trạng thái locale: `chưa nghiệm thu`, `đang xử lý`, `đạt kỹ thuật`, `đạt nội dung và thiết bị`; mỗi mục phải có bằng chứng/ngày. Việt–Anh là mục tiêu ưu tiên, chưa tự gắn nhãn đã đạt.

**Nghiệm thu:** có baseline mới và danh sách còn lỗi; không sửa production code ở A0.

### A1 — Sửa checker để không che lỗi (R10)

**File:** `scripts/audit_localization.py`, test Python mới cho validator.

- Kiểm tra duplicate của base/locales/plurals, tồn tại `other`, placeholder theo index/kiểu trong từng item; xử lý hợp lệ trường hợp ngôn ngữ bỏ số khỏi câu số ít/số đôi nếu callsite vẫn truyền tham số.
- Đối chiếu catalog/config/resources qua canonical tag và alias, không chỉ đếm 37. Không phải mỗi folder là một ngôn ngữ.
- Không tự duy trì bảng ngữ pháp plural bằng suy đoán: dùng Android Lint hiện tại làm gate quantity theo locale. Báo rõ phần script kiểm tra và phần cần Lint.
- Danh sách literal Kotlin và chuỗi giống base là danh sách review có allowlist/lý do, không phải mọi mục đều lỗi. Không mặc định yêu cầu hai bản dịch phải khác nhau.
- Bỏ tuyên bố “100% localization integrity”; exit code phản ánh đúng các kiểm tra thực sự chạy.

**Tests:** fixture thiếu key, base duplicate, thiếu `other`, sai `%1$d` thành `%1$s`, config/alias không khớp; fixture hợp lệ chứa thương hiệu giống base và format literal `%`.

**Nghiệm thu:** checker bắt được fixture lỗi; Lint vẫn phải chạy độc lập. A1 có thể còn đỏ do lỗi dữ liệu thực, không “sửa” checker để bỏ qua.

### A2 — Sửa plural của tài nguyên đang đóng gói (R01)

**File:** chỉ nhóm `<plurals>` ở locale có lỗi và alias liên quan; không dịch lại toàn file.

- Mỗi lượt xử lý một locale cùng alias. Theo Lint hiện tại; danh sách cũ có ar/cs/hr/lt/lv/pl/ro/sk/sl/sr-Latn/sr/tl.
- Bổ sung quantity mà Android yêu cầu, với câu đúng ngữ pháp. Không sao chép `other` cho tất cả dạng chỉ để vượt Lint. Nếu không chắc bản dịch, ghi cần người thông thạo duyệt, không đánh dấu đạt nội dung.
- Rà 35 cảnh báo MissingQuantity của baseline, phân biệt quantity thừa/không dùng và quantity thiếu. Không thêm baseline/suppression để giấu lỗi.

**Nghiệm thu:** process resources pass, checker pass, Lint không còn error; các cảnh báo localization còn lại được phân loại. Dữ liệu plural có test 0/1/2/3/11/100; không bắt mọi ngôn ngữ dùng bộ quantity giống English.

## 5. Đợt B — Sửa cơ chế chung

### B1 — Locale hệ thống và migration (R08/R09)

Chia hai lượt để tránh sửa vòng đời cùng lúc quá nhiều.

**B1a — nguồn locale:** `utils/AppLanguageManager.kt`, `ui/dialogs/LanguageSelectionDialog.kt`, tests.

- Tách `system locale list`, `app override`, `resolved UI locale`; system không đọc từ Activity context đã override.
- Tách “không có locale được hỗ trợ” khỏi kết quả fallback English để mô tả hệ thống chính xác.
- Giữ System là override rỗng, explicit không bị đổi khi system đổi. Tôn trọng danh sách ưu tiên và script/tag hiện có, không rút mọi Chinese/Serbian về cùng script.

**B1b — migration:** manager, `TScannerApplication`, điểm lifecycle Activity phù hợp, tests.

- API 33+ truy cập framework LocaleManager qua context thích hợp hoặc migrate sau khi delegate sẵn sàng; không gọi setter no-op trong Application rồi xóa preference.
- API cũ phối hợp thời điểm restore AppCompat auto-storage. Ưu tiên lựa chọn mới/framework đã tồn tại; import legacy đúng một lần nếu cần.
- Marker chỉ được lưu sau khi chuyển thành công; giữ retry an toàn khi gián đoạn. Không thêm nguồn lưu locale song song mới.

**Nghiệm thu thiết bị:** API 26/32 và 33/36; system en/app ja; system ru; `[ru,fr]`; legacy-only vi; framework ja khác legacy vi; reset System; restart/process death. Không mất draft khi recreation. Không có thiết bị thì ghi chưa nghiệm thu B1, không chặn làm gói độc lập khác.

### B2 — Sửa bảng năng lực OCR trước khi mở selector (R03)

**File:** `utils/OcrModels.kt`, phần routing `TextRecognitionHelper.kt`, `OcrRoutingTest.kt`.

- Tạo danh sách ngôn ngữ tài liệu OCR được hỗ trợ dựa trên engine/model hiện có; không lấy mọi mã lạ làm Latin. UI language catalog không quyết định capability OCR.
- Tesseract explicit: vi→vie, en→eng; ngôn ngữ khác không có model tương ứng phải báo không tương thích. French/German dùng ML Kit Auto theo năng lực hiện có, không trả vie.
- Nhật/Hàn/Hindi dùng recognizer chuyên biệt; Chinese dùng model đã xác minh. Không tự nhận script/biến thể chưa chứng minh; không fallback Chinese sang English rồi coi là thành công đầy đủ.
- Paddle không được nhận Vietnamese chỉ vì vi/en chung enum. Chốt support riêng theo model/assets và tài liệu model sẵn có; chưa xác minh thì không quảng bá capability đó.
- Thay tests đang assert `fr -> vie`, `ru -> Latin`; thêm mã trống/lạ, engine lạ, alias/script và tổ hợp incompatible. Dùng Locale.ROOT cho normalize kỹ thuật.

**Nghiệm thu:** bảng ngôn ngữ × engine có test; mã unsupported không được chạy; labels mô tả đúng engine/model thực tế. Chưa thêm model mới.

### B3 — Nối lựa chọn ngôn ngữ tài liệu vào UI (R02)

**Phụ thuộc:** B2. **File:** settings/dialog OCR và layout, helper preference, strings en/vi; chia UI và entrypoint thành hai lượt.

- Hiển thị hai lựa chọn riêng: ngôn ngữ tài liệu, engine. Auto nghĩa là chọn engine theo ngôn ngữ tài liệu đã chọn; không tuyên bố tự phát hiện ngôn ngữ ảnh.
- Gọi setter thực sự, lưu và đọc lại, hiển thị lựa chọn. Sau lựa chọn ban đầu, đổi UI không làm đổi ngôn ngữ tài liệu.
- Lần đầu nếu UI được OCR hỗ trợ thì gợi ý và persist lựa chọn theo chính sách rõ ràng. Nếu UI chưa hỗ trợ, yêu cầu chọn ngôn ngữ tài liệu trước khi OCR; không ngầm fallback vi/en.
- Dùng snapshot OcrRequest duy nhất cho cả job nhiều trang. Entry points Home/Files/Viewer/Tools phải cùng chính sách.
- Ngôn ngữ không hỗ trợ có thể không xuất hiện trong selector OCR hoặc xuất hiện disabled với giải thích; không ảnh hưởng selector UI.

**Nghiệm thu:** UI vi + OCR ja; UI en + OCR vi; UI ar + OCR en; restart giữ lựa chọn; đổi UI không đổi OCR; không thay request giữa các trang.

### B4 — Structured result thực sự đi đến UI (R04)

**Phụ thuộc:** B2/B3. Chia B4a helper, B4b các caller từng màn hình.

- B4a: engine wrapper phân biệt Success/NoText/Unsupported/Incompatible/ModelUnavailable/Failure; không `success("")` trong mọi failure listener. Bảo toàn coroutine cancellation và lifecycle job.
- ModelUnavailable phải được tạo ở nhánh lỗi/availability đúng, có thông điệp thử lại/tải model phù hợp; lỗi khác vẫn là Failure. Không phân loại theo chuỗi exception dịch được nếu có mã lỗi/API rõ ràng.
- B4b: migrate toàn bộ sync/callback caller để không biến status về chuỗi rỗng hay raw exception. Giữ wrapper tương thích tạm nếu cần compile, chỉ xóa sau khi hết callsite.
- Metadata engine theo job/trang; bỏ phụ thuộc `lastEngineUsed` toàn cục tại UI. Resolve nhãn bằng locale đang hiển thị.

**Nghiệm thu:** fake engine cho từng status; nhãn hai job không lẫn; model chưa tải không là NoText; hủy job không trở thành thông báo OCR thất bại. Test tải model lần đầu trên thiết bị trước khi đánh dấu đầy đủ.

### B5 — Chặn xuất kết quả giả hoặc thiếu trang im lặng (R05)

**Phụ thuộc:** B4. **File:** Home/Files/Viewer/Tools, helper gom kết quả nếu dùng; mỗi lượt một hoặc hai caller.

- Chỉ thêm header khi có text OCR thật; status/error không bao giờ trở thành nội dung xuất.
- Chính sách mặc định để tránh tự thiết kế: nếu có trang Failure/ModelUnavailable/Incompatible/Unsupported thì dừng xuất tự động, nêu trang lỗi và cho thử lại; không xuất bản thiếu trang như thành công đầy đủ.
- NoText là trạng thái hợp lệ của trang trắng; toàn bộ NoText thì không mở kết quả giả/không tạo Word. Nếu có trang Success và trang NoText, cho hiển thị kết quả kèm số trang không tìm thấy chữ, giữ số trang gốc.
- Chặn copy/share/export khi không có text thật; giữ nguyên dữ liệu quét và draft.

**Tests:** hai trang NoText; hai trang incompatible; một Success + một Failure; một Success + một NoText; cancellation. Nghiệm thu UI khớp chính sách ở tất cả entrypoints.

## 6. Đợt C — Nghiệm thu Việt–Anh đầy đủ

### C1 — Loại chuỗi còn hardcode và sửa nội dung (R07)

**Ưu tiên:** `MainActivity.kt`; `PostScanEditorViewModel.kt` + Activity; OCR callbacks; sau đó toàn bộ màn hình còn lại theo báo cáo literal.

- Mỗi lượt một màn hình hoặc một luồng lỗi. Đưa thông điệp vào base English + values-vi; ViewModel trả mã lỗi/tham số, UI resolve, không lưu Activity Context vào ViewModel.
- Không dịch OCR text, tên tài liệu người dùng, QR raw, MIME/protocol, tên thương hiệu và log. Tiêu đề/tên file đã lưu không được tự đổi khi UI đổi ngôn ngữ.
- Dùng placeholder/plural đúng; thống nhất scan/import/export/backup/sync trong cả Việt–Anh. Không đổi claim VIP/giá theo locale; xóa key cũ chỉ khi xác nhận hết references.
- Ưu tiên tái sử dụng key đã có đúng ngữ nghĩa. Nếu buộc tạo key mới, bổ sung bản dịch cho các locale còn đóng gói theo từng lượt nhỏ, chỉ giới hạn ở key mới đó; đây là bảo trì bắt buộc để không tạo MissingTranslation mới, không phải đợt dịch lại toàn bộ. Bản dịch chưa chắc phải ghi cần review và không gắn trạng thái locale đã nghiệm thu. Không copy English để giả đủ coverage, không nới checker/suppress để vượt gate. Nếu chưa hoàn tất key mới thì gói còn dang dở, không báo mốc A/C đạt.

**Nghiệm thu:** không còn chuỗi sai ngôn ngữ trong UI Việt/Anh, kể cả lỗi/empty/loading; allowlist có lý do. Rà đủ 4 tab, camera, editor/crop, PDF, OCR, ID card, QR, quản lý file, export, account/VIP/update.

### C2 — Ma trận thiết bị và mốc Việt–Anh

**Phụ thuộc:** A, B, C1. Không chỉ kiểm tra screenshot màn hình chính.

| Nhóm | Kịch bản tối thiểu |
|---|---|
| Locale | vi↔en, System, đổi trong Android Settings, restart/process death, migration, quay lại màn hình cũ |
| Dữ liệu | Draft chưa lưu, tên file Unicode, OCR tài liệu khác UI; không đổi tên/nội dung |
| UI | Font 1.0/1.3/2.0, màn hình hẹp, keyboard/dialog, loading/error/empty/success |
| OCR | vi/en/ja, explicit incompatible, offline lần đầu, model đã tải, retry/cancel, nhiều trang |
| Export | Tất cả NoText, một trang lỗi, không xuất header giả, copy/share không có thông báo lỗi trong nội dung |
| Phân phối | Debug APK + kiểm tra language split của bản AAB cài thử; không tự phát hành Store |

**Nghiệm thu:** unit tests/assemble/Lint pass; checklist thiết bị có kết quả và bằng chứng. Nếu chưa có thiết bị hay điều kiện AAB thì ghi thiếu, chưa đánh dấu C hoàn tất; không hỏi lại quyền sửa các gói đã được giao.

## 7. Đợt D — Thêm ngôn ngữ theo nhu cầu thực tế

Chỉ bắt đầu sau mốc C. Khi chưa có dữ liệu người dùng hoặc lựa chọn của chủ ứng dụng, **dừng việc mở rộng ở Việt–Anh đã nghiệm thu**; không tự chọn và dịch thêm hàng loạt. Công việc bảo toàn và sửa hợp lệ các locale còn đóng gói vẫn thuộc A/B, không bị bỏ qua.

### D1 — Chọn một locale

- Ưu tiên nhu cầu người dùng/khách hàng đã có dữ liệu hợp lệ; không tự thêm analytics hoặc truyền dữ liệu để chọn.
- Mỗi đợt một locale canonical cùng alias. Ghi người review nội dung và thiết bị kiểm tra. Không coi chỉ dịch bằng mô hình nhỏ là đã đạt ngôn ngữ đó.

### D2 — Dịch và review theo nhóm màn hình (R06)

- Mỗi lượt 30–50 key cùng ngữ cảnh; đọc English chuẩn, Việt tham khảo và bản dịch hiện có.
- Giữ placeholders/markup, kiểm tra plural của locale, review chuỗi giống English với allowlist thương hiệu. Không dùng script dịch hàng loạt làm bằng chứng chất lượng.
- Review cả key đã dịch; sửa các đoạn bị hỏng như `100% s.` hoặc trộn `safe.`. Không dịch tên sản phẩm, dữ liệu người dùng.
- Hết một nhóm: process resources + validator. Hết locale: Lint + smoke workflow + người thông thạo duyệt nội dung.

### D3 — Nghiệm thu riêng cho hệ chữ/bố cục

- Locale chữ dài: kiểm tra cắt nút/dialog với font lớn.
- Arabic: RTL/Bidi, email/đường dẫn Latin, quantity, accessibility. Không mirror ảnh tài liệu, crop hoặc nút xoay vật lý.
- CJK/Hindi/Thai: font, ngắt dòng, script và phân biệt UI support với OCR support.
- Chỉ nâng trạng thái locale sau khi cả kỹ thuật, ngữ nghĩa và thiết bị đạt; không reset locale người dùng khác khi nâng trạng thái.

## 8. Lệnh kiểm tra và nguyên tắc báo cáo

```powershell
python scripts/audit_localization.py
$env:GRADLE_USER_HOME='C:\Users\nguye\.gradle'
.\gradlew.bat :app:processDebugResources --offline --console=plain
.\gradlew.bat :app:testDebugUnitTest --rerun --offline --console=plain
.\gradlew.bat :app:assembleDebug :app:lintDebug --offline --console=plain
```

Chạy kiểm tra tối thiểu phù hợp trong mỗi tiểu gói; toàn bộ ở cuối mốc. Cache không ghi được thì dùng quyền thực thi phù hợp, không sửa version Gradle hay xóa cache để né lỗi. Instrumentation chỉ báo pass khi thật sự chạy trên thiết bị/emulator.

Không sửa tests để giữ 68 tests hay số lượng cố định. Số lượng tests không phải mục tiêu; regression phải bắt được lỗi cũ. Không thêm suppression/baseline cho 84 lỗi để báo build sạch.

## 9. Prompt giao việc cho mô hình nhỏ

```text
Thực hiện đúng gói <A0/A1/A2-<locale>/B1a/B1b/B2/B3-UI/B3-caller/
B4a/B4b-<screen>/B5-<screen>/C1-<screen>/C2/D2-<locale+group>/D3-<locale>>
trong PLAN_LANGUAGE_ROLLOUT_SMALL_MODEL_2026-09-17.md.

Đọc phạm vi gói, bằng chứng liên quan trong REVIEW_MULTILINGUAL_2026-09-17.md
và git diff hiện tại trước khi sửa. Giữ mọi thay đổi có sẵn.
Chỉ làm một gói nhỏ, không tự chuyển sang gói tiếp theo hay dịch toàn bộ locale.
Giữ 37 lựa chọn và dữ liệu hiện có; ưu tiên nghiệm thu Việt–Anh, không ẩn/xóa locale.
UI language và OCR document language phải độc lập; không thêm engine/model.
Nếu lỗi đã được sửa, xác minh thay vì viết lại. Nếu thiếu prerequisite, báo rõ;
không tự triển khai một kiến trúc khác hoặc làm rộng phạm vi.
Thêm regression test cho lỗi logic, chạy kiểm tra đúng gói; không sửa test theo bug.
Không suppress để che lỗi hoặc copy English để giả đủ bản dịch.
Ghi LANGUAGE_ROLLOUT_STATUS.md: phạm vi, files, lệnh/kết quả, chưa kiểm thử,
điều kiện nghiệm thu còn thiếu và gói tiếp theo được đề xuất.
Không commit/push/build release/phát hành. Kết thúc sau gói đã giao.
```

**Thứ tự mặc định:** A0 → A1 → A2 từng locale → B1a → B1b → B2 → B3 → B4 → B5 → C1 từng màn hình → C2. D chỉ mở theo nhu cầu sau mốc C. Bảng trạng thái chưa tồn tại thì bắt đầu A0; không giao “làm hết kế hoạch” cho một lượt của mô hình nhỏ.
