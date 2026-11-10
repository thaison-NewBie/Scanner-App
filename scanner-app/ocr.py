"""
Scan barcode + RapidOCR (PaddleOCR 2.x dự phòng).
Gọi CLI: python ocr.py <image_path> [serials_json_path]
stdout: một JSON {candidates, method, error}; log engine chuyển sang stderr.
OCR ảnh gốc trước, sau đó chỉ thử một số biến thể có ích cho chữ SN mờ.

scan_image() là lõi dùng chung cho CLI và ocr_server (daemon nạp model 1 lần).
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
                    # text_score thấp hơn mặc định để giữ chuỗi mờ; shape-filter
                    # ở extract_serials loại nhiễu thay cho ngưỡng cao. Độ phân
                    # giải det đổi động giữa lượt quét (xem FAST/DEEP_DET_LIMIT).
                    ocr_engine = RapidOCR(
                        intra_op_num_threads=min(4, os.cpu_count() or 1),
                        inter_op_num_threads=1,
                        det_limit_side_len=DEEP_DET_LIMIT,
                        det_limit_type='min',
                        det_box_thresh=0.4,
                        text_score=0.35,
                        **options)
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

# Ngân sách vòng quét sau khi engine đã sẵn sàng (giây). Java giữ timeout
# 60s làm lớp an toàn khi engine phải nạp model lần đầu.
DEADLINE_SECONDS = 12.0

# Hai tầng độ phân giải phát hiện chữ:
# - FAST_DET_LIMIT: lượt quét đầu (vùng giữa) chạy nhanh để ăn ngay ảnh rõ.
# - DEEP_DET_LIMIT: quét sâu chỉ chạy khi lượt đầu chưa khớp; cao hơn giúp
#   phát hiện chữ SN nhỏ trên tem. Có thể chỉnh theo CPU máy chủ qua biến
#   môi trường SCANNER_OCR_DET_LIMIT (mặc định 960; 1280 nhận diện tốt hơn
#   nhưng mỗi lượt quét chậm hơn đáng kể trên CPU yếu).
FAST_DET_LIMIT = 736.0
DEEP_DET_LIMIT = float(os.environ.get('SCANNER_OCR_DET_LIMIT', '960'))

# Các ký tự OCR thường đọc nhầm sang nhau. Khi có Excel, chuỗi đọc được sẽ
# được "chuẩn hoá" theo bảng này trước khi so khớp nên ảnh rõ nhưng đọc nhầm
# 1-2 ký tự vẫn tự xác nhận được mà không cần quét lại.
CONFUSION_MAP = {'O': '0', 'I': '1', 'B': '8', 'S': '5', 'Z': '2', 'G': '6'}


def canonical(value: str) -> str:
    return ''.join(CONFUSION_MAP.get(ch, ch) for ch in value)


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

def run_ocr_on_pil(pil_img: Image.Image, det_limit=None) -> list:
    # RapidOCR đọc limit_side_len của TextDetector mỗi lần gọi, nên có thể
    # hạ nhanh cho lượt đầu rồi nâng lại khi quét sâu.
    if det_limit is not None and OCR_BACKEND == 'rapid' and ocr_engine is not None:
        ocr_engine.text_det.limit_side_len = det_limit
    # Hai engine nhận ndarray theo thứ tự màu BGR.
    img_np = np.array(pil_img.convert('RGB'))[:, :, ::-1].copy()
    with redirect_stdout(sys.stderr):
        if OCR_BACKEND == 'rapid':
            result, _ = ocr_engine(img_np)
            lines = [(line[1], float(line[2])) for line in (result or [])]
        else:
            result = ocr_engine.ocr(img_np, cls=True)
            lines = [(line[1][0], float(line[1][1])) for line in ((result or [None])[0] or [])]
    # Engine đã lọc theo text_score (0.35); chỉ bỏ các dòng gần như chắc chắn sai.
    return sorted((item for item in lines if item[1] > 0.30), key=lambda item: -item[1])


def looks_like_serial(value: str, known_serials=None) -> bool:
    """Giữ lại chuỗi có hình dạng SN, loại model/ngày/thông số linh tinh."""
    value = str(value or '').strip().upper()
    if known_serials and value in known_serials:
        return True
    if not 6 <= len(value) <= 30 or not any(c.isdigit() for c in value):
        return False
    if re.match(r'^(?:19|20)\d{6}$', value):
        return False  # ngày YYYYMMDD bị OCR nhầm thành SN
    if any(value.startswith(prefix) for prefix in NOISE_PREFIXES):
        return False
    digit_count = sum(c.isdigit() for c in value)
    # SN ngắn hoặc ít chữ số vẫn cho qua để người dùng xem ở giao diện;
    # Excel là nguồn xác nhận cuối cùng nên không sợ tự xuất sai.
    if digit_count < 2 or digit_count / len(value) < 0.15:
        return False
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
    Ưu tiên biến thể rẻ và hiệu quả; xoay 90/270 chỉ chạy cuối vì tốn CPU nhất.
    """
    w, h = pil_img.size

    yield 'enhanced', enhance_for_ocr(pil_img)

    # Phóng to vừa phải cho tem SN nhỏ, tránh tạo ảnh 3600–4800 px quá chậm.
    scale = min(1.5, 2400 / max(w, h))
    if scale > 1.02:
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

    # Xoay cho ảnh cầm máy dọc (đắt nhất nên để cuối cùng).
    portrait = h > (w * 1.12)
    if portrait:
        yield 'rotate90', pil_img.rotate(90, expand=True)
        yield 'rotate270', pil_img.rotate(270, expand=True)


# ── LÕI QUÉT (dùng chung cho CLI và ocr_server) ──────────────────────────────

def scan_image(pil_original: Image.Image, known_serials=None, deadline_seconds: float = DEADLINE_SECONDS):
    """
    Trả dict {candidates, method, error}. Không in ra stdout.
    Thứ tự: barcode (cả ảnh + vùng giữa) → OCR vùng giữa → biến thể vùng giữa
    → OCR cả ảnh (fallback khi tem SN không nằm giữa) → biến thể cả ảnh.
    """
    known_serials = {str(s).strip().upper() for s in (known_serials or ()) if str(s).strip()}
    deadline = time.monotonic() + deadline_seconds

    # ── GIAI ĐOẠN 1: Barcode (nhanh, chính xác nhất) ────────────────────
    center = crop_serial_center(pil_original)
    barcode_results = deduplicate(scan_barcode(pil_original) + scan_barcode(center))
    matched = exact_matches(barcode_results, known_serials)
    if matched or (barcode_results and not known_serials):
        return {"candidates": matched or barcode_results, "method": "barcode", "error": None}

    if OCR_BACKEND is None:
        try:
            initialize_ocr()
        except Exception as exc:
            if barcode_results:
                return {"candidates": barcode_results, "method": "barcode", "error": None}
            return {"candidates": [], "method": "none", "error": str(exc)}

    # ── GIAI ĐOẠN 2: OCR theo thứ tự vùng/biến thể ───────────────────────
    all_serials = list(barcode_results)

    def passes():
        # Lượt đầu chạy nhanh (det 736) để ảnh rõ khớp trong ~1-2s.
        yield 'center', center, FAST_DET_LIMIT
        # Quét sâu chỉ chạy khi lượt đầu chưa khớp Excel.
        for name, img in build_image_variants(center):
            yield name, img, DEEP_DET_LIMIT
        # Fallback: tem SN có thể không nằm ở giữa (ảnh rõ nhưng crop cứng
        # cắt mất dòng SN) → quét cả ảnh gốc và một biến thể nâng tương phản.
        yield 'full', pil_original, DEEP_DET_LIMIT
        yield 'full_enhanced', enhance_for_ocr(pil_original), DEEP_DET_LIMIT

    for name, img, det_limit in passes():
        if time.monotonic() >= deadline:
            break
        raw = run_ocr_on_pil(img, det_limit)
        serials = extract_serials(raw, known_serials)
        matched = exact_matches(serials, known_serials)
        if matched:
            # Chỉ thoát sớm khi khớp Excel, tránh dừng ở mã model/nhiễu.
            return {"candidates": matched, "method": f"ocr_{name}_exact", "error": None}
        all_serials.extend(serials)

    return {"candidates": deduplicate(all_serials),
            "method": 'ocr_deep' if all_serials else 'ocr_no_result',
            "error": None}


# ── MAIN (CLI) ────────────────────────────────────────────────────────────────

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

    output = scan_image(pil_original, known_serials)
    print(json.dumps(output, ensure_ascii=False))
    sys.exit(0)


def exact_matches(candidates, known_serials):
    """Chỉ xác nhận chuỗi khớp nguyên vẹn; chuỗi gần giống cần người dùng chọn.
    Khớp thêm qua bảng ký tự dễ nhầm (O/0, I/1, B/8, S/5, Z/2, G/6) khi đã có
    Excel: chuỗi chuẩn hoá trùng đúng một dòng Excel thì tính là khớp chính xác."""
    known = set(known_serials or ())
    direct = [candidate for candidate in candidates if candidate in known]
    if direct:
        return deduplicate(direct)
    if not known:
        return []
    canonical_index = {}
    ambiguous = set()
    for serial in known:
        key = canonical(serial)
        if key in canonical_index:
            ambiguous.add(key)
        else:
            canonical_index[key] = serial
    result = []
    for candidate in candidates:
        key = canonical(candidate)
        if key in canonical_index and key not in ambiguous:
            result.append(canonical_index[key])
    return deduplicate(result)


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
