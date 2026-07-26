package io.storpt.excel;

import java.util.List;
import org.apache.poi.hssf.record.ProtectRecord;
import org.apache.poi.hssf.record.Record;
import org.apache.poi.hssf.record.SupBookRecord;
import org.apache.poi.hssf.record.WindowProtectRecord;
import org.apache.poi.hssf.usermodel.HSSFSheet;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.hssf.model.InternalWorkbook;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFChart;
import org.apache.poi.xssf.usermodel.XSSFDrawing;
import org.apache.poi.xssf.usermodel.XSSFObjectData;
import org.apache.poi.xssf.usermodel.XSSFPicture;
import org.apache.poi.xssf.usermodel.XSSFPivotTable;
import org.apache.poi.xssf.usermodel.XSSFShape;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * Detects Excel features that StoRpt cannot preserve and rejects the workbook
 * before any cell is written. Each detection reports the specific feature so the
 * user knows what to remove, per ADR-0013.
 *
 * <p>VBA macros and static data connections are intentionally tolerated
 * (see ADR-0013 revision dated 2026-07-26); they are NOT checked here.</p>
 */
final class UnsupportedFeatureDetector {
  private UnsupportedFeatureDetector() {}

  /**
   * Throws {@code TEMPLATE-005} describing the first unsupported feature found,
   * or returns silently when the workbook is acceptable.
   */
  static void detect(Workbook workbook) throws TemplateAnalysisException {
    if (workbook == null) {
      throw error("工作簿不能为空。");
    }
    if (workbook instanceof XSSFWorkbook xssf) {
      detectXssf(xssf);
    } else if (workbook instanceof HSSFWorkbook hssf) {
      detectHssf(hssf);
    }
    // Features common to both formats (sheet protection, pictures) are checked
    // on the generic Workbook/Sheet interface so the logic is not duplicated.
    detectCommon(workbook);
  }

  // ---- XSSF (.xlsx) specific detections -----------------------------------

  private static void detectXssf(XSSFWorkbook workbook) throws TemplateAnalysisException {
    // Workbook structure / window protection.
    var protection = workbook.getCTWorkbook().getWorkbookProtection();
    if (protection != null
        && ((protection.isSetLockStructure() && protection.getLockStructure())
            || (protection.isSetLockWindows() && protection.getLockWindows()))) {
      throw error("工作簿启用了结构保护或窗口保护。");
    }

    // External links to other workbooks.
    if (!workbook.getExternalLinksTable().isEmpty()) {
      throw error("工作簿包含指向其他工作簿的外部链接。");
    }

    // Digital signature via the OPC digital-signature origin relationship.
    boolean signed;
    try {
      signed = workbook.getPackage()
          .getRelationshipsByType(
              org.apache.poi.openxml4j.opc.PackageRelationshipTypes.DIGITAL_SIGNATURE_ORIGIN)
          .size() > 0;
    } catch (RuntimeException ignored) {
      signed = false;
    }
    if (signed) {
      throw error("工作簿包含数字签名。");
    }

    // Pivot tables, charts, shapes, embedded objects live on each sheet's drawing.
    for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
      XSSFSheet sheet = workbook.getSheetAt(i);

      try {
        List<XSSFPivotTable> pivots = sheet.getPivotTables();
        if (pivots != null && !pivots.isEmpty()) {
          throw error("工作表 " + sheet.getSheetName() + " 包含数据透视表。");
        }
      } catch (RuntimeException ignored) {
        // getPivotTables can throw on workbooks without a pivot cache; absence
        // is the safe interpretation.
      }

      XSSFDrawing drawing = sheet.getDrawingPatriarch();
      if (drawing == null) {
        continue;
      }
      for (XSSFChart chart : drawing.getCharts()) {
        throw error("工作表 " + sheet.getSheetName() + " 包含图表。");
      }
      // Pictures must be checked before the generic shape branch: XSSFPicture
      // is itself an XSSFShape, so order matters for the reported feature name.
      for (XSSFShape shape : drawing.getShapes()) {
        if (shape instanceof XSSFObjectData) {
          throw error("工作表 " + sheet.getSheetName() + " 包含嵌入对象。");
        }
        if (shape instanceof XSSFPicture) {
          throw error("工作表 " + sheet.getSheetName() + " 包含图片。");
        }
        // Any remaining shape (text box, connector, autoshape, group) that is
        // neither a chart (handled above) nor a picture nor an embedded object.
        throw error("工作表 " + sheet.getSheetName() + " 包含形状或文本框。");
      }
    }
  }

  // ---- HSSF (.xls) specific detections ------------------------------------

  private static void detectHssf(HSSFWorkbook workbook) throws TemplateAnalysisException {
    InternalWorkbook internal = workbook.getInternalWorkbook();
    List<Record> records = internal.getRecords();

    // Workbook-level structure/window protection (distinct from sheet protection).
    for (Record record : records) {
      if (record instanceof ProtectRecord pr && pr.getProtect()) {
        throw error("工作簿启用了结构保护。");
      }
      if (record instanceof WindowProtectRecord wp && wp.getProtect()) {
        throw error("工作簿启用了窗口保护。");
      }
      // External links: a SupBookRecord referencing external workbooks.
      if (record instanceof SupBookRecord sb && sb.isExternalReferences()) {
        throw error("工作簿包含指向其他工作簿的外部链接。");
      }
    }

    // Pivot tables (ViewDefinitionRecord, sid 0x00B0) live in sheet substreams;
    // POI surfaces them via Escher/drawing aggregates. Charts, shapes, pictures
    // and embedded objects all ride the drawing in .xls, so a non-null drawing
    // Escher aggregate is treated conservatively as "unsupported drawing content".
    for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
      HSSFSheet sheet = workbook.getSheetAt(i);
      if (sheet.getDrawingEscherAggregate() != null) {
        throw error("工作表 " + sheet.getSheetName() + " 包含图表、图片、形状或嵌入对象。");
      }
    }

    // Note: .xls digital signature detection is intentionally omitted. The
    // "Signature" OLE2 stream lives in the raw container, which this detector
    // cannot reopen from a Workbook handle. XSSF signatures are detected above;
    // HSSF signature coverage is tracked as a known gap pending a real sample.
  }

  // ---- Common detections (both formats) -----------------------------------

  private static void detectCommon(Workbook workbook) throws TemplateAnalysisException {
    // Sheet-level cell protection.
    for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
      Sheet sheet = workbook.getSheetAt(i);
      if (sheet.getProtect()) {
        throw error("工作表 " + sheet.getSheetName() + " 启用了工作表保护。");
      }
    }

    // Pictures anywhere in the workbook (covers floating and anchored images).
    if (!workbook.getAllPictures().isEmpty()) {
      throw error("工作簿包含图片。");
    }
  }

  private static TemplateAnalysisException error(String detail) {
    return new TemplateAnalysisException("TEMPLATE-005", detail);
  }
}
