import contextlib
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import ocr
from PIL import Image


class ScanTests(unittest.TestCase):
    def test_preserves_valid_letters_and_dots(self):
        candidates = ocr.extract_serials([('S/N: ABIO123.45', 0.95)])
        self.assertEqual(candidates[0], 'ABIO123.45')
        self.assertNotIn('A810123.45', candidates)

    def test_noise_word_inside_serial_is_preserved(self):
        self.assertIn('RUN123456', ocr.extract_serials([('RUN123456', 0.9)]))
        self.assertIn('SN12345678', ocr.extract_serials([('SN12345678', 0.9)]))

    def test_filters_labels_and_dates_but_keeps_serial_shape(self):
        candidates = ocr.extract_serials([
            ('MODEL98765', 0.9), ('POWER220V', 0.9), ('DATE20240101', 0.9),
            ('N0094771129401', 0.9), ('L1164809484', 0.9)
        ])
        self.assertEqual(candidates, ['N0094771129401', 'L1164809484'])

    def test_strips_s_no_label_when_ocr_joins_it_to_serial(self):
        self.assertEqual(
            ocr.extract_serials([('NO151771016782', 0.98)], {'151771016782'}),
            ['151771016782']
        )
        self.assertEqual(
            ocr.extract_serials([('N0151771016782', 0.98)], {'151771016782'}),
            ['151771016782']
        )

    def test_crop_serial_center_ignores_image_edges(self):
        cropped = ocr.crop_serial_center(Image.new('RGB', (1000, 800), 'white'))
        self.assertEqual(cropped.size, (840, 416))

    def test_portrait_scan_includes_rotated_variants_last(self):
        variants = list(ocr.build_image_variants(Image.new('RGB', (80, 140), 'white')))
        names = [name for name, _ in variants]
        self.assertEqual(names[:2], ['enhanced', 'zoom'])
        self.assertEqual(names[-2:], ['rotate90', 'rotate270'])
        self.assertEqual(variants[1][1].size, (120, 210))

    def test_exact_match_before_substring(self):
        self.assertEqual(ocr.exact_matches(['AB12345678'], {'AB12345678', '12345678'}), ['AB12345678'])

    def test_substring_matching_does_not_invent_fuzzy_match(self):
        self.assertEqual(ocr.exact_matches(['XXAB12345678YY'], {'AB12345678'}), [])
        self.assertEqual(ocr.exact_matches(['AB12345679'], {'AB12345678'}), [])

    def test_short_alphanumeric_serial_is_accepted(self):
        # SN ngắn 6 ký tự, ít chữ số vẫn phải hiện ra cho người dùng xác nhận.
        self.assertIn('AB12CD', ocr.extract_serials([('AB12CD', 0.9)]))

    def test_confusion_characters_match_excel_serials(self):
        # O đọc nhầm thành 0, G thành 6... vẫn tự xác nhận đúng dòng Excel.
        self.assertEqual(ocr.exact_matches(['ABO1234567'], {'AB01234567'}), ['AB01234567'])
        self.assertEqual(ocr.exact_matches(['ABO12345G'], {'AB0123456'}), ['AB0123456'])

    def test_confusion_not_returned_when_two_excel_rows_normalize_equal(self):
        # Hai dòng Excel cùng dạng chuẩn hoá → không đoán bừa.
        self.assertEqual(ocr.exact_matches(['ABO12345G'], {'AB0123456', 'ABO123456'}), [])

    def test_scan_image_falls_back_to_full_image_when_center_has_no_serial(self):
        pil_img = Image.new('RGB', (120, 120), 'white')
        with patch.object(ocr, 'OCR_BACKEND', 'rapid'), \
             patch.object(ocr, 'scan_barcode', return_value=[]), \
             patch.object(ocr, 'run_ocr_on_pil', side_effect=[[], [('AB12345678', 0.9)]]), \
             patch.object(ocr, 'build_image_variants', return_value=iter([])):
            output = ocr.scan_image(pil_img, {'AB12345678'})
        self.assertEqual(output['method'], 'ocr_full_exact')
        self.assertEqual(output['candidates'], ['AB12345678'])

    def test_unmatched_barcode_and_first_ocr_do_not_stop_deep_scan(self):
        with tempfile.TemporaryDirectory() as directory:
            image_path = Path(directory) / 'image.png'
            serial_path = Path(directory) / 'serials.json'
            Image.new('RGB', (50, 50), 'white').save(image_path)
            serial_path.write_text(json.dumps(['AB12345678']), encoding='utf-8')
            output = io.StringIO()
            with patch.object(sys, 'argv', ['ocr.py', str(image_path), str(serial_path)]), \
                 patch.object(ocr, 'scan_barcode', return_value=['99999999']), \
                 patch.object(ocr, 'initialize_ocr'), \
                 patch.object(ocr, 'run_ocr_on_pil', side_effect=[[('MODEL98765', .9)], [('AB12345678', .9)]]), \
                 patch.object(ocr, 'build_image_variants', return_value=iter([('contrast', Image.new('RGB', (50, 50)))])), \
                 contextlib.redirect_stdout(output):
                with self.assertRaises(SystemExit):
                    ocr.main()
            self.assertEqual(json.loads(output.getvalue())['candidates'], ['AB12345678'])

    def test_rapid_adapter_uses_bgr_and_confidence(self):
        with patch.object(ocr, 'OCR_BACKEND', 'rapid'), \
             patch.object(ocr, 'ocr_engine', return_value=([[None, 'AB12345678', .9], [None, 'noise', .1]], None)) as engine:
            result = ocr.run_ocr_on_pil(Image.new('RGB', (2, 2), (255, 0, 0)))
            self.assertEqual(result, [('AB12345678', .9)])
            self.assertEqual(engine.call_args.args[0][0, 0].tolist(), [0, 0, 255])


if __name__ == '__main__':
    unittest.main()
