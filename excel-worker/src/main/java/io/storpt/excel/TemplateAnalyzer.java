package io.storpt.excel;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.SheetVisibility;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;

/**
 * Locates and validates the one visible worksheet that satisfies the strict
 * StoRpt template contract.
 *
 * <p>Rows and columns in the returned metadata are zero-based, matching the
 * Apache POI API and the worker request contract.</p>
 */
public final class TemplateAnalyzer {
  private static final String[] HEADERS = {"股票代码", "股票名称", "理想进价", "理想出价"};
  private static final Pattern PERIOD_PATTERN = Pattern.compile(
      "^(\\d{4}\\.\\d{2}\\.\\d{2}) - (\\d{4}\\.\\d{2}\\.\\d{2})$");
  private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter
      .ofPattern("uuuu.MM.dd")
      .withResolverStyle(ResolverStyle.STRICT);

  public TemplateMetadata analyze(Workbook workbook) throws TemplateAnalysisException {
    if (workbook == null) {
      throw error("TEMPLATE-001", "工作簿不能为空。");
    }

    List<SheetAnalysis> candidates = new ArrayList<>();
    for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
      if (workbook.getSheetVisibility(sheetIndex) != SheetVisibility.VISIBLE) {
        continue;
      }
      SheetAnalysis analysis = inspectSheet(workbook.getSheetAt(sheetIndex), sheetIndex);
      if (!analysis.periods().isEmpty()) {
        candidates.add(analysis);
      }
    }

    if (candidates.size() != 1) {
      throw error(
          "TEMPLATE-001",
          "必须恰好识别一个可见兼容工作表，实际找到 " + candidates.size() + " 个。");
    }

    SheetAnalysis candidate = candidates.get(0);
    TemplateMetadata.PeriodBlock latest = candidate.periods().get(candidate.periods().size() - 1);
    return new TemplateMetadata(
        candidate.sheetIndex(),
        candidate.sheetName(),
        candidate.periods(),
        latest);
  }

  private SheetAnalysis inspectSheet(Sheet sheet, int sheetIndex)
      throws TemplateAnalysisException {
    List<TemplateMetadata.PeriodBlock> periods = new ArrayList<>();
    for (int rowIndex = 0; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
      Row row = sheet.getRow(rowIndex);
      Cell titleCell = row == null ? null : row.getCell(0);
      if (titleCell == null || titleCell.getCellType() != CellType.STRING) {
        continue;
      }

      String title = titleCell.getStringCellValue();
      if (!title.contains(" - ")) {
        continue;
      }

      Matcher matcher = PERIOD_PATTERN.matcher(title);
      if (!matcher.matches()) {
        throw error("TEMPLATE-002", "第 " + (rowIndex + 1) + " 行的时间段标题格式无效。");
      }

      LocalDate start = parseDate(matcher.group(1), rowIndex);
      LocalDate end = parseDate(matcher.group(2), rowIndex);
      if (start.isAfter(end)) {
        throw error("TEMPLATE-002", "第 " + (rowIndex + 1) + " 行的开始日期晚于结束日期。");
      }
      if (!hasExactTitleMerge(sheet, rowIndex)) {
        throw error("TEMPLATE-002", "第 " + (rowIndex + 1) + " 行的日期标题必须合并 A:S。");
      }

      int headerRow = rowIndex + 1;
      validateHeaders(sheet, headerRow, rowIndex);
      periods.add(new TemplateMetadata.PeriodBlock(
          start, end, rowIndex, headerRow, rowIndex + 2, rowIndex + 1));
    }

    periods.sort(Comparator.comparingInt(TemplateMetadata.PeriodBlock::titleRow));
    validatePeriodOrder(periods);
    if (!periods.isEmpty()) {
      TemplateMetadata.PeriodBlock latest = periods.get(periods.size() - 1);
      periods.set(periods.size() - 1, latestDataBounds(sheet, latest));
    }
    return new SheetAnalysis(sheetIndex, sheet.getSheetName(), periods);
  }

  private TemplateMetadata.PeriodBlock latestDataBounds(
      Sheet sheet, TemplateMetadata.PeriodBlock period) throws TemplateAnalysisException {
    int lastDataRow = period.dataStartRow() - 1;
    boolean gapFound = false;
    for (int rowIndex = period.dataStartRow(); rowIndex <= sheet.getLastRowNum(); rowIndex++) {
      Row row = sheet.getRow(rowIndex);
      boolean hasData = false;
      boolean hasCode = false;
      for (int columnIndex = 0; columnIndex <= 3; columnIndex++) {
        Cell cell = row == null ? null : row.getCell(columnIndex);
        if (isBlank(cell)) {
          continue;
        }
        hasData = true;
        if (columnIndex == 0) {
          hasCode = true;
        }
        if (cell.getCellType() == CellType.FORMULA || isMerged(sheet, rowIndex, columnIndex)) {
          throw error(
              "TEMPLATE-004",
              "最新时间段第 " + (rowIndex + 1) + " 行的 A:D 含公式或合并单元格。");
        }
      }

      if (!hasData) {
        gapFound = true;
        continue;
      }
      if (gapFound || !hasCode) {
        throw error(
            "TEMPLATE-004",
            "最新时间段的 A:D 必须是连续股票数据，不能在空行后继续出现数据。");
      }
      lastDataRow = rowIndex;
    }

    return new TemplateMetadata.PeriodBlock(
        period.startDate(),
        period.endDate(),
        period.titleRow(),
        period.headerRow(),
        period.dataStartRow(),
        lastDataRow);
  }

  private void validateHeaders(Sheet sheet, int headerRowIndex, int titleRowIndex)
      throws TemplateAnalysisException {
    Row header = sheet.getRow(headerRowIndex);
    for (int columnIndex = 0; columnIndex < HEADERS.length; columnIndex++) {
      Cell cell = header == null ? null : header.getCell(columnIndex);
      if (cell == null
          || cell.getCellType() != CellType.STRING
          || !HEADERS[columnIndex].equals(cell.getStringCellValue())) {
        throw error(
            "TEMPLATE-001",
            "第 " + (titleRowIndex + 1) + " 行下方的 A:D 表头不符合模板契约。");
      }
    }
  }

  private static void validatePeriodOrder(List<TemplateMetadata.PeriodBlock> periods)
      throws TemplateAnalysisException {
    for (int index = 1; index < periods.size(); index++) {
      TemplateMetadata.PeriodBlock previous = periods.get(index - 1);
      TemplateMetadata.PeriodBlock current = periods.get(index);
      if (!current.startDate().isAfter(previous.startDate())
          || !current.endDate().isAfter(previous.endDate())) {
        throw error("TEMPLATE-003", "时间段日期必须从上到下严格递增。");
      }
    }
  }

  private static LocalDate parseDate(String value, int rowIndex) throws TemplateAnalysisException {
    try {
      return LocalDate.parse(value, DATE_FORMAT);
    } catch (DateTimeParseException exception) {
      throw error("TEMPLATE-002", "第 " + (rowIndex + 1) + " 行包含无效公历日期。");
    }
  }

  private static boolean hasExactTitleMerge(Sheet sheet, int rowIndex) {
    for (CellRangeAddress range : sheet.getMergedRegions()) {
      if (range.getFirstRow() == rowIndex
          && range.getLastRow() == rowIndex
          && range.getFirstColumn() == 0
          && range.getLastColumn() == 18) {
        return true;
      }
    }
    return false;
  }

  private static boolean isMerged(Sheet sheet, int rowIndex, int columnIndex) {
    for (CellRangeAddress range : sheet.getMergedRegions()) {
      if (range.isInRange(rowIndex, columnIndex)) {
        return true;
      }
    }
    return false;
  }

  private static boolean isBlank(Cell cell) {
    if (cell == null || cell.getCellType() == CellType.BLANK) {
      return true;
    }
    return cell.getCellType() == CellType.STRING && cell.getStringCellValue().isBlank();
  }

  private static TemplateAnalysisException error(String code, String message) {
    return new TemplateAnalysisException(code, message);
  }

  private record SheetAnalysis(int sheetIndex, String sheetName,
      List<TemplateMetadata.PeriodBlock> periods) {}
}
