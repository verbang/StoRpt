package io.storpt.excel;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFClientAnchor;
import org.apache.poi.xssf.usermodel.XSSFDrawing;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises {@link UnsupportedFeatureDetector} by building minimal XSSF
 * workbooks that each carry exactly one unsupported feature, writing them to
 * disk, reopening them, and asserting the detector throws TEMPLATE-005.
 *
 * <p>Disk round-trip is used because several POI detections (drawings, pictures,
 * external links) only materialise after the workbook is serialised and
 * reopened. Each test seeds the feature via a stable, documented POI API rather
 * than poking XMLBeans directly, so it compiles and runs across POI 5.4.x.</p>
 */
class UnsupportedFeatureDetectorTest {
  private final TemplateAnalyzer analyzer = new TemplateAnalyzer();

  @TempDir
  Path temporaryDirectory;

  @Test
  void rejectsSheetProtection() throws Exception {
    try (XSSFWorkbook workbook = baseTemplate()) {
      workbook.getSheetAt(0).protectSheet("pwd");
      assertTemplate005(writeAndReopen(workbook), "工作表保护");
    }
  }

  @Test
  void rejectsWorkbookStructureProtection() throws Exception {
    try (XSSFWorkbook workbook = baseTemplate()) {
      workbook.lockStructure();
      assertTemplate005(writeAndReopen(workbook), "结构保护");
    }
  }

  @Test
  void rejectsChart() throws Exception {
    try (XSSFWorkbook workbook = baseTemplate()) {
      XSSFDrawing drawing = workbook.getSheetAt(0).createDrawingPatriarch();
      drawing.createChart(new XSSFClientAnchor(0, 0, 0, 0, 5, 1, 8, 10));
      assertTemplate005(writeAndReopen(workbook), "图表");
    }
  }

  @Test
  void rejectsPicture() throws Exception {
    try (XSSFWorkbook workbook = baseTemplate()) {
      XSSFSheet sheet = workbook.getSheetAt(0);
      int pictureIdx = workbook.addPicture(
          new byte[]{1, 2, 3, 4}, Workbook.PICTURE_TYPE_PNG);
      XSSFDrawing drawing = sheet.createDrawingPatriarch();
      drawing.createPicture(new XSSFClientAnchor(0, 0, 0, 0, 5, 1, 8, 10), pictureIdx);
      assertTemplate005(writeAndReopen(workbook), "图片");
    }
  }

  @Test
  void rejectsShape() throws Exception {
    try (XSSFWorkbook workbook = baseTemplate()) {
      XSSFDrawing drawing = workbook.getSheetAt(0).createDrawingPatriarch();
      drawing.createTextbox(new XSSFClientAnchor(0, 0, 0, 0, 5, 1, 8, 3));
      assertTemplate005(writeAndReopen(workbook), "形状");
    }
  }

  @Test
  void acceptsCleanTemplate() throws Exception {
    try (XSSFWorkbook workbook = baseTemplate()) {
      try (Workbook reopened = writeAndReopen(workbook)) {
        assertDoesNotThrow(() -> analyzer.analyze(reopened));
      }
    }
  }

  // ---- helpers ------------------------------------------------------------

  private void assertTemplate005(Workbook workbook, String featureFragment) {
    TemplateAnalysisException exception = assertThrows(
        TemplateAnalysisException.class,
        () -> analyzer.analyze(workbook));
    assertEquals("TEMPLATE-005", exception.code());
    if (exception.getMessage() == null || !exception.getMessage().contains(featureFragment)) {
      throw new AssertionError(
          "TEMPLATE-005 message should mention '" + featureFragment
              + "' but was: " + exception.getMessage());
    }
  }

  /**
   * Builds the minimal compatible template (one period, A:S title merge, A:D
   * header + one data row) used as the base for every feature test.
   */
  static XSSFWorkbook baseTemplate() {
    XSSFWorkbook workbook = new XSSFWorkbook();
    Sheet sheet = workbook.createSheet("Sheet1");
    Row title = sheet.createRow(0);
    title.createCell(0).setCellValue("2026.01.05 - 2026.01.09");
    sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 18));
    Row header = sheet.createRow(1);
    header.createCell(0).setCellValue("股票代码");
    header.createCell(1).setCellValue("股票名称");
    header.createCell(2).setCellValue("理想进价");
    header.createCell(3).setCellValue("理想出价");
    Row data = sheet.createRow(2);
    data.createCell(0, CellType.STRING).setCellValue("000001");
    return workbook;
  }

  private Workbook writeAndReopen(Workbook workbook) throws Exception {
    Path file = temporaryDirectory.resolve("feature.xlsx");
    try (OutputStream stream = Files.newOutputStream(file)) {
      workbook.write(stream);
    }
    return WorkbookFactory.create(file.toFile());
  }
}
