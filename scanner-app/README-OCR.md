# OCR

Phần scan từ bản desktop được tích hợp trong `ocr.py`; Java gọi qua `/api/ocr`.

## ocr_server.py — nạp model một lần

Java ưu tiên gọi OCR qua daemon `ocr_server.py` (HTTP trên `127.0.0.1:18081`)
thay vì spawn `ocr.py` từng lần:

- Daemon nạp RapidOCR **một lần** lúc khởi động; mỗi lần quét chỉ còn thời
  gian inference (lượt đầu khớp Excel khoảng 2s thay vì 15s+ như trước).
- Java tự khởi động daemon khi cần, chờ health rồi mới gửi ảnh; nếu không
  chạy được thì tự quay về spawn `ocr.py` như hành vi cũ.
- Cổng cấu hình `scanner.ocr.server-port` (mặc định 18081); tắt daemon bằng
  `scanner.ocr.server-enabled=false`.

Chiến lược hai tầng trong `ocr.py`:

- Lượt quét đầu (vùng giữa ảnh slot 2) dùng det **736** để ảnh rõ khớp nhanh.
- Chỉ khi chưa khớp Excel mới quét sâu với det **960** (chỉnh qua biến môi
  trường `SCANNER_OCR_DET_LIMIT`; 1280 nhận diện chữ nhỏ tốt hơn nhưng mỗi
  lượt quét chậm hơn đáng kể trên CPU yếu), gồm các biến thể nâng tương
  phản/phóng to/xoay và fallback quét cả ảnh khi tem SN không nằm giữa.
- Khi có Excel, ký tự dễ đọc nhầm (O/0, I/1, B/8, S/5, Z/2, G/6) được chuẩn
  hoá trước khi so khớp nên đọc nhầm 1-2 ký tự vẫn tự xác nhận, không phải
  quét lại.

## Cài đặt

```powershell
python -m venv .venv
.venv/Scripts/python.exe -m pip install -r requirements-ocr.txt
```

Backend dùng `SCANNER_PYTHON` nếu được cấu hình, tiếp theo là Python trong
`.venv`, sau cùng là `python` trên PATH. Khởi động lại backend sau khi đổi cấu hình.

Ưu tiên RapidOCR ONNX 1.4.x như bản desktop. Nếu không khởi tạo được, thử
PaddleOCR 2.x đã cài sẵn; requirements không cài PaddleOCR vì kích thước lớn.
Model tùy chọn đặt trong `models/` với ba tên như trong `initialize_ocr()`.
Nếu thiếu bộ model này, dùng model đi kèm RapidOCR.

## Luồng xử lý

- ZXing thử barcode trước, chỉ dừng khi khớp danh sách Excel nếu đã tải Excel.
- Python sửa EXIF, giới hạn kích thước, thử pyzbar rồi RapidOCR.
- Quét ảnh gốc trước, sau đó tối đa ba biến thể tập trung cho tem SN: tăng nét,
  phóng to vừa phải và CLAHE. Các nhãn model/ngày/công suất và chuỗi không có
  hình dạng SN bị loại sớm để tránh trả về chữ linh tinh. Ngân sách vòng quét
  là 15 giây; Java vẫn giữ timeout 60 giây làm lớp an toàn. Đây là ngân sách
  cho vòng thử biến thể, không phải cam kết tổng thời gian xử lý.
- Dừng sớm khi khớp nguyên vẹn SN Excel. Chuỗi cắt hoặc sửa O/I/B chỉ là gợi ý
  trên giao diện; không tự động xuất theo kết quả gần đúng.
- Giới hạn ONNX intra-op ở tối đa 4 luồng, inter-op ở 1 luồng để tránh tranh
  chấp CPU giữa các session; giữ bộ model và các biến thể tiền xử lý.
- Desktop và điện thoại cùng gọi `/api/ocr`; backend luôn chạy chung `ocr.py`
  và cùng danh sách SN Excel. Chỉ gửi ảnh để quét khi bấm Kiểm tra & tự động
  xuất file (trừ khi nhập SN thủ công). Khớp nguyên vẹn thì xuất; không khớp
  thì hiển thị SN quét gốc ở dòng đầu, tối đa 5 gợi ý Excel đạt 80% trở lên ở
  các dòng tiếp theo.
- Trước khi gọi `/api/report/generate`, trình duyệt nén mỗi ảnh về tối đa
  1400px; WordService tiếp tục xử lý EXIF và giới hạn ảnh nhúng ở 1000px.
  Điều này giảm thời gian upload trên điện thoại mà không thay đổi mẫu Word.
- Ảnh chụp từ camera được đọc pixel thô bằng `createImageBitmap` rồi áp dụng
  ma trận EXIF 1–8 đúng một lần; vì vậy chụp ngang hay dọc đều giữ đúng chiều
  trong preview, OCR và file Word.
- Ảnh OCR lớn hơn khoảng 1,5MB được giảm còn tối đa 1600px trước khi upload;
  ảnh dọc được thử thêm hai hướng xoay 90°/270° trong `ocr.py` để dòng SN
  không phụ thuộc cách cầm điện thoại.
- `ocr.py` quét vùng trung tâm của ảnh slot 2 trước, gồm barcode và dòng SN;
  hậu tố nhãn `S-No` bị dính thành `NO151...` được tách thành `151...` trước
  khi đối chiếu Excel. Các slot 1, 3 và 4 không gọi OCR.
- Fallback Tesseract.js khi server lỗi. Nếu không đọc được SN, hiện bảng
  chọn/nhập SN, không tự tạo báo cáo thiếu serial.
- Hộp thoại Windows dùng script UTF-8 BOM để giữ đường dẫn tiếng Việt.
  Vị trí folder 3/4 được nhớ chung trong phiên trang. Bộ chọn file ảnh 1/2
  nhớ vị trí riêng và gợi ý ảnh tồn tại kế tiếp; tải lại trang xóa các vị trí này.

API vẫn trả `{success, candidates, method, rawText}` khi thành công.
Lỗi engine trả `success: false`, không bị hiểu nhầm thành chuỗi serial.

## Kiểm tra

```powershell
.venv/Scripts/python.exe -m unittest discover -s tests -v
.venv/Scripts/python.exe ocr.py image.jpg serials.json
```

`serials.json` là mảng SN, ví dụ `["AB1234567890"]`, có thể bỏ qua.
Độ chính xác với ảnh mờ cần đánh giá trên bộ ảnh thiết bị thực tế.

API RapidOCR tham chiếu: https://rapidai.github.io/RapidOCRDocs/v1.4.4/install_usage/api/RapidOCR/
