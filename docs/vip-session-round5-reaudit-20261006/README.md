# Bằng chứng kiểm tra 06/10/2026

- `status-before.txt`: trạng thái checkout trước kiểm tra.
- `source-hashes.json`, `source-evidence.txt`: hash và trích dòng production đã kiểm tra; không sửa production.
- `host.log`: full test/lint/assemble offline, BUILD SUCCESSFUL; test chạy thật, assembleDebug UP-TO-DATE.
- `host-test-results.json`: số theo từng suite trước khi probe thay output Gradle; 1.075 tests, không failure/error/skipped.
- `lint-results-debug.xml`: 0 errors, 768 warnings toàn dự án; không quy toàn bộ warning cho bản sửa này.
- `adb-devices.txt`: không có device; Google/Play/lifecycle thực chưa chạy.
- `VipSessionRound5ProbeTest.kt`, `audit.init.gradle`, `run-probes.ps1`: probe độc lập và lệnh tái hiện; chỉ thêm nguồn test cho invocation này.
- `probes.log`, `TEST-*ProbeTest.xml`: log và XML của bộ probe chọn lọc; Gradle exit khác 0 khi assertion đỏ là kết quả phát hiện lỗi, không phải compilation failure.
- `gradle-threads.txt`: chẩn đoán lint chạy lâu; lint sau đó hoàn tất bình thường.

Môi trường: JDK `C:\Users\nguye\.jdks\openjdk-21.0.1`, GRADLE_USER_HOME `C:\Users\nguye\.gradle`.

Full command từ root:

```powershell
$env:JAVA_HOME='C:\Users\nguye\.jdks\openjdk-21.0.1'
$env:GRADLE_USER_HOME='C:\Users\nguye\.gradle'
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --offline -I docs/vip-session-round3-reaudit-20261004/audit.init.gradle --console=plain
```

Probe command: `powershell -NoProfile -File docs/vip-session-round5-reaudit-20261006/run-probes.ps1`.

Để bảo toàn bằng chứng vòng này, khi sửa hãy copy runner/init/probe sang thư mục fix, cập nhật đường dẫn nguồn/output tương ứng rồi chạy; không ghi đè log/XML đã bàn giao. Nếu API đổi chỉ cập nhật adapter, giữ tình huống và assertion.
