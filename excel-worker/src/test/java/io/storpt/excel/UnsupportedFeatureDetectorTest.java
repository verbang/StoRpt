package io.storpt.excel;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
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

  // ---- HSSF (.xls) detections ---------------------------------------------

  @Test
  void acceptsCleanHssfTemplate() throws Exception {
    // Baseline: a clean .xls must not trip the detector. Until now every test
    // in this class was XSSF-only; this guards the HSSF path against false
    // positives (notably the new digital-signature stream probe).
    try (HSSFWorkbook workbook = createCompatibleHssf()) {
      try (Workbook reopened = writeAndReopenXls(workbook)) {
        assertDoesNotThrow(() -> analyzer.analyze(reopened));
      }
    }
  }

  @Test
  void rejectsHssfDigitalSignature() throws Exception {
    // A real signed .xls is unavailable, so the signature OLE2 stream is
    // injected programmatically: write a clean workbook, reopen the raw
    // container from an InputStream (the canonical POIFS read-inject-write
    // path), add the "_signatures" root stream MS-OFFCRYPTO defines for binary
    // CryptoAPI signatures, and write out. This proves the detector finds the
    // stream through HSSFWorkbook.getDirectory() rather than relying on the
    // coarse drawing-Escher fallback.
    byte[] cleanBytes;
    try (HSSFWorkbook workbook = createCompatibleHssf();
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream()) {
      workbook.write(buffer);
      cleanBytes = buffer.toByteArray();
    }

    Path signed = temporaryDirectory.resolve("signed.xls");
    try (POIFSFileSystem fs = new POIFSFileSystem(new ByteArrayInputStream(cleanBytes))) {
      fs.getRoot().createDocument(
          "_signatures",
          new ByteArrayInputStream(new byte[] {0x30, 0x2E})); // dummy ASN.1 SEQUENCE head
      try (OutputStream stream = Files.newOutputStream(signed)) {
        fs.writeFilesystem(stream);
      }
    }

    // Self-check: the injected signature stream must be physically present in
    // the written file's root storage, independent of the detector. If this
    // fails, the injection is the problem; if it passes but the detector below
    // does not fire, the detector is swallowing the failure.
    try (POIFSFileSystem probe = new POIFSFileSystem(signed.toFile())) {
      assertTrue(probe.getRoot().hasEntry("_signatures"),
          "Injected _signatures stream missing from signed.xls root storage");
    }

    try (Workbook reopened = WorkbookFactory.create(signed.toFile())) {
      assertTemplate005(reopened, "数字签名");
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

  /**
   * Minimal compatible .xls (HSSF) template, mirroring {@link #baseTemplate()}
   * but forcing recalculation-on-open so it clears {@code WorkbookCalculation}.
   * Carries no pictures, drawings or other unsupported content.
   */
  static HSSFWorkbook createCompatibleHssf() {
    HSSFWorkbook workbook = new HSSFWorkbook();
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
    workbook.setForceFormulaRecalculation(true);
    return workbook;
  }

  private Workbook writeAndReopen(Workbook workbook) throws Exception {
    Path file = temporaryDirectory.resolve("feature.xlsx");
    try (OutputStream stream = Files.newOutputStream(file)) {
      workbook.write(stream);
    }
    return WorkbookFactory.create(file.toFile());
  }

  private Workbook writeAndReopenXls(Workbook workbook) throws Exception {
    Path file = temporaryDirectory.resolve("feature.xls");
    try (OutputStream stream = Files.newOutputStream(file)) {
      workbook.write(stream);
    }
    return WorkbookFactory.create(file.toFile());
  }
}
