package com.synapse;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;

public class Exporter {
    public static byte[] exportToXlsx(List<Map<String, Object>> candidates) throws IOException {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Shortlisted Candidates");
            
            // Header Row
            Row headerRow = sheet.createRow(0);
            String[] headers = {"Rank", "Score (%)", "Candidate ID", "Name", "Current Title", "Current Company", "Experience (Years)", "Location", "Notice Period (Days)"};
            
            CellStyle headerStyle = workbook.createCellStyle();
            Font font = workbook.createFont();
            font.setBold(true);
            headerStyle.setFont(font);
            
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
            }
            
            // Data Rows
            int rowIdx = 1;
            for (Map<String, Object> c : candidates) {
                Row row = sheet.createRow(rowIdx++);
                Map<String, Object> profile = (Map<String, Object>) c.get("profile");
                Map<String, Object> signals = (Map<String, Object>) c.get("redrob_signals");
                
                row.createCell(0).setCellValue(c.get("rank") != null ? ((Number) c.get("rank")).intValue() : 0);
                row.createCell(1).setCellValue(c.get("score") != null ? ((Number) c.get("score")).doubleValue() * 100.0 : 0.0);
                row.createCell(2).setCellValue((String) c.get("candidate_id"));
                row.createCell(3).setCellValue(profile != null ? (String) profile.getOrDefault("anonymized_name", "") : "");
                row.createCell(4).setCellValue(profile != null ? (String) profile.getOrDefault("current_title", "") : "");
                row.createCell(5).setCellValue(profile != null ? (String) profile.getOrDefault("current_company", "") : "");
                row.createCell(6).setCellValue(profile != null && profile.get("years_of_experience") != null ? ((Number) profile.get("years_of_experience")).doubleValue() : 0.0);
                row.createCell(7).setCellValue(profile != null ? (String) profile.getOrDefault("location", "") : "");
                row.createCell(8).setCellValue(signals != null && signals.get("notice_period_days") != null ? ((Number) signals.get("notice_period_days")).intValue() : 90);
            }
            
            // Auto size columns
            for (int i = 0; i < headers.length; i++) {
                sheet.autoSizeColumn(i);
            }
            
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        }
    }
}
