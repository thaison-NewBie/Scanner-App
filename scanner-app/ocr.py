"""
Scan barcode + RapidOCR (PaddleOCR 2.x dự phòng).
Gọi: python ocr.py <image_path> [serials_json_path]
stdout: một JSON {candidates, method, error}; log engine chuyển sang stderr.
OCR ảnh gốc trước, sau đó chỉ thử một số biến thể có ích cho chữ SN mờ.
"""
import sys
import re
import json
import warnings
import logging
import os
import time
from contextlib import redirect_stdout
from pathlib import Path

# Fix encoding UTF-8 cho Windows
if sys.stdout.encoding != 'utf-8':
    sys.stdout.reconfigure(encoding='utf-8')

# Tắt mọi log rác
warnings.filterwarnings("ignore")
logging.disable(logging.CRITICAL)
os.environ["PADDLE_LOG_LEVEL"]       = "ERROR"
os.environ["FLAGS_use_mkldnn"]       = "0"
os.environ["FLAGS_call_stack_level"] = "0"

from PIL import Image, ImageEnhance, ImageFilter, ImageOps
import numpy as np

try:
    from pillow_heif import register_heif_opener
    register_heif_opener()
except ImportError:
    pass

# Thử import pyzbar (barcode)
try:
    from pyzbar.pyzbar import decode as decode_barcode
    HAS_PYZBAR = True
except Exception:
    HAS_PYZBAR = False

# Thử import OpenCV (tùy chọn — dùng để preprocess ảnh mờ)
try:
    import cv2
    HAS_CV2 = True
except Exception:
    HAS_CV2 = False

# Chỉ khởi tạo AI khi barcode chưa đủ; stdout chỉ dành cho JSON.
ocr_engine = None
OCR_BACKEND = None


def initialize_ocr():
    global ocr_engine, OCR_BACKEND
    errors = []
    for backend in ('rapid', 'paddle'):
        try:
            with redirect_stdout(sys.stderr):
                if backend == 'rapid':
                    from rapidocr_onnxruntime import RapidOCR
                    models = Path(__file__).resolve().parent / 'models'
                    paths = {
                        'det_model_path': models / 'ch_PP-OCRv4_det_infer.onnx',
                        'cls_model_path': models / 'ch_ppocr_mobile_v2.0_cls_infer.onnx',
                        'rec_model_path': models / 'ch_PP-OCRv4_rec_infer.onnx',
                    }
                    options = {k: str(v) for k, v in paths.items()} if all(p.is_file() for p in paths.values()) else {}
                    # Tránh ba session ONNX tạo quá nhiều luồng trên CPU nhiều nhân.
                    ocr_engine = RapidOCR(intra_op_num_threads=min(4, os.cpu_count() or 1),
                                         inter_op_num_threads=1, **options)
                else:
                    from paddleocr import PaddleOCR
                    ocr_engine = PaddleOCR(use_angle_cls=True, lang='en', use_gpu=False, show_log=False)
            OCR_BACKEND = backend
            return
        except Exception as exc:
            errors.append(f'{backend}: {exc}')
    raise RuntimeError('Không khởi tạo được OCR. Cài requirements-ocr.txt. ' + '; '.join(errors))

# ── Cấu hình ──────────────────────────────────────────────────────────────────
NOISE_WORDS = {
    "NOKIA", "MADE", "FINLAND", "PMU", "STATUS", "INDOOR", "USE", "ONLY",
    "VIETNAM", "AUTOMATIC", "TRANSFER", "SWITCH", "MODEL", "ANPHAT", "JSC",
    "MANUFACTURE", "DATE", "POWER", "ASIA", "RUN", "ALM",
    "IMEI", "SIM", "FCC", "UL", "CLASS", "TYPE", "INPUT", "OUTPUT",
}

# Các nhãn thường xuất hiện trên tem nhưng không phải số serial. Chỉ loại
# khi đứng ở đầu chuỗi để không làm hỏng một serial thật có chứa từ này.
NOISE_PREFIXES = {
    "MODEL", "DATE", "POWER", "STATUS", "INPUT", "OUTPUT", "TYPE",
    "CLASS", "PART", "ITEM", "REV", "VER", "VOLT", "AMP", "HZ", "LOT",
    "P/N", "PN", "FCC", "IMEI", "SIM", "NOKIA", "MADE", "FINLAND",
    "VIETNAM", "MANUFACTURE", "AUTOMATIC", "TRANSFER", "SWITCH",
}

# Kích thước tối đa để tăng tốc OCR
MAX_DIM = 1800


def ok(candidates: list, method: str):
    print(json.dumps({"candidates": candidates, "method": method, "error": None},
                     ensure_ascii=False))


def fail(error: str):
    print(json.dumps({"candidates": [], "method": "none", "error": error},
                     ensure_ascii=False))


# ── BARCODE ───────────────────────────────────────────────────────────────────

def scan_barcode(pil_img: Image.Image) -> list:
    if not HAS_PYZBAR:
        return []
    try:
        # Thử ảnh gốc
        codes = decode_barcode(pil_img)
        if not codes and HAS_CV2:
            # Thử ảnh xử lý tương phản cao hơn
            img_np = np.array(pil_img.convert('L'))
            _, thresh = cv2.threshold(img_np, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)
            thresh_pil = Image.fromarray(thresh).convert('RGB')
            codes = decode_barcode(thresh_pil)

        results = []
        for code in codes:
            text = code.data.decode("utf-8", errors="ignore")
            clean = re.sub(r'[^A-Z0-9.]', '', text.upper())
            if len(clean) >= 6:
                results.append(clean)
        return results
    except Exception:
        return []


# ── OCR ───────────────────────────────────────────────────────────────────────

def run_ocr_on_pil(pil_img: Image.Image) -> list:
    # Hai engine nhận ndarray theo thứ tự màu BGR.
    img_np = np.array(pil_img.convert('RGB'))[:, :, ::-1].copy()
    with redirect_stdout(sys.stderr):
        if OCR_BACKEND == 'rapid':
            result, _ = ocr_engine(img_np)
            lines = [(line[1], float(line[2])) for line in (result or [])]
        else:
            result = ocr_engine.ocr(img_np, cls=True)
            lines = [(line[1][0], float(line[1][1])) for line in ((result or [None])[0] or [])]
    return sorted((item for item in lines if item[1] > 0.45), key=lambda item: -item[1])


def looks_like_serial(value: str, known_serials=None) -> bool:
    """Giữ lại chuỗi có hình dạng SN, loại model/ngày/thông số linh tinh."""
    value = str(value or '').strip().upper()
    if known_serials and value in known_serials:
        return True
    if not 8 <= len(value) <= 30 or not any(c.isdigit() for c in value):
        return False
    if re.match(r'^(?:19|20)\d{6}$', value):
        return False  # ngày YYYYMMDD bị OCR nhầm thành SN
    if any(value.startswith(prefix) for prefix in NOISE_PREFIXES):
        return False
    digit_count = sum(c.isdigit() for c in value)
    # SN thường có nhiều chữ số; loại các từ có một con số lẻ như TYPE-A1.
    if digit_count < 3 or digit_count / len(value) < 0.30:
        return False
    # Chuỗi toàn chữ không thể là SN. Chuỗi toàn số vẫn cho phép vì một số
    # dòng thiết bị dùng serial chỉ gồm chữ số (Excel sẽ xác nhận chính xác).
    return True


def expand_serial_label_prefix(value: str, known_serials=None) -> list:
    """Tách tiền tố nhãn bị OCR dính vào số SN, ví dụ NO151... → 151...."""
    value = str(value or '').strip().upper()
    # Nhãn trên thiết bị thường ghi S-No / S.No. OCR đôi khi bỏ "S-" và dính
    # phần NO vào chuỗi số. Nếu chuỗi gốc có trong Excel, giữ nguyên nó; còn
    # lại chỉ trả SN đã tách để giao diện không hiển thị nhãn NO như một mã.
    # O và 0 thường bị lẫn khi đọc chữ "No" trên nhãn. Tuy nhiên nhiều SN
    # thật cũng bắt đầu bằng N0, nên chỉ cắt N0 khi phần còn lại khớp Excel.
    match = re.fullmatch(r'((?:S[-_.]?N[O0]|SN[O0]|N[O0]))(\d{8,30})', value)
    if match:
        prefix, serial = match.groups()
        if known_serials and value in known_serials:
            return [value]
        if prefix.endswith('0') and (not known_serials or serial not in known_serials):
            return [value]
        return [serial]
    return [value]


def extract_serials(raw_candidates: list, known_serials=None) -> list:
    results = []
    seen_set = set()
    for text, _ in raw_candidates:
        text_upper = text.upper()

        # Loại từ nhiễu
        for noise in NOISE_WORDS:
            text_upper = re.sub(r'\b' + re.escape(noise) + r'\b', ' ', text_upper)
        text_upper = re.sub(r'^\s*(?:S\s*/\s*N|SN|SERIAL(?:\s+NUMBER)?)(?:\s*[:#=]\s*|\s+)', '', text_upper)

        # Giữ nguyên O/I/B; gợi ý sửa được tính riêng trên giao diện.
        for match in re.findall(r'[A-Z0-9]+(?:\.[A-Z0-9]+)*', text_upper):
            for candidate in expand_serial_label_prefix(match, known_serials):
                if looks_like_serial(candidate, known_serials) and candidate not in seen_set:
                    seen_set.add(candidate)
                    results.append(candidate)
    return results


# ── TIỀN XỬ LÝ ẢNH MỜ ────────────────────────────────────────────────────────

def enhance_for_ocr(pil_img: Image.Image) -> Image.Image:
    """Tiền xử lý cơ bản: tăng độ nét + tương phản thích ứng."""
    # Unsharp Mask mạnh hơn
    img = pil_img.filter(ImageFilter.UnsharpMask(radius=2, percent=180, threshold=2))
    # Tăng tương phản thích ứng (CLAHE-like với PIL)
    img = ImageEnhance.Contrast(img).enhance(2.2)
    # Tăng độ sắc nét
    img = ImageEnhance.Sharpness(img).enhance(3.0)
    return img


def crop_serial_center(pil_img: Image.Image) -> Image.Image:
    """Lấy vùng trung tâm nơi người dùng đặt tem SN trong ảnh slot 2."""
    w, h = pil_img.size
    if w < 200 or h < 200:
        return pil_img

    # Slot 2 được chụp tập trung vào nhãn SN ở giữa. Bỏ phần viền giúp OCR
    # không mất thời gian đọc model, logo hoặc thông số ở xung quanh.
    left, right = int(w * 0.08), int(w * 0.92)
    top, bottom = int(h * 0.24), int(h * 0.76)
    return pil_img.crop((left, top, right, bottom))


def build_image_variants(pil_img: Image.Image):
    """
    Tạo các biến thể tiền xử lý cho ảnh mờ/không hoàn thiện.
    Ưu tiên các biến thể hiệu quả nhất với ảnh serial mờ.
    """
    w, h = pil_img.size

    # Xử lý tương phản trước vì đa số ảnh dọc đã được EXIF xoay đúng chiều.
    # Nếu chưa khớp, mới thử hai hướng 90°/270° cho ảnh cầm máy dọc.
    portrait = h > (w * 1.12)
    yield 'enhanced', enhance_for_ocr(pil_img)
    if portrait:
        yield 'rotate90', pil_img.rotate(90, expand=True)
        yield 'rotate270', pil_img.rotate(270, expand=True)

    # Ảnh gốc đã được OCR trước đó nên không cần lặp lại các biến thể cũ.

    # Phóng to vừa phải cho tem SN nhỏ, tránh tạo ảnh 3600–4800 px quá chậm.
    scale = min(1.5, 2400 / max(w, h))
    if not portrait and scale > 1.02:
        yield 'zoom', pil_img.resize((int(w * scale), int(h * scale)), Image.LANCZOS)

    gray_np = np.array(pil_img.convert('L'))
    if HAS_CV2:
        clahe = cv2.createCLAHE(clipLimit=2.5, tileGridSize=(8, 8))
        yield 'clahe', Image.fromarray(clahe.apply(gray_np)).convert('RGB')
    else:
        # Fallback nhẹ khi máy không có OpenCV.
        gray = Image.fromarray(gray_np)
        threshold = int(gray_np.mean())
        yield 'bw_auto', gray.point(lambda x: 0 if x < threshold else 255).convert('RGB')


# ── MAIN ──────────────────────────────────────────────────────────────────────

def main():
    if len(sys.argv) < 2:
        fail("Thiếu đường dẫn ảnh")
        sys.exit(0)

    image_path = sys.argv[1]
    known_serials = set()
    if len(sys.argv) > 2:
        with open(sys.argv[2], encoding='utf-8') as serial_file:
            known_serials = {str(s).strip().upper() for s in json.load(serial_file) if str(s).strip()}

    # Đọc ảnh + sửa EXIF
    try:
        if not os.path.exists(image_path):
            fail("File khong ton tai: " + image_path)
            sys.exit(0)
        pil_original = Image.open(image_path)
        pil_original = ImageOps.exif_transpose(pil_original)
        if pil_original.mode not in ('RGB', 'RGBA'):
            pil_original = pil_original.convert('RGB')
        elif pil_original.mode == 'RGBA':
            bg = Image.new('RGB', pil_original.size, (255, 255, 255))
            bg.paste(pil_original, mask=pil_original.split()[3])
            pil_original = bg
        # Giới hạn kích thước
        if max(pil_original.size) > MAX_DIM:
            pil_original.thumbnail((MAX_DIM, MAX_DIM), Image.LANCZOS)
    except Exception as e:
        fail("Loi doc anh: " + str(e))
        sys.exit(0)

    # Slot 2 là ảnh zoom serial: ưu tiên vùng giữa để bỏ qua chữ/nền xung
    # quanh, giảm thời gian xử lý và tránh trả về model/date không liên quan.
    pil_serial = crop_serial_center(pil_original)

    # ── GIAI ĐOẠN 1: Barcode vùng SN (nhanh, chính xác nhất) ─────────────
    barcode_results = scan_barcode(pil_serial)
    matched = exact_matches(barcode_results, known_serials)
    if matched or (barcode_results and not known_serials):
        ok(matched or barcode_results, "barcode")
        sys.exit(0)

    try:
        initialize_ocr()
    except Exception as exc:
        if barcode_results:
            ok(barcode_results, 'barcode')
        else:
            fail(str(exc))
        return

    # ── GIAI ĐOẠN 2: OCR ảnh gốc ─────────────────────────────────────────
    all_serials = list(barcode_results)
    # Dừng quét sâu sớm để UI phản hồi nhanh. Ảnh rõ thường trả về ở lượt OCR đầu.
    deadline = time.monotonic() + 15

    raw = run_ocr_on_pil(pil_serial)
    serials = extract_serials(raw, known_serials)
    all_serials.extend(serials)

    matched = exact_matches(serials, known_serials)
    if matched:
        ok(matched, "ocr_original_exact")
        sys.exit(0)

    # ── GIAI ĐOẠN 3: Tiền xử lý chuyên sâu cho ảnh mờ ───────────────────
    for variant_name, variant_img in build_image_variants(pil_serial):
        if time.monotonic() >= deadline:
            break
        raw = run_ocr_on_pil(variant_img)
        serials = extract_serials(raw, known_serials)
        matched = exact_matches(serials, known_serials)
        if matched:
            # Chỉ thoát sớm khi khớp Excel, tránh dừng ở mã model/nhiễu.
            ok(matched, f"ocr_{variant_name}_exact")
            sys.exit(0)
        all_serials.extend(serials)

    # Không tìm thấy gì
    ok(deduplicate(all_serials), 'ocr_deep' if all_serials else 'ocr_no_result')


def exact_matches(candidates, known_serials):
    """Chỉ xác nhận chuỗi khớp nguyên vẹn; chuỗi gần giống cần người dùng chọn."""
    direct = [candidate for candidate in candidates if candidate in known_serials]
    if direct:
        return deduplicate(direct)
    # Chuỗi cắt hoặc sửa ký tự chỉ là gợi ý, cần người dùng xác nhận ở giao diện.
    return []


def deduplicate(lst: list) -> list:
    seen = set()
    result = []
    for item in lst:
        if item not in seen:
            seen.add(item)
            result.append(item)
    return result


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        fail(str(exc))
