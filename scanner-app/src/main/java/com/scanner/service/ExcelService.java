package com.scanner.service;

import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.*;

@Service
public class ExcelService {

    // Dùng static để giữ data qua các request (test mode, chưa có DB)
    private static Set<String> loadedSerialNumbers = new HashSet<>();
    private static int rowCount = 0;

    public Map<String, Object> loadExcel(MultipartFile file) throws IOException {
        loadedSerialNumbers = new HashSet<>();
        rowCount = 0;

        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0);
            int snColIndex = -1;
            boolean foundHeader = false;

            for (Row row : sheet) {
                if (!foundHeader) {
                    for (Cell cell : row) {
                        String val = getCellValue(cell);
                        if (val != null && val.trim().equalsIgnoreCase("SN")) {
                            snColIndex = cell.getColumnIndex();
                            foundHeader = true;
                            break;
                        }
                    }
                    if (foundHeader) continue; // Skip header row for values
                }

                if (foundHeader && snColIndex != -1) {
                    Cell cell = row.getCell(snColIndex);
                    String value = getCellValue(cell);
                    if (value != null && !value.isBlank()) {
                        loadedSerialNumbers.add(value.trim().toUpperCase());
                    }
                }
                rowCount++;
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("totalValues", loadedSerialNumbers.size());
        result.put("rowCount", rowCount);
        result.put("filename", file.getOriginalFilename());
        // Gửi 5 mẫu về frontend để preview
        result.put("sample", loadedSerialNumbers.stream().limit(5).toList());
        return result;
    }

    public boolean checkSN(String sn) {
        if (sn == null || sn.isBlank()) return false;
        return loadedSerialNumbers.contains(sn.trim().toUpperCase());
    }

    public int getLoadedCount() {
        return loadedSerialNumbers.size();
    }

    /** Trả danh sách SN đã sort, filter theo keyword (uppercase). */
    public List<String> getAllSN(String filter) {
        String kw = (filter == null) ? "" : filter.trim().toUpperCase();
        return loadedSerialNumbers.stream()
            .filter(s -> kw.isEmpty() || s.contains(kw))
            .sorted()
            .toList();
    }

    private String getCellValue(Cell cell) {
        if (cell == null) return null;
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> {
                if (DateUtil.isCellDateFormatted(cell)) yield "";
                double val = cell.getNumericCellValue();
                yield (val == Math.floor(val)) ? String.valueOf((long) val) : String.valueOf(val);
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> {
                try {
                    yield cell.getStringCellValue();
                } catch (Exception e) {
                    yield String.valueOf(cell.getNumericCellValue());
                }
            }
            default -> null;
        };
    }
}
