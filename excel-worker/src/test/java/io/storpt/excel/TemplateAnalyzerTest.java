package io.storpt.excel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.SheetVisibility;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class TemplateAnalyzerTest {
  private final TemplateAnalyzer analyzer = new TemplateAnalyzer();

  @Test
  void analyzesPlatformTemplate() throws Exception {
    Path template = Path.of(System.getProperty("storpt.template", "../platform.xlsx"))
        .toAbsolutePath()
        .normalize();
    assertTrue(Files.isRegularFile(template), "Missing template: " + template);

    try (Workbook workbook = WorkbookFactory.create(template.toFile())) {
      TemplateMetadata metadata = analyzer.analyze(workbook);

      assertEquals(0, metadata.sheetIndex());
      assertEquals("Sheet1", metadata.sheetName());
      assertEquals(4, metadata.periods().size());
      assertEquals(LocalDate.of(2026, 1, 26), metadata.latestPeriod().startDate());
      assertEquals(LocalDate.of(2026, 1, 30), metadata.latestPeriod().endDate());
      assertEquals(27, metadata.latestPeriod().titleRow());
      assertEquals(28, metadata.latestPeriod().headerRow());
      assertEquals(29, metadata.latestPeriod().dataStartRow());
      assertEquals(34, metadata.latestPeriod().dataEndRow());
    }
  }

  @Test
  void rejectsWorkbookWithoutVisibleCandidate() throws Exception {
    try (Workbook workbook = new XSSFWorkbook()) {
      workbook.createSheet("Blank");

      TemplateAnalysisException exception = assertThrows(
          TemplateAnalysisException.class,
          () -> analyzer.analyze(workbook));

      assertEquals("TEMPLATE-001", exception.code());
    }
  }

  @Test
  void rejectsMultipleVisibleCandidates() throws Exception {
    try (Workbook workbook = new XSSFWorkbook()) {
      addPeriod(workbook.createSheet("First"), 0, "2026.01.05 - 2026.01.09");
      addPeriod(workbook.createSheet("Second"), 0, "2026.01.12 - 2026.01.16");

      TemplateAnalysisException exception = assertThrows(
          TemplateAnalysisException.class,
          () -> analyzer.analyze(workbook));

      assertEquals("TEMPLATE-001", exception.code());
    }
  }

  @Test
  void ignoresHiddenCompatibleWorksheet() throws Exception {
    try (Workbook workbook = new XSSFWorkbook()) {
      addPeriod(workbook.createSheet("Visible"), 0, "2026.01.05 - 2026.01.09");
      addPeriod(workbook.createSheet("Hidden"), 0, "2026.01.12 - 2026.01.16");
      workbook.setSheetVisibility(1, SheetVisibility.HIDDEN);

      TemplateMetadata metadata = analyzer.analyze(workbook);

      assertEquals("Visible", metadata.sheetName());
    }
  }

  @Test
  void rejectsNonIncreasingPeriods() throws Exception {
    try (Workbook workbook = new XSSFWorkbook()) {
      Sheet sheet = workbook.createSheet("Sheet1");
      addPeriod(sheet, 0, "2026.01.12 - 2026.01.16");
      addPeriod(sheet, 4, "2026.01.05 - 2026.01.09");

      TemplateAnalysisException exception = assertThrows(
          TemplateAnalysisException.class,
          () -> analyzer.analyze(workbook));

      assertEquals("TEMPLATE-003", exception.code());
    }
  }

  @Test
  void rejectsDataAfterGapInLatestPeriod() throws Exception {
    try (Workbook workbook = new XSSFWorkbook()) {
      Sheet sheet = workbook.createSheet("Sheet1");
      addPeriod(sheet, 0, "2026.01.05 - 2026.01.09");
      sheet.createRow(2).createCell(0).setCellValue("000001");
      sheet.createRow(4).createCell(0).setCellValue("600000");

      TemplateAnalysisException exception = assertThrows(
          TemplateAnalysisException.class,
          () -> analyzer.analyze(workbook));

      assertEquals("TEMPLATE-004", exception.code());
    }
  }

  @Test
  void rejectsInvalidCalendarDate() throws Exception {
    try (Workbook workbook = new XSSFWorkbook()) {
      addPeriod(workbook.createSheet("Sheet1"), 0, "2026.02.30 - 2026.03.06");

      TemplateAnalysisException exception = assertThrows(
          TemplateAnalysisException.class,
          () -> analyzer.analyze(workbook));

      assertEquals("TEMPLATE-002", exception.code());
    }
  }

  private static void addPeriod(Sheet sheet, int titleRowIndex, String title) {
    Row titleRow = sheet.createRow(titleRowIndex);
    titleRow.createCell(0).setCellValue(title);
    sheet.addMergedRegion(new CellRangeAddress(titleRowIndex, titleRowIndex, 0, 18));

    Row header = sheet.createRow(titleRowIndex + 1);
    header.createCell(0).setCellValue("股票代码");
    header.createCell(1).setCellValue("股票名称");
    header.createCell(2).setCellValue("理想进价");
    header.createCell(3).setCellValue("理想出价");
  }
}
