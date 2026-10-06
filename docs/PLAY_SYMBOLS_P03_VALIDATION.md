# Báo cáo P03 — Kiểm tra candidate và lưu bộ artifact cùng build

Ngày: 2026-09-24  
Workspace: `E:\DU AN AI\T-Scanner`  
Trạng thái gói: **HOÀN THÀNH — HOST CHECKS THÀNH CÔNG, LƯU TRỮ VÀ KIỂM CHỨNG BỘ CANDIDATE ARTIFACT ĐẦY ĐỦ**

---

## 1. Kết quả Host Checks

Thực thi bộ kiểm tra host toàn diện:
```powershell
./gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --offline
./gradlew.bat :app:bundleRelease :app:assembleRelease --offline
```

- **Exit Code**: `0` (BUILD SUCCESSFUL cho toàn bộ các task).
- **Unit Tests (`:app:testDebugUnitTest`)**: Tất cả các unit test debug đều PASS, không có lỗi hồi quy (regression) nào phát sinh sau khi cấu hình R8 và native symbols.
- **Lint (`:app:lintDebug`)**: Không có lỗi fatal chặn build.
- **Build Release (`:app:bundleRelease :app:assembleRelease`)**: Thành công, sinh đầy đủ AAB, APK và mapping.

---

## 2. Kết quả Smoke & Runtime Tests trên thiết bị

- Lệnh kiểm tra kết nối thiết bị: `adb devices`
- **Kết quả**: `List of devices attached` trống (không có thiết bị vật lý hoặc máy ảo emulator nào đang kết nối).
- **Ký số release**: Bản release local hiện tại là `app-release-unsigned.apk` (chưa ký keystore release).
- **Tình trạng nghiệm thu Runtime**: **CHƯA NGHIỆM THU RUNTIME (NOT_RUN)** theo đúng quy định tại Kế hoạch P03:
  > *"Nếu thiếu signing/device: báo chưa nghiệm thu runtime. Cả arm64-v8a và armeabi-v7a nếu tiếp tục phát hành cả hai. Thiếu thiết bị phải ghi NOT_RUN."*

---

## 3. Danh mục Bộ Candidate Artifact đã lưu trữ

Thư mục lưu trữ bằng chứng ứng viên độc lập: `app/build/candidate_play_symbols/`

| Loại Artifact | Tên file lưu trữ | Dung lượng (bytes) | SHA-256 |
|---|---|---|---|
| **App Bundle (AAB)** | `app-release-candidate.aab` | 17,032,117 bytes | `945f62a86fb6c8f9a6b716dd5487409126b400c7b3fbdff70351af807f5f8d55` |
| **Mapping File** | `mapping.txt` | 48,834,127 bytes | `35e9549ed0ec7840e52d98bf4ae935f94e117be1fa54220d52487c127c7fd9a8` |

### Thông tin truy nguồn binary của Candidate AAB:
- **Application ID**: `com.tscanner.app`
- **versionCode**: `16`
- **versionName**: `0.9.9`
- **compileSdkVersion**: `36` | **minSdkVersion**: `26` | **targetSdkVersion**: `36`
- **AGP Version**: `9.3.0`
- **Variant**: `release`
- **Metadata nhúng trong AAB**:
  - `BUNDLE-METADATA/com.android.tools.build.obfuscation/proguard.map`: Có (48,834,127 B, SHA-256: `35e9549ed0...`, khớp tuyệt đối với `mapping.txt`).
  - `BUNDLE-METADATA/com.android.tools.build.debugsymbols/`: Không có (do tất cả 14 thư viện `.so` từ upstream AARs đã bị stripped trước đó).

---

## 4. Nghiệm thu P03
- [x] Host checks (unit tests, lint, debug & release assemble/bundle) đạt 100% exit code 0.
- [x] Không có regression mới từ code hay cấu hình.
- [x] Ghi nhận trung thực tình trạng thiết bị / runtime: `NOT_RUN` do thiếu device và signing key.
- [x] Đã lưu bộ candidate AAB, mapping và tính toán SHA-256 chính xác tại `app/build/candidate_play_symbols/`.
- [x] Sẵn sàng dữ liệu cho gói P04 (Xác minh và chuẩn bị phát hành Play Console).
