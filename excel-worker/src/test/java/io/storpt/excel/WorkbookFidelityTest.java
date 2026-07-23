package io.storpt.excel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellRangeAddress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkbookFidelityTest {

  private static final int TARGET_SHEET = 0;
  private static final int LATEST_TITLE_ROW = 27;
  private static final int FIRST_DATA_ROW = 29;
  private static final int LAST_WRITABLE_COLUMN = 3;

  @TempDir
  Path temporaryDirectory;

  @Test
  void xlsxRoundTripPreservesFullSemanticSnapshot() throws Exception {
    Path source = platformTemplate();
    Path output = temporaryDirectory.resolve("platform-roundtrip.xlsx");

    WorkbookSnapshot before = snapshot(source, WorkbookSnapshot.CellSelector.ALL);
    roundTrip(source, output);
    WorkbookSnapshot after = snapshot(output, WorkbookSnapshot.CellSelector.ALL);

    assertEquals(before, after, "A no-op .xlsx save changed workbook semantics");
  }

  @Test
  void xlsxAllowlistedWritePreservesProtectedContent() throws Exception {
    Path source = platformTemplate();
    Path output = temporaryDirectory.resolve("platform-allowlisted-write.xlsx");
    WorkbookSnapshot.CellSelector protectedCells = WorkbookFidelityTest::isProtectedCell;
    WorkbookSnapshot protectedBefore = snapshot(source, protectedCells);

    try (Workbook workbook = open(source)) {
      Sheet sheet = workbook.getSheetAt(TARGET_SHEET);
      sheet.getRow(LATEST_TITLE_ROW).getCell(0)
          .setCellValue("2026.02.02 - 2026.02.06");

      Row row = sheet.getRow(FIRST_DATA_ROW);
      row.getCell(0).setCellValue("000001");
      row.getCell(1).setCellValue("平安银行");
      row.getCell(2).setCellValue(10.25d);
      row.getCell(3).setCellValue(11.50d);
      write(workbook, output);
    }

    WorkbookSnapshot protectedAfter = snapshot(output, protectedCells);
    assertEquals(
        protectedBefore,
        protectedAfter,
        "An allowlisted A:D write changed protected workbook content");

    try (Workbook workbook = open(output)) {
      Sheet sheet = workbook.getSheetAt(TARGET_SHEET);
      assertEquals("2026.02.02 - 2026.02.06", sheet.getRow(LATEST_TITLE_ROW).getCell(0)
          .getStringCellValue());
      assertEquals("000001", sheet.getRow(FIRST_DATA_ROW).getCell(0).getStringCellValue());
      assertEquals("平安银行", sheet.getRow(FIRST_DATA_ROW).getCell(1).getStringCellValue());
      assertEquals(10.25d, sheet.getRow(FIRST_DATA_ROW).getCell(2).getNumericCellValue());
      assertEquals(11.50d, sheet.getRow(FIRST_DATA_ROW).getCell(3).getNumericCellValue());
    }
  }

  @Test
  void generatedXlsRoundTripPreservesFullSemanticSnapshot() throws Exception {
    Path source = temporaryDirectory.resolve("generated-compatible.xls");
    Path output = temporaryDirectory.resolve("generated-compatible-roundtrip.xls");
    createCompatibleXls(source);

    WorkbookSnapshot before = snapshot(source, WorkbookSnapshot.CellSelector.ALL);
    roundTrip(source, output);
    WorkbookSnapshot after = snapshot(output, WorkbookSnapshot.CellSelector.ALL);

    assertEquals(before, after, "A no-op .xls save changed workbook semantics");
  }

  private static boolean isProtectedCell(int sheetIndex, int rowIndex, int columnIndex) {
    if (sheetIndex != TARGET_SHEET) {
      return true;
    }
    boolean titleValue = rowIndex == LATEST_TITLE_ROW && columnIndex == 0;
    boolean latestDataCell = rowIndex >= FIRST_DATA_ROW && columnIndex <= LAST_WRITABLE_COLUMN;
    return !titleValue && !latestDataCell;
  }

  private static Path platformTemplate() {
    Path template = Path.of(System.getProperty("storpt.template", "../platform.xlsx"))
        .toAbsolutePath()
        .normalize();
    assertTrue(Files.isRegularFile(template), "Missing template: " + template);
    return template;
  }

  private static WorkbookSnapshot snapshot(
      Path path, WorkbookSnapshot.CellSelector selector) throws Exception {
    try (Workbook workbook = open(path)) {
      return WorkbookSnapshot.capture(workbook, selector);
    }
  }

  private static void roundTrip(Path source, Path output) throws Exception {
    try (Workbook workbook = open(source)) {
      write(workbook, output);
    }
    try (Workbook ignored = open(output)) {
      // Reopening is a separate release condition from snapshot equality.
    }
  }

  private static Workbook open(Path path) throws Exception {
    return WorkbookFactory.create(path.toFile());
  }

  private static void write(Workbook workbook, Path output) throws IOException {
    try (OutputStream stream = Files.newOutputStream(output)) {
      workbook.write(stream);
    }
  }

  private static void createCompatibleXls(Path output) throws IOException {
    try (Workbook workbook = new HSSFWorkbook()) {
      Sheet sheet = workbook.createSheet("Sheet1");
      sheet.setColumnWidth(0, 12 * 256);
      sheet.setColumnWidth(1, 14 * 256);

      Row title = sheet.createRow(0);
      title.createCell(0).setCellValue("2026.01.05 - 2026.01.09");
      sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 18));

      Row headers = sheet.createRow(1);
      String[] values = {"股票代码", "股票名称", "理想进价", "理想出价"};
      for (int column = 0; column < values.length; column++) {
        headers.createCell(column).setCellValue(values[column]);
      }

      CellStyle codeStyle = workbook.createCellStyle();
      codeStyle.setDataFormat(workbook.createDataFormat().getFormat("@"));
      codeStyle.setFillForegroundColor(IndexedColors.LIGHT_YELLOW.getIndex());
      codeStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
      Font font = workbook.createFont();
      font.setBold(true);
      codeStyle.setFont(font);

      for (int rowIndex = 2; rowIndex <= 3; rowIndex++) {
        Row row = sheet.createRow(rowIndex);
        Cell code = row.createCell(0);
        code.setCellStyle(codeStyle);
        code.setCellValue(rowIndex == 2 ? "000001" : "600000");
        row.createCell(1).setCellValue(rowIndex == 2 ? "平安银行" : "浦发银行");
        row.createCell(2).setCellValue(10.0d + rowIndex);
        row.createCell(3).setCellValue(11.0d + rowIndex);
        row.createCell(4).setCellFormula("D" + (rowIndex + 1) + "-C" + (rowIndex + 1));
      }

      workbook.setForceFormulaRecalculation(true);
      write(workbook, output);
    }
  }
}
