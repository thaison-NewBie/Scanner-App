# Chạy thử Scanner App bằng Docker trên Ubuntu

Hướng dẫn này dành cho Ubuntu đã có Docker Engine và Docker Compose. Các lệnh chạy trong Terminal của Ubuntu, tại máy IBM hoặc máy ảo Ubuntu trên IBM.

Frontend nằm trong JAR của backend. Một container chạy JAR, Python và `ocr.py`. Không cần cài Microsoft Office, Java, Maven hoặc Python riêng trên Ubuntu khi dùng cách này.

- `Dockerfile`: chuẩn bị môi trường Java/Python và build app.
- `docker-compose.yml`: cấu hình cổng truy cập, thư mục dữ liệu và tự khởi động lại container.
- `.env`: cấu hình riêng của máy triển khai, không đưa lên GitHub.

Cấu hình được chuẩn bị để thử bằng dữ liệu mẫu trên Ubuntu. Cần build và thử OCR/Word trên máy IBM thật để xác nhận thư viện và tốc độ; chưa có kết quả kiểm thử container trên máy đó.

## 1. Kiểm tra công cụ

```bash
docker --version
docker compose version
docker info
git --version
```

Nếu thiếu `docker compose`, cần cài Compose plugin theo [hướng dẫn chính thức](https://docs.docker.com/compose/install/linux/). Nếu `docker info` báo không kết nối được Docker daemon, kiểm tra `sudo systemctl status docker`. Nếu chỉ báo không có quyền truy cập Docker socket và bạn có quyền sudo, có thể dùng `sudo docker ...` cho các lệnh Docker bên dưới. Không cần mở quyền socket cho mọi người.

## 2. Lấy mã nguồn từ GitHub

Nếu chưa có repository trên Ubuntu:

```bash
cd ~
git clone https://github.com/thaison-NewBie/Scanner-App.git
cd ~/Scanner-App/scanner-app
```

Nếu đã clone tại vị trí đó, dùng:

```bash
cd ~/Scanner-App/scanner-app
git pull --ff-only
```

Các bước tiếp theo đều chạy trong thư mục `scanner-app`, nơi có `Dockerfile` và `docker-compose.yml`. Nếu GitHub yêu cầu đăng nhập, dùng tài khoản có quyền đọc repository; không ghi token vào URL hoặc file cấu hình.

## 3. Chuẩn bị thư mục thử và cấu hình

```bash
mkdir -p docker-data/images/front docker-data/images/back docker-data/reports
test -f .env || cp .env.example .env
id -u
id -g
nano .env
```

Đổi `SCANNER_UID` thành số từ `id -u`, `SCANNER_GID` thành số từ `id -g`. Giữ các giá trị khác mặc định cho lần thử đầu. Trong Nano, nhấn Ctrl+O, Enter để lưu rồi Ctrl+X để thoát.

Chép một số ảnh mẫu mặt trước vào `docker-data/images/front`, mặt sau vào `docker-data/images/back`. File Word sẽ nằm trong `docker-data/reports` của Ubuntu. Các thư mục này và `.env` được Git bỏ qua.

Không tạo thư mục bằng sudo nếu đang dùng tài khoản Ubuntu thường: container dùng UID/GID của tài khoản đó để ghi Word. Thư mục ảnh được mount chỉ đọc. Thư mục báo cáo được mount cho phép ghi. Đây là các thư mục thử độc lập, chưa kết nối NAS.

## 4. Build và khởi động

```bash
docker compose config --quiet
docker compose up -d --build
docker compose ps
docker compose logs --tail=100 scanner
curl -I http://127.0.0.1:18080/
```

`config --quiet` không in lỗi khi cấu hình hợp lệ. Lần build đầu cần Internet để tải image, thư viện Maven, gói Ubuntu và thư viện OCR; có thể mất nhiều phút. Dockerfile có bước kiểm tra tải thư viện/model OCR, nhưng vẫn cần thử quét ảnh thực tế.

Sau khi khởi động, trạng thái mong đợi là `Up` và sau đó `healthy`; lệnh curl trả HTTP 200. Nếu container liên tục khởi động lại, xem log trước khi tiếp tục. Không dừng container của web nội bộ khác; Compose này dùng project riêng `scanner-test` và cổng mặc định 18080. Nếu cổng đã có app dùng, đổi `SCANNER_PORT` trong `.env` sang cổng còn trống rồi chạy lại.

Nếu Ubuntu có giao diện trình duyệt, mở **http://127.0.0.1:18080/** ngay trên Ubuntu. Địa chỉ này chưa dùng được từ laptop hoặc iPhone. Nếu Ubuntu chỉ có Terminal, dùng curl kiểm tra rồi làm bước 6 để mở bằng thiết bị khác.

## 5. Thử luồng tạo Word

Khi mở được app, có hai cách nạp Excel PO: bấm **Chọn file Excel** để duyệt ổ đĩa bên trong container, hoặc **Tải từ điện thoại** từ thiết bị đang mở trình duyệt. Cấu hình Compose giới hạn quyền duyệt trong các thư mục đã mount, nên file Excel phải nằm trong `/data/reports` (hoặc đổi cấu hình mount/`reports-unrestricted`). Container không tự đọc ổ đĩa laptop.

Trong bộ chọn thư mục của web, chọn:

- Ảnh mặt trước: `/data/images/front`.
- Ảnh mặt sau: `/data/images/back`.
- Nơi lưu Word: `/data/reports`.

Đây là đường dẫn bên trong container, tương ứng các thư mục `docker-data` đã tạo trên Ubuntu. Cấu hình thử chỉ duyệt trong hai thư mục được mount. Muốn dùng thư mục khác trên Ubuntu, đổi `SCANNER_IMAGES_DIR` hoặc `SCANNER_REPORTS_DIR` trong `.env` sang đường dẫn tuyệt đối đã tồn tại rồi chạy `docker compose up -d`.

Chọn/chụp ảnh tổng quan ở ô 1, ảnh SN ở ô 2, Random ảnh 3/4 rồi xuất Word. Kiểm tra file trong `docker-data/reports`, định dạng và thông báo khi xuất lại cùng tên. Cũng thử một ảnh dọc và một ảnh ngang. Tốc độ OCR cần đo trên CPU IBM thực tế.

## 6. Mở cho iPhone trong mạng công ty

Lấy địa chỉ IPv4 của Ubuntu:

```bash
ip -br -4 addr
```

Chọn IP card mạng LAN của Ubuntu, không chọn `127.0.0.1` hoặc mạng bridge của Docker. Nếu Ubuntu là máy ảo, nó cần có mạng mà iPhone truy cập được, thường là bridge/external switch; mạng NAT riêng cần cấu hình chuyển tiếp cổng. Cần biết phần mềm máy ảo trước khi thay đổi thiết lập này.

Mở lại `.env`, đổi `SCANNER_BIND_ADDRESS=127.0.0.1` thành IP LAN thật của Ubuntu, rồi chạy:

```bash
docker compose up -d
docker compose ps
```

Trên iPhone cùng mạng, mở `http://IP_UBUNTU:18080/`, thay `IP_UBUNTU` bằng số thật. Dùng cổng đã chọn nếu khác 18080. Không dùng IP NAS hoặc `localhost` trên điện thoại. Sau khi đổi bind address, cũng kiểm tra curl bằng IP LAN thay cho 127.0.0.1.

App hiện chưa có đăng nhập, vì vậy chỉ thử trong phạm vi mạng nội bộ được phép; không cấu hình port forwarding ra Internet. Nếu điện thoại không vào được, kiểm tra đường mạng máy ảo, mạng Wi-Fi khách/cô lập thiết bị và firewall cho cổng đã chọn. Docker có thể làm lưu lượng cổng đã publish đi vòng qua quy tắc UFW: không coi một lệnh UFW là đủ để giới hạn truy cập. Xem [Docker và firewall](https://docs.docker.com/engine/network/packet-filtering-firewalls/) hoặc nhờ người quản lý mạng kiểm tra phạm vi truy cập thực tế.

Đổi IP Ubuntu có thể khiến cổng không bind được; cập nhật `.env` hoặc dùng IP cố định/DHCP reservation phù hợp mạng công ty. Chính sách `unless-stopped` giúp container tự chạy lại khi Docker khởi động, trừ khi đã được dừng chủ động. Cần kiểm tra Docker tự khởi động khi Ubuntu reboot.

## 7. Kết nối NAS sau khi thử bằng dữ liệu mẫu

Linux cần mount SMB từ NAS thành thư mục trên Ubuntu trước. Sau khi mount thành công với tài khoản được cấp quyền, đặt `SCANNER_IMAGES_DIR` và `SCANNER_REPORTS_DIR` trong `.env` thành đường dẫn mount tương ứng và chạy lại `docker compose up -d`. Trong app vẫn dùng `/data/images` và `/data/reports`.

Không nhập đường dẫn Windows `C:\...` hoặc `\\192.168.1.8\...` vào container Linux. Mật khẩu SMB thuộc cấu hình mount trên Ubuntu, không đưa vào GitHub, Dockerfile hoặc JavaScript. Quyền trên NAS và UID/GID khi mount phải cho phép đọc ảnh, ghi báo cáo. Cần kiểm tra NAS đã mount trước khi khởi động app; một thư mục mount trống chưa bảo đảm SMB đang kết nối.

## 8. Dừng, mở lại và cập nhật app

Dừng riêng Scanner:

```bash
docker compose stop
```

Mở lại:

```bash
docker compose up -d
```

Cập nhật sau khi có phiên bản mới trên GitHub:

```bash
git pull --ff-only
docker compose up -d --build
```

Thay container không xóa ảnh/Word đã lưu trong các thư mục bind mount. Đây không thay thế sao lưu dữ liệu. Không chạy lệnh dọn toàn bộ Docker trên máy đang có ứng dụng khác.

Compose đọc các giá trị từ `.env`; cú pháp và cách kiểm tra tham khảo [tài liệu Docker Compose](https://docs.docker.com/compose/how-tos/environment-variables/variable-interpolation/).
