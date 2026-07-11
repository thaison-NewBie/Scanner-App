"""
ocr.py — Chiến lược SIÊU QUÉT v2 (tối ưu ảnh mờ + tốc độ)
============================================================
Gọi bởi Java ProcessBuilder:
    python ocr.py <image_path>

Kết quả (JSON ra stdout):
    {"candidates": ["SN1", "SN2", ...], "method": "barcode|ocr", "error": null}

Chiến lược v2:
  1. Quét Barcode (pyzbar nếu có) — 100% chính xác, nhanh nhất
  2. OCR ảnh gốc (đã sửa EXIF + tối ưu độ tương phản thích ứng)
  3. Nếu thất bại → thử các biến thể tiền xử lý chuyên sâu cho ảnh mờ:
       - Adaptive contrast (CLAHE-like)
       - Unsharp mask mạnh
       - Otsu threshold (ảnh bị ngược màu)
       - Deblur kernel
       - Phóng to 2x
  4. Tổng hợp kết quả, sắp xếp theo confidence, trả về sớm nhất có thể
"""
import sys
import re
import json
import warnings
import logging
import os
import io

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

# Khởi tạo PaddleOCR (1 lần duy nhất)
try:
    from paddleocr import PaddleOCR
    ocr_engine = PaddleOCR(
        use_angle_cls=True,
        lang="en",
        use_gpu=False,
        show_log=False,
    )
    HAS_OCR = True
except Exception:
    ocr_engine = None
    HAS_OCR = False

# ── Cấu hình ──────────────────────────────────────────────────────────────────
SN_PATTERN = re.compile(r'[A-Z0-9]{6,60}')

NOISE_WORDS = {
    "NOKIA", "MADE", "FINLAND", "PMU", "STATUS", "INDOOR", "USE", "ONLY",
    "VIETNAM", "AUTOMATIC", "TRANSFER", "SWITCH", "MODEL", "ANPHAT", "JSC",
    "MANUFACTURE", "DATE", "POWER", "ASIA", "RUN", "ALM",
    "IMEI", "SIM", "FCC", "UL", "CLASS", "TYPE", "INPUT", "OUTPUT",
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
            clean = re.sub(r'[^A-Z0-9]', '', text.upper())
            if len(clean) >= 6:
                results.append(clean)
        return results
    except Exception:
        return []


# ── OCR ───────────────────────────────────────────────────────────────────────

def run_ocr_on_pil(pil_img: Image.Image) -> list:
    if not HAS_OCR:
        return []
    try:
        if pil_img.mode != 'RGB':
            pil_img = pil_img.convert('RGB')
        img_np = np.array(pil_img)
        result = ocr_engine.ocr(img_np, cls=True)
        candidates = []
        if result and result[0]:
            for line in result[0]:
                text, conf = line[1][0], line[1][1]
                if float(conf) > 0.45:  # Nới lỏng ngưỡng từ 0.5 → 0.45 cho ảnh mờ
                    candidates.append((text, float(conf)))
        return candidates
    except Exception:
        return []


def extract_serials(raw_candidates: list) -> list:
    results = []
    seen_set = set()
    for text, _ in raw_candidates:
        text_upper = text.upper()

        # Loại từ nhiễu
        for noise in NOISE_WORDS:
            text_upper = text_upper.replace(noise, " ")

        # Thử cả hai phiên bản: gốc và sau khi replace O→0, I→1
        for variant in [text_upper, text_upper.replace('O', '0').replace('I', '1')]:
            for match in SN_PATTERN.findall(re.sub(r'[^A-Z0-9\s]', ' ', variant)):
                if len(match) >= 6 and any(c.isdigit() for c in match):
                    if match not in seen_set:
                        seen_set.add(match)
                        results.append(match)
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


def build_image_variants(pil_img: Image.Image) -> list:
    """
    Tạo các biến thể tiền xử lý cho ảnh mờ/không hoàn thiện.
    Ưu tiên các biến thể hiệu quả nhất với ảnh serial mờ.
    """
    variants = []
    w, h = pil_img.size

    # V1: Unsharp mask mạnh + tương phản cao
    variants.append(("enhanced", enhance_for_ocr(pil_img)))

    # V2: Grayscale + Otsu threshold → xử lý ảnh tối/sáng không đều
    gray = pil_img.convert('L')
    if HAS_CV2:
        gray_np = np.array(gray)
        _, otsu = cv2.threshold(gray_np, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)
        # Morphological closing để nối ký tự bị đứt
        kernel = np.ones((2, 2), np.uint8)
        closed = cv2.morphologyEx(otsu, cv2.MORPH_CLOSE, kernel)
        variants.append(("otsu_morph", Image.fromarray(closed).convert('RGB')))
        # V3: Đảo màu Otsu (cho ảnh text trắng nền tối)
        variants.append(("otsu_inv", Image.fromarray(cv2.bitwise_not(closed)).convert('RGB')))
        # V4: CLAHE (tăng tương phản cục bộ)
        clahe = cv2.createCLAHE(clipLimit=3.0, tileGridSize=(8, 8))
        clahe_img = clahe.apply(gray_np)
        variants.append(("clahe", Image.fromarray(clahe_img).convert('RGB')))
    else:
        # Không có OpenCV: dùng PIL Otsu-like
        arr = np.array(gray)
        threshold = int(arr.mean())
        bw = gray.point(lambda x: 0 if x < threshold else 255, '1').convert('RGB')
        variants.append(("bw_auto", bw))
        bw_inv = gray.point(lambda x: 255 if x < threshold else 0, '1').convert('RGB')
        variants.append(("bw_inv", bw_inv))

    # V5: Phóng to 2x (quan trọng cho serial quá nhỏ)
    zoom = pil_img.resize((w * 2, h * 2), Image.LANCZOS)
    variants.append(("zoom2x", zoom))

    # V6: Phóng to + enhance
    zoom_enh = enhance_for_ocr(pil_img.resize((int(w * 1.5), int(h * 1.5)), Image.LANCZOS))
    variants.append(("zoom_enh", zoom_enh))

    return variants


# ── MAIN ──────────────────────────────────────────────────────────────────────

def main():
    if len(sys.argv) < 2:
        fail("Thiếu đường dẫn ảnh")
        sys.exit(0)

    image_path = sys.argv[1]

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

    # ── GIAI ĐOẠN 1: Barcode (nhanh, chính xác nhất) ─────────────────────
    barcode_results = scan_barcode(pil_original)
    if barcode_results:
        ok(barcode_results, "barcode")
        sys.exit(0)

    if not HAS_OCR:
        fail("Không có OCR engine")
        sys.exit(0)

    # ── GIAI ĐOẠN 2: OCR ảnh gốc ─────────────────────────────────────────
    all_serials = []

    raw = run_ocr_on_pil(pil_original)
    serials = extract_serials(raw)
    all_serials.extend(serials)

    if all_serials:
        ok(deduplicate(all_serials), "ocr_original")
        sys.exit(0)

    # ── GIAI ĐOẠN 3: Tiền xử lý chuyên sâu cho ảnh mờ ───────────────────
    for variant_name, variant_img in build_image_variants(pil_original):
        raw = run_ocr_on_pil(variant_img)
        serials = extract_serials(raw)
        if serials:
            # Tìm thấy → trả về ngay, không quét thêm
            ok(deduplicate(serials), f"ocr_{variant_name}")
            sys.exit(0)
        all_serials.extend(serials)

    # Không tìm thấy gì
    ok([], "ocr_no_result")


def deduplicate(lst: list) -> list:
    seen = set()
    result = []
    for item in lst:
        if item not in seen:
            seen.add(item)
            result.append(item)
    return result


if __name__ == "__main__":
    main()
