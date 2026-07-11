package com.scanner.service;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.xwpf.usermodel.*;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.*;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class WordService {

    // EMU: 8.5cm × 6.4cm — vừa ô bảng Word 2 cột
    private static final int IMG_W = (int) (8.5 * 360000);
    private static final int IMG_H = (int) (6.4 * 360000);

    // Kích thước tối đa trước khi nhúng vào Word (giảm tải AWT)
    private static final int MAX_IMG_PX = 1200;

    // Thread pool dùng chung cho xử lý ảnh song song
    private static final ExecutorService IMAGE_POOL =
        Executors.newFixedThreadPool(4);

    /**
     * Tạo báo cáo Word.
     * @param imageBytesList  Danh sách 4 ảnh dưới dạng byte[] (null = không có ảnh)
     */
    public byte[] generateReport(
            String deviceName, String productCode, String poNumber,
            String reportType, String repairNotes,
            String serialNumber, boolean snVerified,
            List<byte[]> imageBytesList) throws IOException, InvalidFormatException {

        // ── Xử lý EXIF song song cho tất cả 4 ảnh ─────────────────────────
        // Mỗi ảnh được xử lý trên 1 thread riêng, tổng thời gian ≈ thời gian ảnh chậm nhất
        List<CompletableFuture<byte[]>> futures = new ArrayList<>();
        for (byte[] raw : imageBytesList) {
            byte[] capture = raw; // effectively-final for lambda
            futures.add(CompletableFuture.supplyAsync(
                () -> capture != null ? correctExifOrientation(capture) : null,
                IMAGE_POOL
            ));
        }

        // Chờ tất cả hoàn thành
        List<byte[]> corrected = new ArrayList<>();
        for (CompletableFuture<byte[]> f : futures) {
            try { corrected.add(f.get()); }
            catch (Exception e) { corrected.add(null); }
        }

        // ── Tạo tài liệu Word ───────────────────────────────────────────────
        try (XWPFDocument doc = new XWPFDocument()) {

            // Lề chuẩn
            CTSectPr sectPr = doc.getDocument().getBody().addNewSectPr();
            CTPageMar mar = sectPr.addNewPgMar();
            mar.setLeft(BigInteger.valueOf(792));
            mar.setRight(BigInteger.valueOf(792));
            mar.setTop(BigInteger.valueOf(720));
            mar.setBottom(BigInteger.valueOf(720));

            // ── Bảng thông tin ────────────────────────────────────────────
            boolean hasNotes = !"TRUOC_SCC".equals(reportType);
            int infoRows = hasNotes ? 3 : 2;

            XWPFTable info = doc.createTable(infoRows, 2);
            setTableWidth(info, 10440);
            setColWidths(info, new int[]{5220, 5220});

            setCell(info.getRow(0).getCell(0), "MHH: " + s(productCode), false, 10);
            setCell(info.getRow(0).getCell(1), "SN: "  + s(serialNumber), false, 10);
            setCell(info.getRow(1).getCell(0), "Ten: " + s(deviceName),   false, 10);
            setCell(info.getRow(1).getCell(1), "PO: "  + s(poNumber),     false, 10);

            if (hasNotes) {
                String notesText = buildNotesText(reportType, repairNotes);
                XWPFTableRow notesRow = info.getRow(2);
                mergeHorizontal(notesRow.getCell(0), notesRow.getCell(1));
                setCell(notesRow.getCell(0), notesText, false, 10);
            }

            doc.createParagraph();

            // ── Bảng ảnh ─────────────────────────────────────────────────
            String[] caps = getCaptions(reportType);
            XWPFTable imgTable = doc.createTable(4, 2);
            setTableWidth(imgTable, 10440);
            setColWidths(imgTable, new int[]{5220, 5220});

            setCaptionCell(imgTable.getRow(0).getCell(0), caps[0]);
            setCaptionCell(imgTable.getRow(0).getCell(1), caps[1]);
            addImageCell(imgTable.getRow(1).getCell(0), corrected, 0);
            addImageCell(imgTable.getRow(1).getCell(1), corrected, 1);
            setCaptionCell(imgTable.getRow(2).getCell(0), caps[2]);
            setCaptionCell(imgTable.getRow(2).getCell(1), caps[3]);
            addImageCell(imgTable.getRow(3).getCell(0), corrected, 2);
            addImageCell(imgTable.getRow(3).getCell(1), corrected, 3);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.write(out);
            return out.toByteArray();
        }
    }

    // ── EXIF Correction ──────────────────────────────────────────────────────

    /**
     * Đọc EXIF Orientation và xoay ảnh về đúng chiều vật lý.
     * Tối ưu: resize ngay về MAX_IMG_PX trước khi xoay để giảm tải AWT.
     */
    private byte[] correctExifOrientation(byte[] imageBytes) {
        try {
            // 1. Đọc EXIF orientation
            int orientation = 1;
            try {
                Metadata meta = ImageMetadataReader.readMetadata(
                    new ByteArrayInputStream(imageBytes));
                ExifIFD0Directory dir = meta.getFirstDirectoryOfType(
                    ExifIFD0Directory.class);
                if (dir != null && dir.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) {
                    orientation = dir.getInt(ExifIFD0Directory.TAG_ORIENTATION);
                }
            } catch (Exception ignored) {
                // Không có EXIF → dùng orientation = 1
            }

            // 2. Đọc ảnh
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(imageBytes));
            if (img == null) return imageBytes;

            // 3. Resize sơ bộ trước khi xoay (giảm kích thước pixel → xoay nhanh hơn)
            img = resizeIfLarge(img);

            // 4. Xoay theo EXIF (bỏ qua nếu orientation = 1)
            if (orientation != 1) {
                img = applyExifOrientation(img, orientation);
            }

            // 5. Ghi lại thành JPEG
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "JPEG", out);
            return out.toByteArray();

        } catch (Exception e) {
            System.err.println("[WordService EXIF] " + e.getMessage());
            return imageBytes; // Fallback: dùng ảnh gốc
        }
    }

    /** Giới hạn kích thước ảnh về MAX_IMG_PX (nếu lớn hơn) */
    private BufferedImage resizeIfLarge(BufferedImage src) {
        int w = src.getWidth(), h = src.getHeight();
        if (w <= MAX_IMG_PX && h <= MAX_IMG_PX) return src;

        float scale = (float) MAX_IMG_PX / Math.max(w, h);
        int nw = Math.round(w * scale);
        int nh = Math.round(h * scale);

        BufferedImage dst = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                           RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, nw, nh, null);
        g.dispose();
        return dst;
    }

    /** Xoay BufferedImage theo EXIF orientation (1-8) */
    private BufferedImage applyExifOrientation(BufferedImage src, int orientation) {
        int w = src.getWidth(), h = src.getHeight();
        boolean swap = (orientation == 5 || orientation == 6 ||
                        orientation == 7 || orientation == 8);
        BufferedImage dst = new BufferedImage(swap ? h : w, swap ? w : h,
                                              BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();

        switch (orientation) {
            case 2 -> { g.translate(w, 0); g.scale(-1, 1); }
            case 3 -> { g.translate(w, h); g.rotate(Math.PI); }
            case 4 -> { g.translate(0, h); g.scale(1, -1); }
            case 5 -> { g.rotate(Math.PI / 2); g.scale(1, -1); }
            case 6 -> { g.translate(h, 0); g.rotate(Math.PI / 2); }
            case 7 -> { g.translate(h, w); g.rotate(Math.PI / 2); g.scale(1, -1); }
            case 8 -> { g.translate(0, w); g.rotate(-Math.PI / 2); }
        }

        g.drawImage(src, 0, 0, null);
        g.dispose();
        return dst;
    }

    // ── Word Helpers ──────────────────────────────────────────────────────────

    private String buildNotesText(String reportType, String notes) {
        String n = (notes == null || notes.isBlank()) ? "" : notes.trim();
        return switch (reportType) {
            case "CHAY_NO" ->
                "Thiết bị không đủ điều kiện sửa chữa (Cháy nổ, móp méo, ẩm mốc...): " + n;
            default -> "Lỗi đã sửa chữa: " + n;
        };
    }

    private String[] getCaptions(String type) {
        return switch (type) {
            case "SAU_SCC" -> new String[]{
                "Ảnh tổng thể của thiết bị có S/N", "Ảnh S/N thiết bị",
                "Ảnh mặt trên của bản mạch sau sửa chữa",
                "Ảnh mặt dưới của bản mạch sau sửa chữa"};
            case "TRUOC_SCC" -> new String[]{
                "Ảnh tổng thể của thiết bị có S/N", "Ảnh S/N thiết bị",
                "Ảnh mặt trên của bản mạch trước sửa chữa",
                "Ảnh mặt dưới của bản mạch trước sửa chữa"};
            case "CHAY_NO" -> new String[]{
                "Ảnh tổng thể của thiết bị có S/N", "Ảnh S/N thiết bị",
                "Ảnh thiết bị bị cháy nổ (mặt trên)",
                "Ảnh thiết bị bị cháy nổ (mặt dưới)"};
            default -> new String[]{
                "Ảnh tổng thể của thiết bị có S/N", "Ảnh S/N thiết bị",
                "Ảnh mặt trên", "Ảnh mặt dưới"};
        };
    }

    private void setCell(XWPFTableCell cell, String text, boolean bold, int size) {
        XWPFParagraph p = cell.getParagraphs().get(0);
        XWPFRun r = p.createRun();
        r.setBold(bold);
        r.setFontSize(size);
        r.setText(text);
    }

    private void setCaptionCell(XWPFTableCell cell, String text) {
        // Nền xám nhạt
        CTTcPr tcPr = getOrCreateTcPr(cell);
        CTShd shd = (tcPr.getShd() != null) ? tcPr.getShd() : tcPr.addNewShd();
        shd.setVal(STShd.CLEAR);
        shd.setColor("auto");
        shd.setFill("D9D9D9");

        XWPFParagraph p = cell.getParagraphs().get(0);
        p.setAlignment(ParagraphAlignment.CENTER);
        XWPFRun r = p.createRun();
        r.setFontSize(9);
        r.setText(text);
    }

    private void addImageCell(XWPFTableCell cell, List<byte[]> imgs, int idx) {
        XWPFParagraph p = cell.getParagraphs().get(0);
        p.setAlignment(ParagraphAlignment.CENTER);

        // Bỏ padding ô
        CTTcPr tcPr = getOrCreateTcPr(cell);
        CTTcMar mar = (tcPr.getTcMar() != null) ? tcPr.getTcMar() : tcPr.addNewTcMar();
        CTTblWidth zero = CTTblWidth.Factory.newInstance();
        zero.setW(BigInteger.ZERO);
        zero.setType(STTblWidth.DXA);
        mar.setTop(zero);
        mar.setBottom(zero);

        if (idx >= imgs.size() || imgs.get(idx) == null) {
            p.createRun().setText("[Khong co anh]");
            return;
        }
        try {
            XWPFRun run = p.createRun();
            run.addPicture(new ByteArrayInputStream(imgs.get(idx)),
                XWPFDocument.PICTURE_TYPE_JPEG, "img" + idx, IMG_W, IMG_H);
        } catch (Exception e) {
            p.createRun().setText("[Loi anh: " + e.getMessage() + "]");
        }
    }

    /** Merge 2 ô ngang trong cùng 1 hàng */
    private void mergeHorizontal(XWPFTableCell start, XWPFTableCell end) {
        CTTcPr sp = getOrCreateTcPr(start);
        if (sp.getHMerge() == null) sp.addNewHMerge().setVal(STMerge.RESTART);
        else sp.getHMerge().setVal(STMerge.RESTART);

        CTTcPr ep = getOrCreateTcPr(end);
        if (ep.getHMerge() == null) ep.addNewHMerge().setVal(STMerge.CONTINUE);
        else ep.getHMerge().setVal(STMerge.CONTINUE);
    }

    private CTTcPr getOrCreateTcPr(XWPFTableCell cell) {
        return (cell.getCTTc().getTcPr() != null)
            ? cell.getCTTc().getTcPr()
            : cell.getCTTc().addNewTcPr();
    }

    private void setTableWidth(XWPFTable table, int twips) {
        CTTbl tbl = table.getCTTbl();
        CTTblPr pr = tbl.getTblPr() != null ? tbl.getTblPr() : tbl.addNewTblPr();
        CTTblWidth w = (pr.getTblW() != null) ? pr.getTblW() : pr.addNewTblW();
        w.setType(STTblWidth.DXA);
        w.setW(BigInteger.valueOf(twips));
    }

    private void setColWidths(XWPFTable table, int[] widths) {
        CTTbl tbl = table.getCTTbl();
        CTTblGrid grid = (tbl.getTblGrid() != null) ? tbl.getTblGrid() : tbl.addNewTblGrid();
        while (grid.sizeOfGridColArray() > 0) grid.removeGridCol(0);
        for (int w : widths) grid.addNewGridCol().setW(BigInteger.valueOf(w));

        for (XWPFTableRow row : table.getRows()) {
            for (int i = 0; i < row.getTableCells().size() && i < widths.length; i++) {
                XWPFTableCell cell = row.getTableCells().get(i);
                CTTcPr tcPr = getOrCreateTcPr(cell);
                CTTblWidth cw = (tcPr.getTcW() != null) ? tcPr.getTcW() : tcPr.addNewTcW();
                cw.setType(STTblWidth.DXA);
                cw.setW(BigInteger.valueOf(widths[i]));
            }
        }
    }

    private String s(String v) {
        return v == null ? "" : v.trim();
    }
}
