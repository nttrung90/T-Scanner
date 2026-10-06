# Hợp Đồng Dữ Liệu và Quy Chuẩn Mẫu (OCR Data Contract & Fixtures)

**Ngày ban hành:** 22/09/2026  
**Gói triển khai:** S01 — Bộ mẫu và hợp đồng dữ liệu  
**Kế hoạch tham chiếu:** `PLAN_OCR_READER_EDITOR_SMALL_MODEL_10MB_2026-09-22.md`

---

## 1. Mục Đích và Nguyên Tắc Cốt Lõi

1. **Một nguồn chân lý (Single Source of Truth) phân tầng:**
   - Dữ liệu OCR gốc từ engine (`sourceText`, `sourcePolygon`, `engineConfidence`) là bất biến (immutable provenance).
   - Nội dung người dùng chỉnh sửa (`editedText`, `formatting`, `tableEdits`) được quản lý qua thực thể sửa đổi riêng biệt có theo dõi số hiệu phiên bản (`revision`).
2. **Không phụ thuộc kích thước hiển thị (Coordinate Independence):**
   - Tọa độ hình học lưu trữ ở cả hai dạng:
     - Tọa độ pixel trên ảnh nguồn (`sourceWidthPx`, `sourceHeightPx`).
     - Tọa độ chuẩn hóa trong khoảng `[0.0, 1.0]` độc lập với độ phân giải và tỷ lệ hiển thị.
3. **An toàn kiểu dữ liệu và chống tiêm công thức (Formula Injection Defense):**
   - Mọi ô bảng trích xuất OCR mặc định là chuỗi ký tự (`TEXT`).
   - Chuỗi có số 0 ở đầu (ví dụ `00123`, `0901234567`) bắt buộc giữ nguyên kiểu chuỗi.
   - Các chuỗi bắt đầu bằng dấu `=`, `+`, `-`, `@` khi xuất bảng tính (XLSX/CSV) phải được vô hiệu hóa để ngăn chặn thực thi mã độc/công thức tự động.
4. **Không đưa tài nguyên ảnh nặng vào APK:**
   - Bộ mẫu ground truth và ảnh fixture phục vụ kiểm thử đơn vị được đặt trong `app/src/test/resources/ocr_reader/`, không nằm trong `app/src/main/assets/` để bảo vệ trần dung lượng 10 MB.

---

## 2. Định Nghĩa Hợp Đồng Dữ Liệu (Schema Contract)

### 2.1. Nhận Diện Thực Thể (Stable Identifier Contract)

| Thực thể | Quy tắc định dạng ID | Ví dụ |
|---|---|---|
| **Tài liệu (Document)** | `doc_<UUID>` | `doc_7f9c2a1e-8b3d-4e92-bc10-1a2b3c4d5e6f` |
| **Trang (Page)** | `page_<pageIndex>_<UUID_8>` | `page_1_a1b2c3d4` |
| **Khối (Block)** | `blk_<pageIndex>_<blockIndex>` | `blk_1_0` |
| **Dòng (Line)** | `line_<pageIndex>_<lineIndex>` | `line_1_4` |
| **Từ/Token (Token)** | `tok_<pageIndex>_<tokenIndex>` | `tok_1_12` |
| **Bảng (Table)** | `tbl_<pageIndex>_<tableIndex>` | `tbl_1_0` |
| **Ô (Cell)** | `cell_<tableIndex>_r<row>_c<col>` | `cell_0_r1_c2` |

### 2.2. Trạng Thái Trang (Page Status)

Mỗi trang trong tài liệu đa trang có một trạng thái xác định thuộc tập hợp:
- `PENDING`: Đang chờ xử lý nhận dạng.
- `SUCCESS`: Nhận dạng thành công, có văn bản hợp lệ.
- `NO_TEXT`: Trang trống, không phát hiện thấy văn bản hoặc hình ảnh trắng hoàn toàn.
- `ERROR`: Lỗi xử lý (hỏng ảnh, lỗi bộ nhớ, lỗi engine).
- `UNSUPPORTED_LANGUAGE`: Ngôn ngữ trang không được engine hỗ trợ.
- `CANCELLED`: Tác vụ nhận dạng bị người dùng hoặc hệ thống hủy bỏ.

### 2.3. Cấu Trúc Tài Liệu Chi Tiết (Document Object Model)

```json
{
  "documentId": "doc_sample_001",
  "schemaVersion": 1,
  "revision": 1,
  "title": "Bản quét hợp đồng mẫu",
  "createdAt": "2026-09-22T08:00:00Z",
  "updatedAt": "2026-09-22T08:00:00Z",
  "sourceLanguage": "vi",
  "pages": [
    {
      "pageId": "page_1_001",
      "pageIndex": 1,
      "status": "SUCCESS",
      "image": {
        "localUri": "file:///data/user/0/com.tscanner.app/files/scans/page_1.jpg",
        "widthPx": 2480,
        "heightPx": 3508,
        "rotationDegrees": 0
      },
      "sourceBlocks": [
        {
          "blockId": "blk_1_0",
          "boundingBox": { "left": 0.1, "top": 0.08, "right": 0.9, "bottom": 0.15 },
          "lines": [
            {
              "lineId": "line_1_0",
              "text": "CỘNG HÒA XÃ HỘI CHỦ NGHĨA VIỆT NAM",
              "polygon": [
                { "x": 0.2, "y": 0.08 },
                { "x": 0.8, "y": 0.08 },
                { "x": 0.8, "y": 0.11 },
                { "x": 0.2, "y": 0.11 }
              ],
              "confidence": 0.98,
              "tokens": [
                {
                  "tokenId": "tok_1_0",
                  "text": "CỘNG",
                  "polygon": [ { "x": 0.2, "y": 0.08 }, { "x": 0.28, "y": 0.08 }, { "x": 0.28, "y": 0.11 }, { "x": 0.2, "y": 0.11 } ],
                  "confidence": 0.99
                }
              ]
            }
          ]
        }
      ],
      "tables": [
        {
          "tableId": "tbl_1_0",
          "rowCount": 3,
          "columnCount": 4,
          "boundingBox": { "left": 0.1, "top": 0.4, "right": 0.9, "bottom": 0.7 },
          "cells": [
            {
              "cellId": "cell_0_r0_c0",
              "rowIndex": 0,
              "colIndex": 0,
              "rowSpan": 1,
              "colSpan": 1,
              "rawText": "Mã đơn hàng",
              "editedText": "Mã đơn hàng",
              "cellType": "TEXT"
            },
            {
              "cellId": "cell_0_r1_c0",
              "rowIndex": 1,
              "colIndex": 0,
              "rowSpan": 1,
              "colSpan": 1,
              "rawText": "00123",
              "editedText": "00123",
              "cellType": "TEXT"
            }
          ]
        }
      ],
      "editedContent": {
        "text": "CỘNG HÒA XÃ HỘI CHỦ NGHĨA VIỆT NAM\nĐộc lập - Tự do - Hạnh phúc",
        "paragraphs": [
          {
            "paragraphId": "p_1_0",
            "sourceAnchorLineId": "line_1_0",
            "text": "CỘNG HÒA XÃ HỘI CHỦ NGHĨA VIỆT NAM",
            "alignment": "CENTER",
            "runs": [
              {
                "text": "CỘNG HÒA XÃ HỘI CHỦ NGHĨA VIỆT NAM",
                "isBold": true,
                "isItalic": false,
                "fontSizePt": 14.0
              }
            ]
          }
        ]
      }
    }
  ]
}
```

---

## 3. Quy Tắc Đo Lường và Nghiệm Thu (Evaluation Rules)

### 3.1. Phép Đo Lỗi Ký Tự (Character Error Rate - CER)

- **Định nghĩa:**
  $$\text{CER} = \frac{S + D + I}{N}$$
  Trong đó:
  - $S$: Số ký tự bị thay thế (Substitutions).
  - $D$: Số ký tự bị xóa (Deletions).
  - $I$: Số ký tự bị chèn thêm (Insertions).
  - $N$: Tổng số ký tự trong ground truth tham chiếu.
  - Phép tính dựa trên khoảng cách Levenshtein mức ký tự Unicode chuẩn hóa dạng NFC.
- **Ngưỡng nghiệm thu:**
  - Văn bản tiếng Việt / tiếng Anh in rõ, chất lượng chuẩn: **CER $\le$ 2,0%**.
  - Văn bản mờ, nghiêng, xoay hoặc độ tương phản thấp: Báo cáo tỷ lệ riêng biệt, không cộng dồn làm sai lệch chỉ số tập in rõ.

### 3.2. Phép Đo Cấu Trúc Bảng (Table Structure Metric)

- **Ma trận lưới không chồng lấn:**
  Đối với mỗi ô $(r, c)$ có span $(rs, cs)$:
  Tất cả các ô $(r', c')$ trong vùng $[r \dots r+rs-1] \times [c \dots c+cs-1]$ chỉ thuộc duy nhất về ô đó.
- **Độ chính xác quan hệ lân cận (Adjacency Relation Accuracy):**
  - Khảo sát các cặp ô lân cận (trái-phải, trên-dưới).
  - Tính Precision, Recall và F1-Score so với ground truth.
  - **Ngưỡng nghiệm thu:** $F1 \ge 95\%$ trên tập bảng lưới đơn giản (simple grid tables).

### 3.3. Quy Tắc Ghép Trang và Giữ Số Thứ Tự (Page Preservation Rules)

- Tài liệu có $M$ trang gốc thì kết quả đầu ra bắt buộc có đúng $M$ phần tử trang.
- Nếu trang thứ $k$ rơi vào trạng thái `NO_TEXT` (trang trắng) hoặc `ERROR`, trang đó vẫn chiếm vị trí index $k$; các trang tiếp theo không bị đẩy dồn vị trí.
- Không tự ý xóa trang trắng hay bỏ qua trang lỗi trong cấu trúc dữ liệu tài liệu.

---

## 4. Danh Mục Bộ Mẫu Ground Truth (Fixtures Catalog)

Bộ fixtures được tổ chức tại `app/src/test/resources/ocr_reader/`:

| Thư mục / File ID | Ngôn ngữ | Đặc điểm | Số lượng ca mẫu | Tiêu chí kiểm định |
|---|---|---|---:|---|
| `single_column_vi` | Tiếng Việt | 1 cột, đầy đủ dấu thanh tiếng Việt | 5 | CER $\le$ 1,5%, giữ đúng dòng |
| `single_column_en` | Tiếng Anh | 1 cột, văn bản tiêu chuẩn | 5 | CER $\le$ 1,0%, không ngắt từ sai |
| `two_column_doc` | Song ngữ | Bố cục 2 cột song song | 4 | Đọc hết cột trái rồi sang cột phải; không xen dòng |
| `simple_table` | Số liệu | Bảng có đường kẻ đầy đủ, mã số có số 0 đầu | 5 | 100% đúng ô, giữ nguyên `"00123"` |
| `merged_cell_table` | Báo cáo | Bảng có ô gộp hàng và cột | 4 | F1 $\ge$ 95%, không sinh span ảo |
| `rotated_page` | Hỗn hợp | Ảnh xoay 90°, 180°, 270° | 4 | Map tọa độ về hệ quy chiếu chuẩn của trang |
| `blank_page` | Không | Trang trắng hoặc vệt nhiễu nhẹ | 3 | Trạng thái `NO_TEXT`, không sinh chữ rác |
| `formula_defense` | Bảng tính | Ô chứa các ký tự `=`, `+`, `-`, `@` | 3 | Không coi là formula, khử injection |
| `multipage_sequence`| Đa trang | Bộ 3 trang (Trang 1: Text, Trang 2: Trắng, Trang 3: Bảng) | 2 | Thứ tự 1, 2, 3 bảo toàn nguyên vẹn |

---

## 5. Kết Luận và Giới Hạn

Hợp đồng này là giao ước kỹ thuật bắt buộc cho toàn bộ các gói từ S02 đến S23. Mọi mô hình dữ liệu mới được bổ sung phải tuân thủ nghiêm ngặt schema và các ràng buộc xác thực tại tài liệu này.
