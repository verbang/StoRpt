package io.storpt.excel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkbookWriterTest {
  private final TemplateAnalyzer analyzer = new TemplateAnalyzer();
  private final WorkbookWriter writer = new WorkbookWriter();

  @TempDir
  Path temporaryDirectory;

  @Test
  void writesDynamicRowsAndPreservesProtectedContentAfterRoundTrip() throws Exception {
    Path output = temporaryDirectory.resolve("writer-output.xlsx");
    WorkbookSnapshot protectedBefore;
    WorkbookWriter.WriteSummary summary;

    try (Workbook workbook = openTemplate()) {
      TemplateMetadata metadata = analyzer.analyze(workbook);
      WorkbookWriteRequest request = request(stockRows(8), true, true, true);
      int newDataEndRow = request.dataStartRow() + request.rows().size() - 1;
      WorkbookSnapshot.CellSelector protectedCells = protectedCells(
          metadata.sheetIndex(),
          metadata.latestPeriod().titleRow(),
          metadata.latestPeriod().dataStartRow(),
          Math.max(metadata.latestPeriod().dataEndRow(), newDataEndRow));
      protectedBefore = WorkbookSnapshot.capture(workbook, protectedCells);

      summary = writer.write(workbook, request);

      assertEquals(protectedBefore, WorkbookSnapshot.capture(workbook, protectedCells));
      Sheet sheet = workbook.getSheetAt(metadata.sheetIndex());
      assertEquals("2026.02.02 - 2026.02.06", sheet.getRow(27).getCell(0).getStringCellValue());
      assertEquals("000008", sheet.getRow(36).getCell(0).getStringCellValue());
      assertEquals("股票8", sheet.getRow(36).getCell(1).getStringCellValue());
      assertEquals(18.25d, sheet.getRow(36).getCell(2).getNumericCellValue());
      assertEquals(19.50d, sheet.getRow(36).getCell(3).getNumericCellValue());
      assertEquals(sheet.getDefaultRowHeight(), sheet.getRow(36).getHeight());
      for (int columnIndex = 4; columnIndex <= 18; columnIndex++) {
        assertNull(sheet.getRow(36).getCell(columnIndex));
      }
      for (int columnIndex = 0; columnIndex <= 3; columnIndex++) {
        assertStyleSubsetEquals(
            sheet.getRow(34).getCell(columnIndex),
            sheet.getRow(36).getCell(columnIndex));
      }

      try (OutputStream stream = Files.newOutputStream(output)) {
        workbook.write(stream);
      }
    }

    assertEquals(34, summary.oldDataEndRow());
    assertEquals(36, summary.newDataEndRow());
    assertEquals(8, summary.writtenRows());

    try (Workbook reopened = WorkbookFactory.create(output.toFile())) {
      WorkbookSnapshot.CellSelector protectedCells = protectedCells(0, 27, 29, 36);
      assertEquals(protectedBefore, WorkbookSnapshot.capture(reopened, protectedCells));
      assertEquals("000008", reopened.getSheetAt(0).getRow(36).getCell(0).getStringCellValue());
    }
  }

  @Test
  void preservesUnselectedColumnsAndClearsOnlyOldCodeTail() throws Exception {
    try (Workbook workbook = openTemplate()) {
      TemplateMetadata metadata = analyzer.analyze(workbook);
      Sheet sheet = workbook.getSheetAt(metadata.sheetIndex());
      List<String> optionalBefore = optionalColumnStates(
          sheet,
          metadata.latestPeriod().dataStartRow(),
          metadata.latestPeriod().dataEndRow());
      WorkbookWriteRequest request = request(
          List.of(new WorkbookWriteRequest.StockValues("000001", null, null, null)),
          false,
          false,
          false);

      writer.write(workbook, request);

      assertEquals("000001", sheet.getRow(29).getCell(0).getStringCellValue());
      for (int rowIndex = 30; rowIndex <= 34; rowIndex++) {
        assertEquals(CellType.BLANK, sheet.getRow(rowIndex).getCell(0).getCellType());
      }
      assertEquals(optionalBefore, optionalColumnStates(sheet, 29, 34));
    }
  }

  @Test
  void rejectsStaleCoordinates() throws Exception {
    try (Workbook workbook = openTemplate()) {
      WorkbookWriteRequest stale = new WorkbookWriteRequest(
          0,
          18,
          20,
          LocalDate.of(2026, 2, 2),
          LocalDate.of(2026, 2, 6),
          stockRows(1),
          true,
          true,
          true);

      WorkbookWriteException exception = assertThrows(
          WorkbookWriteException.class,
          () -> writer.write(workbook, stale));

      assertEquals("TEMPLATE-001", exception.code());
    }
  }

  @Test
  void rejectsDuplicateCodes() throws Exception {
    try (Workbook workbook = openTemplate()) {
      WorkbookWriteRequest.StockValues values = stockRows(1).get(0);
      WorkbookWriteRequest request = request(List.of(values, values), true, true, true);

      WorkbookWriteException exception = assertThrows(
          WorkbookWriteException.class,
          () -> writer.write(workbook, request));

      assertEquals("INPUT-001", exception.code());
    }
  }

  @Test
  void rejectsMissingSelectedMarketValue() throws Exception {
    try (Workbook workbook = openTemplate()) {
      WorkbookWriteRequest request = request(
          List.of(new WorkbookWriteRequest.StockValues("000001", null, null, null)),
          true,
          false,
          false);

      WorkbookWriteException exception = assertThrows(
          WorkbookWriteException.class,
          () -> writer.write(workbook, request));

      assertEquals("INPUT-001", exception.code());
    }
  }

  private static WorkbookSnapshot.CellSelector protectedCells(
      int targetSheet,
      int titleRow,
      int dataStartRow,
      int dataEndRow) {
    return (sheetIndex, rowIndex, columnIndex) -> {
      if (sheetIndex != targetSheet) {
        return true;
      }
      boolean titleValue = rowIndex == titleRow && columnIndex == 0;
      boolean writableData = rowIndex >= dataStartRow
          && rowIndex <= dataEndRow
          && columnIndex <= 3;
      return !titleValue && !writableData;
    };
  }

  private static WorkbookWriteRequest request(
      List<WorkbookWriteRequest.StockValues> rows,
      boolean fillName,
      boolean fillIdealBuy,
      boolean fillIdealSell) {
    return new WorkbookWriteRequest(
        0,
        27,
        29,
        LocalDate.of(2026, 2, 2),
        LocalDate.of(2026, 2, 6),
        rows,
        fillName,
        fillIdealBuy,
        fillIdealSell);
  }

  private static List<WorkbookWriteRequest.StockValues> stockRows(int count) {
    List<WorkbookWriteRequest.StockValues> rows = new ArrayList<>();
    for (int index = 1; index <= count; index++) {
      rows.add(new WorkbookWriteRequest.StockValues(
          String.format("%06d", index),
          "股票" + index,
          BigDecimal.valueOf(10.25d + index),
          BigDecimal.valueOf(11.50d + index)));
    }
    return rows;
  }

  private static Workbook openTemplate() throws Exception {
    Path template = Path.of(System.getProperty("storpt.template", "../platform.xlsx"))
        .toAbsolutePath()
        .normalize();
    assertTrue(Files.isRegularFile(template), "Missing template: " + template);
    return WorkbookFactory.create(template.toFile());
  }

  private static List<String> optionalColumnStates(
      Sheet sheet, int firstRow, int lastRow) {
    List<String> states = new ArrayList<>();
    for (int rowIndex = firstRow; rowIndex <= lastRow; rowIndex++) {
      Row row = sheet.getRow(rowIndex);
      for (int columnIndex = 1; columnIndex <= 3; columnIndex++) {
        states.add(cellState(row == null ? null : row.getCell(columnIndex)));
      }
    }
    return states;
  }

  private static String cellState(Cell cell) {
    if (cell == null) {
      return "missing";
    }
    return switch (cell.getCellType()) {
      case BLANK -> "blank";
      case BOOLEAN -> "boolean:" + cell.getBooleanCellValue();
      case ERROR -> "error:" + cell.getErrorCellValue();
      case FORMULA -> "formula:" + cell.getCellFormula();
      case NUMERIC -> "number:" + Double.toHexString(cell.getNumericCellValue());
      case STRING -> "string:" + cell.getStringCellValue();
      case _NONE -> "none";
    };
  }

  private static void assertStyleSubsetEquals(Cell source, Cell target) {
    assertEquals(source.getCellStyle().getDataFormat(), target.getCellStyle().getDataFormat());
    assertEquals(source.getCellStyle().getAlignment(), target.getCellStyle().getAlignment());
    assertEquals(source.getCellStyle().getVerticalAlignment(), target.getCellStyle().getVerticalAlignment());
    assertEquals(source.getCellStyle().getFillPattern(), target.getCellStyle().getFillPattern());
    assertEquals(source.getCellStyle().getFillForegroundColor(), target.getCellStyle().getFillForegroundColor());
    assertEquals(source.getCellStyle().getBorderTop(), target.getCellStyle().getBorderTop());
    assertEquals(source.getCellStyle().getBorderRight(), target.getCellStyle().getBorderRight());
    assertEquals(source.getCellStyle().getBorderBottom(), target.getCellStyle().getBorderBottom());
    assertEquals(source.getCellStyle().getBorderLeft(), target.getCellStyle().getBorderLeft());
  }
}
