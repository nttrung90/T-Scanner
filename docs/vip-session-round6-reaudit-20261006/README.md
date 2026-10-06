# Evidence vòng 6 — 06/10/2026

Full host suite: 1.090 tests, 0 failures/errors/skipped; `host-results.json` lưu từng suite trước probe. `host.log`: BUILD SUCCESSFUL 44s; 2 executed/53 up-to-date, test chạy lại, lint analysis/report và assembleDebug UP-TO-DATE. XML lint 0 errors/768 warnings toàn dự án.

`run-probes.ps1` + `audit.init.gradle`: chạy 27 tests, gồm 25 regression cũ và 2 probe vòng 6. **26 PASS/1 FAIL**, failure tại P01 listener không chuyển event đến consumer. `TEST-*ProbeTest.xml` và `probes.log` lưu kết quả cuối; exit 1 do assertion chứ không compile.

Vòng 5 dùng nguồn probe trong `docs/vip-session-round5-fix/`, có reset reservation và explicit release/confirm theo API mới; các tình huống/assertions vẫn được giữ. Không chạy P03 unbound API vòng 2 theo phạm vi đã chấp nhận trước.

`source-evidence.txt`: trích code và dòng liên quan S01/S02 + coverage. `source-hashes.json`: hash snapshot lúc audit. `status-before/after.txt`: trạng thái checkout. Không production edits. `adb-devices.txt`: rỗng, device/Play NOT RUN.

Môi trường: JAVA_HOME `C:\Users\nguye\.jdks\openjdk-21.0.1`, GRADLE_USER_HOME `C:\Users\nguye\.gradle`.

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --offline -I docs/vip-session-round3-reaudit-20261004/audit.init.gradle --console=plain
powershell -NoProfile -File docs/vip-session-round6-reaudit-20261006/run-probes.ps1
```

Khi sửa, copy runner/init/probes sang thư mục fix và đổi đường dẫn phù hợp; không ghi đè evidence này. Chi tiết trong report/plan vòng 6 ở root.
