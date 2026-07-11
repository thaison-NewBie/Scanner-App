package com.scanner.model;

import lombok.Data;
import java.util.List;

@Data
public class ReportData {
    private String deviceName;
    private String productCode;
    private String poNumber;
    private String reportType; // CHAY_NO, TRUOC_SCC, SAU_SCC
    private String repairNotes;
    private String serialNumber;
    private boolean snVerified;
}
