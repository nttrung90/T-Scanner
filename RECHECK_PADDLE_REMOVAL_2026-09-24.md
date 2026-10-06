# Kiểm tra độc lập việc loại bỏ Paddle/ONNX — 24/09/2026

## Kết luận

Phần gỡ runtime/model, chuyển routing Chinese, migration preference và xóa lựa chọn UI đã thực hiện đúng ở các đường code được kiểm tra. Chưa thấy lỗi compile hoặc regression trong bộ chuẩn. Chưa nghiệm thu toàn phần: còn vấn đề quản lý tài nguyên ML Kit có sẵn, cleanup chưa giữ đầy đủ hợp đồng cancellation, và kiểm chứng Android/chu trình lưu-xuất legacy còn thiếu.

Không sửa production hoặc test chuẩn. Chỉ thêm probe độc lập trong `build/paddle-reaudit/` và báo cáo này. Working tree có nhiều thay đổi của người dùng, không reset/clean/revert.

## Kết quả thực chạy

- `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:assembleRelease :app:bundleRelease --offline --console=plain`: BUILD SUCCESSFUL; phần lớn build tasks up-to-date. Log `build/paddle-reaudit-2026-09-24.log`.
- Sau probe chạy lại bộ chuẩn không có init script: **460 tests, 0 failures, 0 errors, 0 skipped**. Log `build/paddle-reaudit/standard-and-dependencies.log`.
- Lint hiện tại: **0 errors, 718 warnings**. Không gọi là lint sạch.
- Dependency report `releaseRuntimeClasspath`: không có Paddle/ONNX.
- ZIP release APK: **28.691.181 byte**, 0 entry tên paddle/onnx. AAB: **17.988.354 byte**, 0 entry tên paddle/onnx. Khớp báo cáo thực hiện; dung lượng file không đồng nghĩa dung lượng download/installed trên mỗi thiết bị.
- Probe độc lập gọi cleanup production: **3 tests, 2 failures**, control giữ unknown/sibling data đạt. Log `build/paddle-reaudit/probe.log`; XML đã lưu riêng `build/paddle-reaudit/probe-results.xml` trước khi chạy lại bộ chuẩn.
- ADB sau khởi động daemon: `List of devices attached` trống. Không có thiết bị/emulator để kiểm tra upgrade, model download, offline hoặc độ chính xác chữ Trung.
- Lượt Gradle đầu bị wrapper lock Access denied; chạy lại với quyền cache đã thành công, không phải lỗi code.

## R01 — P2: Recognizer ML Kit chưa được đóng sau thành công hoặc thất bại

**Bằng chứng:** `app/src/main/java/com/tscanner/app/utils/TextRecognitionHelper.kt:746-801`. Mỗi lượt tạo client mới bằng `TextRecognition.getClient(options)`. Chỉ có `recognizer?.close()` bên trong `cont.invokeOnCancellation` ở dòng 795–798. Hai listener thành công/thất bại resume continuation nhưng không đóng client. Hoàn thành bình thường không chạy cancellation handler.

**Tác động:** luồng OCR nhiều trang/lặp lại không giải phóng client rõ ràng khi xong; nguy cơ giữ tài nguyên ML Kit lâu hơn cần thiết. Chưa đo tăng RAM, chưa tái hiện crash trên Android. Đây là vấn đề adapter có sẵn từ trước khi gỡ Paddle, không quy kết là lỗi mới do xóa ONNX. Tuy nhiên Chinese hiện luôn đi qua adapter này nên cần xử lý trước nghiệm thu độ ổn định.

**Đề xuất gói sửa:** đóng client trên mọi terminal path (Success, NoText, Failure, lỗi đồng bộ, cancellation), bảo đảm cleanup chỉ một lần và không resume hai lần. Kiểm tra cả callback đến trễ sau cancellation. Test cần đi qua adapter/lifecycle thật với factory/task seam nhỏ, không chỉ gán `mlKitChineseRunner` vì seam đó bỏ qua toàn bộ đoạn đang lỗi.

**Nghiệm thu:** mỗi client tạo ra được release đúng một lần, kể cả thiếu model và hủy; OCR nhiều trang giữ dữ liệu/contract kết quả; kiểm tra thực trên Android.

## R02 — P3: Cleanup nuốt CancellationException trong bước xóa

**Bằng chứng tái hiện:** `LegacyPaddleCleanup.kt:138-147,155-159`. Hai catch Throwable không rethrow CancellationException. Outer suspend wrapper có catch cancellation nhưng không nhận được exception đã bị inner catch nuốt.

Probe `fileDeletionCancellationMustPropagate` và `directoryDeletionCancellationMustPropagate` gọi production `cleanupDirectory` và inject CancellationException qua deletion seam hiện có; cả hai fail. Test hiện có chỉ ném cancellation trong `getFilesDir`, nên chưa bao phủ bước xóa.

**Giới hạn tác động:** deleter mặc định hiện dùng `File.delete()` đồng bộ, thông thường không tự ném coroutine cancellation. Đây là lỗi hợp đồng cancellation/fault handling đã xác nhận, không phải bằng chứng app hiện crash hoặc mất tài liệu. Control xác nhận file lạ và sibling vẫn nguyên vẹn trong trường hợp thông thường.

**Đề xuất gói sửa:** catch CancellationException trước Throwable ở cả hai điểm; nếu cần coroutine cancellation thực sự trong vòng lặp thì kiểm tra active tại ranh giới thao tác. Giữ I/O failure không chặn startup, allowlist, no-follow-link và không đệ quy. Chuyển hai probe thành regression test chuẩn.

## R03 — Thiếu bằng chứng để nghiệm thu P06/P07

1. `PaddleRemovalVerificationTest.kt:79-193` tạo model, serialize/parse JSON và copy editedContent, rồi serialize lại. Điều này chứng minh serialization mẫu legacy, **không chứng minh** đường repository save/reopen/export hoặc Activity không kích hoạt OCR lại. Báo cáo `docs/paddle-removal/verification.md` mục 4–5 khẳng định quá mức so với ca test này.
2. P07 vẫn chưa chạy; hiện kiểm tra ADB cũng không có thiết bị. Cần thử nâng cấp tại chỗ giữ dữ liệu, Chinese model chưa tải, tải xong rồi offline, OCR chữ Trung/line-only legacy trên Android.
3. `docs/paddle-removal/device-acceptance.md:30,59-61` chỉ dẫn cài `app-release-unsigned.apk`; ghi chú coi ký APK là điều kiện Android 14+ là không đúng. Cần chuẩn bị **cả baseline và candidate đã ký cùng test key** cho môi trường thử, xác minh certificate/applicationId trước `install -r`; không thay candidate sang debug khác key rồi coi là upgrade test tương đương.
4. Uninstall app không chứng minh model unbundled trong Google Play Services đã bị xóa. Ca offline thiếu model cần môi trường thử sạch và xác minh model thực chưa sẵn sàng; tránh false pass do module được cache từ trước. Không xóa dữ liệu Play Services trên thiết bị người dùng.

**Đề xuất gói kiểm chứng:** thêm legacy integration gọi repository thực trong thư mục tạm, mở lại từ disk, edit qua command/reducer, save/reopen và export bằng writer production; kiểm tra text/table/engineId/source image. Sửa tài liệu để khớp bằng chứng, chuẩn bị hai APK ký cùng test key và chạy P07 khi có thiết bị. Không ghi “100%/hoàn hảo” từ một số fixture JVM.

## Đối chiếu kế hoạch

| Gói | Kết quả kiểm tra |
|---|---|
| P00 | Có baseline/báo cáo; không tái tạo lịch sử thao tác baseline |
| P01 | Getter/setter chuẩn hóa legacy; core normalize trước compatibility; giữ metadata string lịch sử |
| P02 | Chinese Auto/ML Kit đi ML Kit Chinese; alias/registry đã chuyển; thiếu model có result riêng; còn R01 trong adapter chung |
| P03 | Đã bỏ row/binding Paddle; chuỗi còn lại thuộc tên lịch sử; build resources đạt |
| P04 | Class/assets/dependency/ProGuard/startup Paddle đã gỡ; ZIP/dependency xác minh đạt |
| P05 | Allowlist, không recursive, IO và kiểm tra link có mặt; control bảo toàn file đạt; còn R02 |
| P06 | Bộ chuẩn/build/lint đạt nhưng coverage legacy end-to-end chưa đủ; báo cáo cần thu hẹp tuyên bố |
| P07 | Chưa chạy; thiếu thiết bị, có hướng dẫn cần sửa trước thực hiện |

Không phát hiện mất dữ liệu do cleanup trong các ca đã chạy. Không kết luận ứng dụng không còn lỗi chỉ từ 460 tests xanh. Ưu tiên tiếp theo: R01 → R02 → bổ sung kiểm chứng R03. Chưa thay code trong lượt kiểm tra này.

## Lệnh probe để tái hiện

```powershell
$env:GRADLE_USER_HOME='C:\Users\nguye\.gradle'
.\gradlew.bat -I build/paddle-reaudit/audit.init.gradle :app:testDebugUnitTest --tests com.tscanner.app.PaddleIndependentReauditTest --offline --console=plain
```

Probe trong build có thể mất khi clean. Sau probe, chạy lại `:app:testDebugUnitTest` không init script để khôi phục báo cáo bộ chuẩn; việc này đã thực hiện trong lượt kiểm tra này.
