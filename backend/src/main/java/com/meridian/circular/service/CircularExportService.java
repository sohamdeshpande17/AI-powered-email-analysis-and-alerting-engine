package com.meridian.circular.service;

import com.meridian.circular.dto.Dtos.CircularSummary;
import com.meridian.circular.web.ApiException;
import java.io.ByteArrayOutputStream;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Builds the Repository "Export to Excel" workbook (.xlsx) for a list of
 * already-filtered circulars. Pure formatting — the filtering and the
 * "at least one filter applied" rule are enforced upstream in
 * {@link CircularService}.
 */
@Service
public class CircularExportService {

    private static final String[] HEADERS = {
            "Circular No", "Subject", "Categories", "Urgency", "Status",
            "Source", "Source Detail", "Due Date", "Effective Date", "Issued Date", "Received At",
    };

    // Fixed column widths (1/256 of a character). Explicit widths avoid POI's
    // autoSizeColumn, which needs AWT font metrics and is unreliable / slow on
    // headless servers without fontconfig.
    private static final int[] WIDTHS = {
            24, 60, 26, 10, 12, 30, 32, 14, 14, 14, 22,
    };

    private static final DateTimeFormatter DAY =
            DateTimeFormatter.ofPattern("dd MMM yyyy");
    private static final DateTimeFormatter DAY_TIME =
            DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm").withZone(ZoneId.of("Asia/Kolkata"));

    /** Render the filtered circulars to an .xlsx byte array. */
    public byte[] toXlsx(List<CircularSummary> rows) {
        try (Workbook wb = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            Sheet sheet = wb.createSheet("Circulars");

            CellStyle headerStyle = wb.createCellStyle();
            Font headerFont = wb.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);

            Row header = sheet.createRow(0);
            for (int c = 0; c < HEADERS.length; c++) {
                Cell cell = header.createCell(c);
                cell.setCellValue(HEADERS[c]);
                cell.setCellStyle(headerStyle);
            }

            int r = 1;
            for (CircularSummary row : rows) {
                Row out2 = sheet.createRow(r++);
                out2.createCell(0).setCellValue(nz(row.circularNo()));
                out2.createCell(1).setCellValue(nz(row.subject()));
                out2.createCell(2).setCellValue(
                        row.categories() == null ? "" : String.join(", ", row.categories()));
                out2.createCell(3).setCellValue(nz(row.urgency()));
                out2.createCell(4).setCellValue(nz(row.status()));
                out2.createCell(5).setCellValue(nz(row.sourceName()));
                out2.createCell(6).setCellValue(nz(row.source()));
                out2.createCell(7).setCellValue(
                        row.dueAt() == null ? "NA" : DAY.format(row.dueAt()));
                out2.createCell(8).setCellValue(
                        row.effectiveAt() == null ? "NA" : DAY.format(row.effectiveAt()));
                out2.createCell(9).setCellValue(
                        row.issuedAt() == null ? "" : DAY.format(row.issuedAt()));
                out2.createCell(10).setCellValue(
                        row.ingestedAt() == null ? "" : DAY_TIME.format(row.ingestedAt()));
            }

            for (int c = 0; c < WIDTHS.length; c++) {
                sheet.setColumnWidth(c, WIDTHS[c] * 256);
            }
            sheet.createFreezePane(0, 1);

            wb.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to build Excel export: " + e.getMessage());
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
