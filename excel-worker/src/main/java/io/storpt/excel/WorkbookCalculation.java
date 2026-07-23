package io.storpt.excel;

import org.apache.poi.hssf.record.CalcModeRecord;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/** Reads format-specific calculation settings without changing the workbook. */
final class WorkbookCalculation {
  private WorkbookCalculation() {}

  static boolean supportsRecalculation(Workbook workbook) {
    return workbook.getForceFormulaRecalculation() || !"manual".equals(mode(workbook));
  }

  static String mode(Workbook workbook) {
    if (workbook instanceof XSSFWorkbook xssf) {
      var properties = xssf.getCTWorkbook().getCalcPr();
      if (properties == null || !properties.isSetCalcMode()) {
        return "automatic-default";
      }
      return properties.getCalcMode().toString();
    }
    if (workbook instanceof HSSFWorkbook hssf) {
      CalcModeRecord record = (CalcModeRecord) hssf.getInternalWorkbook()
          .findFirstRecordBySid(CalcModeRecord.sid);
      if (record == null) {
        return "automatic-default";
      }
      return switch (record.getCalcMode()) {
        case CalcModeRecord.MANUAL -> "manual";
        case CalcModeRecord.AUTOMATIC_EXCEPT_TABLES -> "automatic-except-tables";
        default -> "automatic";
      };
    }
    return "unsupported";
  }
}
