/*******************************************************************************
 * 【模块】server · excel
 * 【文件】ExcelService.java（io.memobservatory.server.excel）
 * 【核心功能】Excel 底层服务（Apache POI 5.2.5）：
 *            读——readAll（全工作簿逐 sheet 转字符串网格）、readRows（首个 sheet
 *            按首行表头转行 map）；写——writeXlsx（表头 + 行 map 导出 xlsx）、
 *            writeGridXlsx（通用网格导出 xlsx）。同时兼容 .xls / .xlsx。
 * 【设计要点】与业务解耦：不感知 memory_events / 导入流程，后续功能按需注入。
 *            单元格读取统一走 DataFormatter（公式取缓存值），写入按类型分派。
 *            注意：XSSF 全量驻内存，超大导出可后续换 SXSSF 流式写。
 * 【核心改动】2026-09-01 新增（Excel 处理底层服务）。
 *******************************************************************************/
package io.memobservatory.server.excel;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Excel 读写底层服务（.xls / .xlsx 通用）。 */
@Service
public class ExcelService {

    /** 单个 sheet 的字符串网格：grid.get(r).get(c)。 */
    public record SheetGrid(String name, List<List<String>> grid) { }

    private static final int MAX_COL_WIDTH = 60;

    // ==================== 读 ====================

    /** 读取全部 sheet：单元格一律转显示字符串（数字按显示格式、公式取缓存值）。 */
    public List<SheetGrid> readAll(InputStream in) throws Exception {
        try (Workbook wb = WorkbookFactory.create(in)) {
            DataFormatter fmt = new DataFormatter();
            FormulaEvaluator ev = wb.getCreationHelper().createFormulaEvaluator();
            List<SheetGrid> out = new ArrayList<>();
            for (int s = 0; s < wb.getNumberOfSheets(); s++) {
                Sheet sheet = wb.getSheetAt(s);
                out.add(new SheetGrid(sheet.getSheetName(), sheetGrid(sheet, fmt, ev)));
            }
            return out;
        }
    }

    /** 首个 sheet：第一行作表头，返回行 map 列表（值为字符串；空行跳过；空表头用 col_N）。 */
    public List<Map<String, Object>> readRows(InputStream in) throws Exception {
        try (Workbook wb = WorkbookFactory.create(in)) {
            if (wb.getNumberOfSheets() == 0) return List.of();
            Sheet sheet = wb.getSheetAt(0);
            DataFormatter fmt = new DataFormatter();
            FormulaEvaluator ev = wb.getCreationHelper().createFormulaEvaluator();
            List<List<String>> grid = sheetGrid(sheet, fmt, ev);
            if (grid.isEmpty()) return List.of();

            List<String> headers = grid.get(0);
            for (int c = 0; c < headers.size(); c++) {
                if (headers.get(c) == null || headers.get(c).isBlank()) {
                    headers.set(c, "col_" + (c + 1));
                }
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            for (int r = 1; r < grid.size(); r++) {
                List<String> cells = grid.get(r);
                boolean any = cells.stream().anyMatch(v -> v != null && !v.isBlank());
                if (!any) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                for (int c = 0; c < headers.size(); c++) {
                    row.put(headers.get(c), c < cells.size() ? cells.get(c) : "");
                }
                rows.add(row);
            }
            return rows;
        }
    }

    private List<List<String>> sheetGrid(Sheet sheet, DataFormatter fmt, FormulaEvaluator ev) {
        List<List<String>> grid = new ArrayList<>();
        for (int r = sheet.getFirstRowNum(); r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) { grid.add(new ArrayList<>()); continue; }
            List<String> cells = new ArrayList<>();
            for (int c = 0; c < row.getLastCellNum(); c++) {
                cells.add(cellText(row.getCell(c), fmt, ev));
            }
            grid.add(cells);
        }
        // 去掉首尾全空行
        while (!grid.isEmpty() && grid.get(0).stream().allMatch(v -> v == null || v.isBlank())) grid.remove(0);
        while (!grid.isEmpty() && grid.get(grid.size() - 1).stream().allMatch(v -> v == null || v.isBlank())) grid.remove(grid.size() - 1);
        return grid;
    }

    private String cellText(Cell c, DataFormatter fmt, FormulaEvaluator ev) {
        if (c == null) return "";
        try {
            return fmt.formatCellValue(c, ev).strip();
        } catch (Exception e) {
            return "";
        }
    }

    // ==================== 写 ====================

    /** 表头 + 行 map → xlsx 字节流（表头加粗、列宽自适应）。headers 顺序即列顺序。 */
    public byte[] writeXlsx(List<String> headers, List<? extends Map<String, Object>> rows) throws Exception {
        List<List<Object>> grid = new ArrayList<>();
        grid.add(new ArrayList<>(headers));
        for (Map<String, Object> row : rows) {
            List<Object> cells = new ArrayList<>();
            for (String h : headers) cells.add(row.get(h));
            grid.add(cells);
        }
        return writeGridXlsx(grid);
    }

    /** 通用网格 → xlsx 字节流（第一行按表头样式处理）。值支持 String/Number/Boolean/Date/null。 */
    public byte[] writeGridXlsx(List<List<Object>> grid) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Sheet1");
            Font boldFont = wb.createFont();
            boldFont.setBold(true);
            CellStyle headStyle = wb.createCellStyle();
            headStyle.setFont(boldFont);
            CellStyle dateStyle = wb.createCellStyle();
            dateStyle.setDataFormat(wb.createDataFormat().getFormat("yyyy-mm-dd hh:mm:ss"));

            for (int r = 0; r < grid.size(); r++) {
                Row row = sheet.createRow(r);
                List<Object> cells = grid.get(r);
                for (int c = 0; c < cells.size(); c++) {
                    Cell cell = row.createCell(c);
                    setCellValue(cell, cells.get(c), dateStyle);
                    if (r == 0) cell.setCellStyle(headStyle);
                }
            }
            for (int c = 0; c < sheet.getRow(0).getLastCellNum(); c++) {
                sheet.autoSizeColumn(c);
                if (sheet.getColumnWidth(c) > MAX_COL_WIDTH * 256) {
                    sheet.setColumnWidth(c, MAX_COL_WIDTH * 256);
                }
            }
            wb.write(out);
            return out.toByteArray();
        }
    }

    private void setCellValue(Cell cell, Object v, CellStyle dateStyle) {
        if (v == null) { cell.setBlank(); return; }
        if (v instanceof Number n) {
            cell.setCellValue(n.doubleValue());
        } else if (v instanceof Boolean b) {
            cell.setCellValue(b);
        } else if (v instanceof Date d) {
            cell.setCellValue(d);
            cell.setCellStyle(dateStyle);
        } else {
            cell.setCellValue(String.valueOf(v));
        }
    }

    /** 便捷方法：字节数组直接转回 InputStream 供二次读取。 */
    public InputStream toInputStream(byte[] bytes) {
        return new ByteArrayInputStream(bytes);
    }
}
