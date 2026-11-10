"""
OCR daemon: nạp model ONNX MỘT lần rồi nhận ảnh qua HTTP trên localhost.
Giúp /api/ocr không phải trả phí nạp model (~4-8s) cho từng lần quét.

Chạy:  python ocr_server.py [--port 18081] [--host 127.0.0.1]
POST /ocr  {"image": "<base64 bytes>", "serials": ["SN1", ...]}
    -> {"candidates": [...], "method": "...", "error": null}
GET  /health -> {"ok": true, "backend": "rapid", "error": null}
"""
import argparse
import base64
import io
import json
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import ocr  # noqa: E402

# RapidOCR không an toàn đa luồng; xử lý tuần tự nhưng vẫn nhận song song
# nhiều kết nối (điện thoại khác nhau cùng lúc sẽ xếp hàng).
_ENGINE_LOCK = threading.Lock()
_INIT_ERRORS = []


def ensure_engine() -> bool:
    if ocr.OCR_BACKEND is None:
        try:
            ocr.initialize_ocr()
        except Exception as exc:
            _INIT_ERRORS.append(str(exc))
    return ocr.OCR_BACKEND is not None


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):  # im lặng, không ghi access log
        pass

    def _reply(self, code: int, obj: dict):
        body = json.dumps(obj, ensure_ascii=False).encode('utf-8')
        self.send_response(code)
        self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == '/health':
            ready = ensure_engine()
            self._reply(200 if ready else 500, {
                "ok": ready,
                "backend": ocr.OCR_BACKEND,
                "error": _INIT_ERRORS[-1] if (not ready and _INIT_ERRORS) else None,
            })
        else:
            self.send_response(404)
            self.end_headers()

    def do_POST(self):
        if self.path != '/ocr':
            self.send_response(404)
            self.end_headers()
            return
        try:
            length = int(self.headers.get('Content-Length', '0'))
            payload = self.rfile.read(length)
            request = json.loads(payload)
            image_bytes = base64.b64decode(request['image'])
            serials = {str(s).strip().upper() for s in request.get('serials', []) if str(s).strip()}

            with _ENGINE_LOCK:
                if not ensure_engine():
                    error = _INIT_ERRORS[-1] if _INIT_ERRORS else 'OCR engine không khởi tạo được'
                    self._reply(500, {"candidates": [], "method": "none", "error": error})
                    return

                pil_img = ocr.Image.open(io.BytesIO(image_bytes))
                pil_img = ocr.ImageOps.exif_transpose(pil_img)
                if pil_img.mode not in ('RGB', 'RGBA'):
                    pil_img = pil_img.convert('RGB')
                elif pil_img.mode == 'RGBA':
                    background = ocr.Image.new('RGB', pil_img.size, (255, 255, 255))
                    background.paste(pil_img, mask=pil_img.split()[3])
                    pil_img = background
                if max(pil_img.size) > ocr.MAX_DIM:
                    pil_img.thumbnail((ocr.MAX_DIM, ocr.MAX_DIM), ocr.Image.LANCZOS)

                output = ocr.scan_image(pil_img, serials)
            self._reply(200, output)
        except Exception as exc:
            self._reply(500, {"candidates": [], "method": "none", "error": str(exc)})


def main():
    parser = argparse.ArgumentParser(description='OCR daemon cho Scanner App')
    parser.add_argument('--port', type=int, default=18081)
    parser.add_argument('--host', default='127.0.0.1')
    args = parser.parse_args()

    # Nạp model trước khi nhận request để lần quét đầu tiên đã nhanh.
    ensure_engine()

    server = ThreadingHTTPServer((args.host, args.port), Handler)
    server.daemon_threads = True
    server.serve_forever()


if __name__ == '__main__':
    main()
