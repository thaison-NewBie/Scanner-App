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

    private static final int TWIPS_PER_INCH = 1440;
    private static final int EMU_PER_INCH = 914400;

    // Giữ đúng khổ của chương trình desktop gốc: mỗi ảnh 3.4 × 3.7 inch.
    private static final int IMG_W = (int) (3.4 * EMU_PER_INCH);
    private static final int IMG_H = (int) (3.7 * EMU_PER_INCH);
    private static final int INFO_COL_WIDTH = 4 * TWIPS_PER_INCH;
    private static final int IMAGE_COL_WIDTH = (int) (3.7 * TWIPS_PER_INCH);
    private static final int IMAGE_ROW_HEIGHT = 4 * TWIPS_PER_INCH;
    // python-docx Table Grid dùng lề ngang mặc định 108 twip cho nội dung trong ô.
    private static final int TEXT_CELL_HORIZONTAL_PADDING = 108;

    // Cùng chiến lược với mã desktop: giảm ảnh trước, cắt ở tâm rồi giảm lần cuối.
    private static final int MAX_INPUT_PX = 1600;
    private static final int MAX_EMBEDDED_IMG_PX = 1000;

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
            // Document() của python-docx mặc định dùng Letter 8.5 × 11 inch.
            // Khai báo rõ để Word và trình render dùng cùng một khổ giấy.
            CTPageSz pageSize = sectPr.isSetPgSz() ? sectPr.getPgSz() : sectPr.addNewPgSz();
            pageSize.setW(BigInteger.valueOf((long) (8.5 * TWIPS_PER_INCH)));
            pageSize.setH(BigInteger.valueOf(11L * TWIPS_PER_INCH));
            CTPageMar mar = sectPr.addNewPgMar();
            mar.setLeft(BigInteger.valueOf(792));
            mar.setRight(BigInteger.valueOf(792));
            mar.setTop(BigInteger.valueOf(720));
            mar.setBottom(BigInteger.valueOf(720));

            // ── Bảng thông tin ────────────────────────────────────────────
            boolean hasNotes = !"TRUOC_SCC".equals(reportType);
            int infoRows = hasNotes ? 3 : 2;

            XWPFTable info = doc.createTable(infoRows, 2);
            configureTable(info, new int[]{INFO_COL_WIDTH, INFO_COL_WIDTH});

            setCell(info.getRow(0).getCell(0), "MHH: " + s(productCode), false, 12,
                ParagraphAlignment.LEFT);
            setCell(info.getRow(0).getCell(1), "SN: "  + s(serialNumber), false, 12,
                ParagraphAlignment.LEFT);
            setCell(info.getRow(1).getCell(0), "Tên: " + s(deviceName),   false, 12,
                ParagraphAlignment.LEFT);
            setCell(info.getRow(1).getCell(1), "PO: "  + s(poNumber),     false, 12,
                ParagraphAlignment.LEFT);

            if (hasNotes) {
                String notesText = buildNotesText(reportType, repairNotes);
                XWPFTableRow notesRow = info.getRow(2);
                mergeHorizontal(notesRow.getCell(0), notesRow.getCell(1));
                setCell(notesRow.getCell(0), notesText, false, 12,
                    ParagraphAlignment.LEFT);
            }

            // Khoảng cách giữa bảng thông tin và bảng ảnh: giữ một dòng trống
            // và cộng thêm 12pt để tách rõ như mẫu Word của bản desktop.
            XWPFParagraph spacer = doc.createParagraph();
            spacer.setSpacingAfter(240);

            // ── Bảng ảnh ─────────────────────────────────────────────────
            String[] caps = getCaptions(reportType);
            XWPFTable imgTable = doc.createTable(4, 2);
            configureTable(imgTable, new int[]{IMAGE_COL_WIDTH, IMAGE_COL_WIDTH});
            setMinimumRowHeight(imgTable.getRow(1), IMAGE_ROW_HEIGHT);
            setMinimumRowHeight(imgTable.getRow(3), IMAGE_ROW_HEIGHT);

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
     * Sau đó cắt chính giữa theo cùng tỷ lệ 3.4 × 3.7 như ứng dụng desktop.
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

            // 3. Resize sơ bộ trước khi xoay để giảm RAM/CPU.
            img = resizeIfLarge(img, MAX_INPUT_PX);

            // 4. Xoay theo EXIF (bỏ qua nếu orientation = 1)
            if (orientation != 1) {
                img = applyExifOrientation(img, orientation);
            }

            // 5. Cắt ảnh tại tâm đúng tỷ lệ khung Word, rồi giảm kích thước nhúng.
            img = cropCenterToRatio(img, 3.4 / 3.7);
            img = resizeIfLarge(img, MAX_EMBEDDED_IMG_PX);

            // 6. Ghi lại thành JPEG
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "JPEG", out);
            return out.toByteArray();

        } catch (Exception e) {
            System.err.println("[WordService EXIF] " + e.getMessage());
            return imageBytes; // Fallback: dùng ảnh gốc
        }
    }

    /** Giới hạn cạnh dài của ảnh; dùng cho cả bước xử lý và ảnh nhúng. */
    private BufferedImage resizeIfLarge(BufferedImage src, int maxPixels) {
        int w = src.getWidth(), h = src.getHeight();
        if (w <= maxPixels && h <= maxPixels) return src;

        float scale = (float) maxPixels / Math.max(w, h);
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

    /** Cắt chính giữa để ảnh lấp đầy khung mà không bị kéo méo. */
    private BufferedImage cropCenterToRatio(BufferedImage src, double targetRatio) {
        int sourceWidth = src.getWidth();
        int sourceHeight = src.getHeight();
        double sourceRatio = (double) sourceWidth / sourceHeight;

        if (Math.abs(sourceRatio - targetRatio) < 0.001) return src;

        int cropWidth = sourceWidth;
        int cropHeight = sourceHeight;
        int x = 0;
        int y = 0;
        if (sourceRatio > targetRatio) {
            cropWidth = Math.max(1, (int) Math.round(sourceHeight * targetRatio));
            x = (sourceWidth - cropWidth) / 2;
        } else {
            cropHeight = Math.max(1, (int) Math.round(sourceWidth / targetRatio));
            y = (sourceHeight - cropHeight) / 2;
        }

        BufferedImage cropped = new BufferedImage(cropWidth, cropHeight,
            BufferedImage.TYPE_INT_RGB);
        Graphics2D g = cropped.createGraphics();
        g.drawImage(src, 0, 0, cropWidth, cropHeight,
            x, y, x + cropWidth, y + cropHeight, null);
        g.dispose();
        return cropped;
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

    private void setCell(XWPFTableCell cell, String text, boolean bold, int size,
                         ParagraphAlignment alignment) {
        setTextCellPadding(cell);
        XWPFParagraph p = cell.getParagraphs().get(0);
        p.setAlignment(alignment);
        XWPFRun r = p.createRun();
        r.setFontFamily("Times New Roman");
        r.setBold(bold);
        r.setFontSize(size);
        r.setText(text);
        cell.setVerticalAlignment(XWPFTableCell.XWPFVertAlign.CENTER);
    }

    /** Tạo khoảng thở giữa chữ và viền bảng, giống Table Grid của mã Python. */
    private void setTextCellPadding(XWPFTableCell cell) {
        CTTcPr tcPr = getOrCreateTcPr(cell);
        CTTcMar mar = (tcPr.getTcMar() != null) ? tcPr.getTcMar() : tcPr.addNewTcMar();
        mar.setLeft(tableCellMargin(TEXT_CELL_HORIZONTAL_PADDING));
        mar.setRight(tableCellMargin(TEXT_CELL_HORIZONTAL_PADDING));
    }

    private CTTblWidth tableCellMargin(int twips) {
        CTTblWidth margin = CTTblWidth.Factory.newInstance();
        margin.setType(STTblWidth.DXA);
        margin.setW(BigInteger.valueOf(twips));
        return margin;
    }

    private void setCaptionCell(XWPFTableCell cell, String text) {
        setCell(cell, text, false, 12, ParagraphAlignment.CENTER);
    }

    private void addImageCell(XWPFTableCell cell, List<byte[]> imgs, int idx) {
        XWPFParagraph p = cell.getParagraphs().get(0);
        p.setAlignment(ParagraphAlignment.CENTER);
        p.setSpacingBefore(0);
        p.setSpacingAfter(0);
        p.setSpacingBetween(1.0);
        cell.setVerticalAlignment(XWPFTableCell.XWPFVertAlign.CENTER);

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

    /** Đồng bộ với table.autofit = False và table.alignment = CENTER của mã Python. */
    private void configureTable(XWPFTable table, int[] widths) {
        int totalWidth = 0;
        for (int width : widths) totalWidth += width;
        setTableWidth(table, totalWidth);
        setColWidths(table, widths);

        CTTbl tbl = table.getCTTbl();
        CTTblPr pr = tbl.getTblPr() != null ? tbl.getTblPr() : tbl.addNewTblPr();
        CTTblLayoutType layout = pr.isSetTblLayout()
            ? pr.getTblLayout() : pr.addNewTblLayout();
        layout.setType(STTblLayoutType.FIXED);
        CTJcTable jc = pr.isSetJc() ? pr.getJc() : pr.addNewJc();
        jc.setVal(STJcTable.CENTER);
    }

    /** python-docx đặt row.height = 4 inch theo quy tắc tối thiểu. */
    private void setMinimumRowHeight(XWPFTableRow row, int twips) {
        CTTrPr trPr = row.getCtRow().isSetTrPr()
            ? row.getCtRow().getTrPr() : row.getCtRow().addNewTrPr();
        CTHeight height = trPr.sizeOfTrHeightArray() > 0
            ? trPr.getTrHeightArray(0) : trPr.addNewTrHeight();
        height.setVal(BigInteger.valueOf(twips));
        height.setHRule(STHeightRule.AT_LEAST);
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
