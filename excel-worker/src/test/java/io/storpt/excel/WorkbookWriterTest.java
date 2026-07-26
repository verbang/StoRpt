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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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

  /**
   * AC-032 + matrix item 2: every one of the eight checkbox combinations must
   * write only the selected columns and leave every unselected B:C:D cell at its
   * original value, cell by cell. Selected columns get the stock value; others
   * keep whatever the template held.
   */
  @ParameterizedTest
  @CsvSource({
      "false, false, false",
      "false, false, true",
      "false, true, false",
      "false, true, true",
      "true, false, false",
      "true, false, true",
      "true, true, false",
      "true, true, true",
  })
  void eachCheckboxCombinationWritesOnlySelectedColumns(
      boolean fillName, boolean fillIdealBuy, boolean fillIdealSell) throws Exception {
    try (Workbook workbook = openTemplate()) {
      TemplateMetadata metadata = analyzer.analyze(workbook);
      Sheet sheet = workbook.getSheetAt(metadata.sheetIndex());
      int firstRow = metadata.latestPeriod().dataStartRow();
      int lastRow = metadata.latestPeriod().dataEndRow();

      // Snapshot B:C:D before writing, so we can assert the unselected columns
      // keep their exact cell-level state (AC-032).
      List<String> optionalBefore = optionalColumnStates(sheet, firstRow, lastRow);

      List<WorkbookWriteRequest.StockValues> rows = stockRows(lastRow - firstRow + 1);
      writer.write(workbook, request(rows, fillName, fillIdealBuy, fillIdealSell));

      // Selected columns carry the written value.
      for (int index = 0; index < rows.size(); index++) {
        int rowIndex = firstRow + index;
        WorkbookWriteRequest.StockValues values = rows.get(index);
        assertEquals(values.code(), sheet.getRow(rowIndex).getCell(0).getStringCellValue());
        if (fillName) {
          assertEquals(values.name(), sheet.getRow(rowIndex).getCell(1).getStringCellValue());
        }
        if (fillIdealBuy) {
          assertEquals(values.idealBuy().doubleValue(),
              sheet.getRow(rowIndex).getCell(2).getNumericCellValue(), 1e-9);
        }
        if (fillIdealSell) {
          assertEquals(values.idealSell().doubleValue(),
              sheet.getRow(rowIndex).getCell(3).getNumericCellValue(), 1e-9);
        }
      }
      // Every B:C:D cell — selected or not — matches its pre-write snapshot for the
      // unselected columns. (Selected columns were overwritten, so we only assert
      // equality when a column is unselected; that is what AC-032 requires.)
      assertUnselectedColumnsUnchanged(
          sheet, firstRow, lastRow, optionalBefore, fillName, fillIdealBuy, fillIdealSell);
    }
  }

  /**
   * AC-031 + matrix item 1 (code reduction): when the new code list is shorter
   * than the existing data, the A column is rewritten with the new codes and the
   * tail (rows beyond the new list) is cleared to blank, while B:C:D everywhere
   * keep their original cell values.
   */
  @Test
  void shorterCodeListClearsOldCodeTailAndPreservesOptionalColumns() throws Exception {
    try (Workbook workbook = openTemplate()) {
      TemplateMetadata metadata = analyzer.analyze(workbook);
      Sheet sheet = workbook.getSheetAt(metadata.sheetIndex());
      int firstRow = metadata.latestPeriod().dataStartRow();
      int lastRow = metadata.latestPeriod().dataEndRow();
      List<String> optionalBefore = optionalColumnStates(sheet, firstRow, lastRow);

      // Submit fewer codes than the existing rows (template has 6 data rows).
      List<WorkbookWriteRequest.StockValues> rows = stockRows(2);
      writer.write(workbook, request(rows, false, false, false));

      // New codes occupy the first two rows.
      assertEquals("000001", sheet.getRow(firstRow).getCell(0).getStringCellValue());
      assertEquals("000002", sheet.getRow(firstRow + 1).getCell(0).getStringCellValue());
      // Tail A cells beyond the new list are blanked (AC-031).
      for (int rowIndex = firstRow + rows.size(); rowIndex <= lastRow; rowIndex++) {
        assertEquals(CellType.BLANK, sheet.getRow(rowIndex).getCell(0).getCellType());
      }
      // B:C:D untouched anywhere (AC-032 for the all-unchecked case).
      assertEquals(optionalBefore, optionalColumnStates(sheet, firstRow, lastRow));
    }
  }

  private static void assertUnselectedColumnsUnchanged(
      Sheet sheet, int firstRow, int lastRow, List<String> before,
      boolean fillName, boolean fillIdealBuy, boolean fillIdealSell) {
    int index = 0;
    for (int rowIndex = firstRow; rowIndex <= lastRow; rowIndex++) {
      Row row = sheet.getRow(rowIndex);
      for (int column = 1; column <= 3; column++) {
        boolean selected =
            (column == 1 && fillName)
                || (column == 2 && fillIdealBuy)
                || (column == 3 && fillIdealSell);
        if (!selected) {
          assertEquals(before.get(index), cellState(row == null ? null : row.getCell(column)));
        }
        index++;
      }
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
