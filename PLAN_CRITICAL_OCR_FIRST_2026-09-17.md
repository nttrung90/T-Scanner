# Kế hoạch ưu tiên lỗi nghiêm trọng trước

Ngày: 2026-09-17. Chỉ lập kế hoạch; chưa sửa mã ứng dụng.

Tài liệu này thay **thứ tự thực hiện** của `REVIEW_AND_FIX_PLAN_POST_ROLLOUT_2026-09-17.md`. Dùng báo cáo đó để xem bằng chứng F01–F07; không làm toàn bộ S01–S08 trong đợt đầu.

## 1. Chia hai đợt

| Đợt | Vấn đề | Mục tiêu |
|---|---|---|
| **Đợt 1 — sửa ngay** | F02: Tesseract âm thầm đổi model Việt/Anh; F01: PaddleOCR nuốt lỗi/bỏ vùng crop lỗi | Không nhận diện bằng model sai rồi báo đúng; không báo thành công với kết quả thiếu do lỗi kỹ thuật |
| **Đợt 2 — sau khi đợt 1 đạt** | F03: tài nguyên ML Kit; F04: callback cũ/lifecycle; F05: thông báo lỗi; F06: nhãn/ngày; F07: validator | Ổn định vòng đời, hoàn thiện thông báo và khả năng phát hiện lỗi |

Đợt 1 chỉ gồm **hai lỗi P1 đã xác nhận từ code**. F03/F04 vẫn quan trọng nhưng hiện chưa có bằng chứng thiết bị về OOM/crash hoặc xuất nhầm thực tế, nên giữ ở đợt sau theo mức ưu tiên của audit. Nếu quá trình kiểm thử phát hiện crash, tăng bộ nhớ không giới hạn hoặc tác vụ cũ thực sự xuất nhầm tài liệu thì nâng finding đó lên đợt 1 với bằng chứng và phạm vi riêng; không tự mở rộng vì suy đoán.

Giữ nguyên 37 lựa chọn ngôn ngữ, các bản dịch, model hiện có, thuật toán nhận diện và dữ liệu người dùng. Không dịch hàng loạt, đổi giá/VIP, nâng dependency, commit/push hoặc build release/phát hành.

## 2. Đợt 1: ba gói nhỏ, thực hiện tuần tự

### H01 — Tesseract phải dùng đúng model được yêu cầu

**Finding:** F02. **Ưu tiên:** P1. **Đầu vào:** code hiện tại và tests liên quan, không dùng kết quả test cũ để kết luận đã sửa.

**Phạm vi file:**

- `app/src/main/java/com/tscanner/app/utils/TesseractOcrHelper.kt`.
- Phần `runTesseractStructured`/routing trong `TextRecognitionHelper.kt` nếu cần để truyền trạng thái đúng.
- Regression test cho khởi tạo Tesseract; bổ sung adapter test nhỏ nếu không thể giả lập native API trực tiếp.

**Hành vi cần đạt:**

1. Request `vi`/`vie` chỉ khởi tạo model `vie`; `en`/`eng` chỉ model `eng`.
2. Model được yêu cầu init thất bại phải trả ModelUnavailable hoặc Failure phù hợp. Không tự thử model của ngôn ngữ khác trong helper.
3. Fallback chỉ do routing cấp trên thực hiện với engine hỗ trợ ngôn ngữ tài liệu; nếu thành công phải có metadata `fallbackUsed=true`.
4. Không tự thêm lựa chọn model kết hợp. Nếu API `vie+eng` hiện còn caller thì kiểm tra callsite: chỉ giữ hành vi khi caller yêu cầu rõ ràng, không downgrade âm thầm sang model đơn.
5. Giải phóng mọi TessBaseAPI instance đã tạo, kể cả khi init ném exception; giữ CancellationException.

**Regression bắt buộc:**

| Tình huống giả lập | Kỳ vọng |
|---|---|
| `vie` init thất bại, `eng` có thể init | Không gọi init eng; không Success từ model Anh |
| `eng` init thất bại, `vie` có thể init | Không gọi init vie; không Success từ model Việt |
| Model yêu cầu init thành công, có text | Success, đúng model/request |
| Init thành công, nhận diện xong không có text | NoText |
| Init/nhận diện ném lỗi hoặc bị hủy | Error/cancellation đúng; tài nguyên được giải phóng |
| Tesseract lỗi, ML Kit cùng capability thành công | Success với fallback metadata đúng |

**Điều kiện hoàn thành:** test mới bắt được implementation cũ; helper không đổi ngôn ngữ ngầm; tests liên quan pass. Test resolver thuần đã có **không thay thế** test khởi tạo engine.

### H02 — PaddleOCR không được che lỗi hoặc xuất thiếu vùng chữ

**Finding:** F01. **Ưu tiên:** P1. **Thực hiện sau:** H01, tránh hai lượt cùng sửa helper.

**Phạm vi file:**

- `app/src/main/java/com/tscanner/app/paddleocr/PaddleOcrEngine.kt`.
- Phần `runPaddleOcrStructured` và ánh xạ fallback liên quan trong `TextRecognitionHelper.kt`.
- Tests cho engine adapter/detection/crop/recognition failure; chỉ thay `OcrModels.kt` nếu trạng thái hiện có không đủ.

**Hành vi cần đạt:**

1. Phân biệt chạy thành công không tìm thấy chữ với lỗi session, init, inference và crop.
2. Thay đường catch Throwable→chuỗi rỗng bằng trạng thái lỗi hoặc exception phân loại được đến wrapper. Cancellation phải được truyền lại.
3. Nếu crop chính thất bại nhưng fallback crop thành công thì tiếp tục hợp lệ. Nếu cả hai thất bại, không `continue` bỏ vùng; tác vụ trang phải là lỗi, không Success chứa những dòng còn lại.
4. Giữ fallback sang ML Kit Chinese ở cấp routing. Fallback thực sự thành công có thể cứu trang; nếu primary lỗi và fallback NoText thì không được coi là trang trắng.
5. Đóng tensor và recycle bitmap/crop thuộc sở hữu helper ở mọi đường kết thúc; không recycle ảnh đầu vào thuộc caller.
6. Không sửa thuật toán detection, threshold, model hoặc chiến lược sắp dòng trong gói này.

**Regression bắt buộc:** init/session không dùng được; detector ném lỗi; lỗi nhận diện vùng thứ hai sau vùng đầu thành công; cả hai cách crop thất bại; detection thành công không có vùng; nhận diện thành công nhưng không có chữ; hủy; primary lỗi/fallback Success; primary lỗi/fallback NoText.

**Điều kiện hoàn thành:** không mất lỗi ở lớp engine thấp nhất; không Success thiếu dòng do lỗi kỹ thuật; fallback và cleanup được kiểm tra. Không chỉ tạo một OcrResult.Failure bằng tay rồi test aggregator để tuyên bố engine đã sửa.

### H03 — Kiểm chứng chặn xuất sai trên các luồng người dùng

**Phụ thuộc:** H01/H02. **Phạm vi:** tests tích hợp và chỉ các caller/aggregator thực sự còn lỗi khi nối trạng thái mới.

- Kiểm tra Home, Files, PDF Viewer và Tools nhận đúng kết quả engine. Không sửa lại tất cả các màn hình nếu logic hiện tại đã đúng.
- Hai trang, một trang bị lỗi kỹ thuật: phải dừng xuất tự động, không báo lưu Word thành công, không ghép lỗi vào nội dung OCR. Dữ liệu scan/draft vẫn giữ nguyên.
- Tất cả trang NoText: không tạo file chỉ chứa tiêu đề trang.
- Một trang Success + một trang NoText thực sự: giữ chính sách hiện có, thông báo trang không có chữ; không nhầm NoText với lỗi kỹ thuật.
- Fallback engine hợp lệ thành công: kết quả vẫn được phép hiển thị/xuất với metadata đúng.
- Đối với lỗi mới phát sinh từ H01/H02, bảo đảm UI nhận trạng thái thất bại và không hứa một tiến trình không có thật. Chỉ chỉnh thông báo tối thiểu cần cho lỗi đó; việc xây lại toàn bộ mapper lỗi thuộc đợt 2.

**Kiểm tra cuối đợt:**

```powershell
python scripts/audit_localization.py
python -m unittest scripts/test_audit_localization.py
$env:GRADLE_USER_HOME='C:\Users\nguye\.gradle'
.\gradlew.bat :app:testDebugUnitTest --rerun :app:assembleDebug :app:lintDebug --offline --console=plain
```

Script validator vẫn còn F07 nên kết quả PASS của nó không phải bằng chứng toàn diện. Lint/test engine và integration là các gate độc lập. Không thêm suppression hoặc sửa tests theo bug để làm xanh.

**Trên thiết bị khi có:** vi/en với model init lỗi có kiểm soát; Chinese với inference/crop lỗi có kiểm soát; tài liệu nhiều trang; retry sau lỗi; native cleanup. Dùng debug test hook/fake được cô lập, không làm hỏng traineddata hoặc tài liệu thật của người dùng.

**Đóng đợt 1:** chỉ đánh dấu “đã sửa và kiểm tra tự động” khi tests/fault injection đúng phạm vi pass. Nếu chưa có thiết bị, ghi riêng “chưa nghiệm thu native trên thiết bị”, không tuyên bố đã sẵn sàng phát hành. Dừng ở đây; không tự triển khai đợt 2.

## 3. Đợt 2: backlog đã xác định, chưa thực hiện trong đợt 1

| Thứ tự | Gói cũ tương ứng | Phạm vi | Điều kiện nghiệm thu chính |
|---|---|---|---|
| 1 | S03 / F03 | Đóng recognizer ML Kit đúng vòng đời, ownership bitmap khi cancel | Create/close cân bằng; không double close; không recycle bitmap khi SDK còn dùng |
| 2 | S04a/b / F04 | OCR thuộc lifecycle Home/Tools và job token | A cũ không cập nhật UI, dismiss loading hoặc export sau khi B thay thế |
| 3 | S05a/b / F05 | Mã lỗi và mapper dịch; phân biệt model chưa có/mạng/hết dung lượng/model hỏng | Không hiển thị raw error hoặc gọi mọi trường hợp là “đang tải” |
| 4 | S06a/b / F06 | Label ngôn ngữ OCR, ngày theo locale, metadata màn hình kết quả | Nhãn/ngày đúng lựa chọn hiện tại; không thay đổi nội dung tài liệu |
| 5 | S07 / F07 | Validator plural index, duplicate quantity, thiếu locale folder | Ba fixture lỗi đã chứng minh phải bị bắt; fixture hợp lệ vẫn pass |

Chi tiết của các gói này giữ trong `REVIEW_AND_FIX_PLAN_POST_ROLLOUT_2026-09-17.md`. Trước đợt 2 phải kiểm tra lại source và diff sau H01–H03; không áp patch cũ một cách máy móc. Cảnh báo Lint thuần typography và việc dịch mở rộng tiếp tục để sau, không chen vào sửa P1.

## 4. Prompt giao model nhỏ — chỉ đợt nghiêm trọng

```text
Đọc PLAN_CRITICAL_OCR_FIRST_2026-09-17.md và chỉ làm gói <H01/H02/H03>.
Đọc source hiện tại, diff và finding F01/F02 trong báo cáo liên quan.
Giữ mọi thay đổi có sẵn; chỉ sửa lỗi còn tồn tại và những dependency trực tiếp.
Không làm S03–S07/F03–F07, không mở rộng dịch thuật, không thêm model/dependency.
Viết regression ở engine boundary thực sự đang sai; test phải bắt được code cũ.
Giữ dữ liệu scan/draft, đúng ngôn ngữ model, đúng status lỗi và fallback metadata.
Chạy kiểm tra phù hợp, ghi LANGUAGE_ROLLOUT_STATUS.md với file, lệnh, kết quả,
phần native/thiết bị chưa kiểm tra. Không sao chép số test cũ làm bằng chứng.
Nếu phát hiện lỗi nghiêm trọng mới, ghi bằng chứng và đề xuất gói riêng;
không tự gom vào refactor lớn. Không commit/push/build release/phát hành.
Hoàn thành gói được giao thì dừng, báo điều kiện còn thiếu và gói tiếp theo.
```

**Bắt đầu bằng H01 → H02 → H03. Đợt 2 để lượt giao việc sau.**
