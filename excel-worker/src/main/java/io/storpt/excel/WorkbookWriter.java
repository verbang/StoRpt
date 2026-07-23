package io.storpt.excel;

import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;

/** Performs only the date-title and latest-period A:D writes authorized by the MVP contract. */
public final class WorkbookWriter {
  private static final Pattern CODE_PATTERN = Pattern.compile("^[0-9]{6}$");
  private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter
      .ofPattern("uuuu.MM.dd")
      .withResolverStyle(ResolverStyle.STRICT);

  private final TemplateAnalyzer analyzer;

  public WorkbookWriter() {
    this(new TemplateAnalyzer());
  }

  WorkbookWriter(TemplateAnalyzer analyzer) {
    this.analyzer = analyzer;
  }

  public WriteSummary write(Workbook workbook, WorkbookWriteRequest request)
      throws TemplateAnalysisException, WorkbookWriteException {
    if (workbook == null || request == null) {
      throw error("INPUT-001", "工作簿和写入请求不能为空。");
    }

    TemplateMetadata metadata = analyzer.analyze(workbook);
    validateCoordinates(metadata, request);
    validateRequest(metadata, request);

    TemplateMetadata.PeriodBlock latest = metadata.latestPeriod();
    int oldDataEndRow = latest.dataEndRow();
    int newDataEndRow = request.dataStartRow() + request.rows().size() - 1;
    if (newDataEndRow >= workbook.getSpreadsheetVersion().getMaxRows()) {
      throw error("INPUT-001", "股票数量超过当前 Excel 格式的最大行数。");
    }

    Sheet sheet = workbook.getSheetAt(request.sheetIndex());
    Row sampleRow = formatSampleRow(sheet, latest);
    Map<Short, CellStyle> copiedStyles = new HashMap<>();

    Cell titleCell = requiredCell(sheet, request.titleRow(), 0, "日期标题");
    titleCell.setCellValue(
        DATE_FORMAT.format(request.startDate()) + " - " + DATE_FORMAT.format(request.endDate()));

    for (int index = 0; index < request.rows().size(); index++) {
      int rowIndex = request.dataStartRow() + index;
      Row targetRow = sheet.getRow(rowIndex);
      if (targetRow == null) {
        targetRow = sheet.createRow(rowIndex);
      }
      WorkbookWriteRequest.StockValues values = request.rows().get(index);

      writableCell(workbook, targetRow, sampleRow, 0, copiedStyles).setCellValue(values.code());
      if (request.fillName()) {
        writableCell(workbook, targetRow, sampleRow, 1, copiedStyles)
            .setCellValue(values.name());
      }
      if (request.fillIdealBuy()) {
        writableCell(workbook, targetRow, sampleRow, 2, copiedStyles)
            .setCellValue(values.idealBuy().doubleValue());
      }
      if (request.fillIdealSell()) {
        writableCell(workbook, targetRow, sampleRow, 3, copiedStyles)
            .setCellValue(values.idealSell().doubleValue());
      }
    }

    for (int rowIndex = newDataEndRow + 1; rowIndex <= oldDataEndRow; rowIndex++) {
      Row row = sheet.getRow(rowIndex);
      Cell codeCell = row == null ? null : row.getCell(0);
      if (codeCell != null) {
        codeCell.setBlank();
      }
    }

    return new WriteSummary(
        metadata.sheetIndex(),
        request.titleRow(),
        request.dataStartRow(),
        oldDataEndRow,
        newDataEndRow,
        request.rows().size());
  }

  private static void validateCoordinates(
      TemplateMetadata metadata, WorkbookWriteRequest request) throws WorkbookWriteException {
    TemplateMetadata.PeriodBlock latest = metadata.latestPeriod();
    if (request.sheetIndex() != metadata.sheetIndex()
        || request.titleRow() != latest.titleRow()
        || request.dataStartRow() != latest.dataStartRow()) {
      throw error("TEMPLATE-001", "写入坐标与重新分析得到的最新时间段不一致。");
    }
  }

  private static void validateRequest(
      TemplateMetadata metadata, WorkbookWriteRequest request) throws WorkbookWriteException {
    if (request.startDate() == null || request.endDate() == null) {
      throw error("INPUT-001", "开始日期和结束日期不能为空。");
    }
    if (request.startDate().isAfter(request.endDate())) {
      throw error("INPUT-001", "开始日期不能晚于结束日期。");
    }
    if (metadata.periods().size() > 1) {
      TemplateMetadata.PeriodBlock previous = metadata.periods().get(metadata.periods().size() - 2);
      if (!request.startDate().isAfter(previous.startDate())
          || !request.endDate().isAfter(previous.endDate())) {
        throw error("TEMPLATE-003", "修改后的日期必须分别严格晚于上一时间段。");
      }
    }
    if (request.rows().isEmpty()) {
      throw error("INPUT-001", "至少需要一只股票。");
    }

    Set<String> codes = new HashSet<>();
    for (WorkbookWriteRequest.StockValues values : request.rows()) {
      if (values == null || values.code() == null || !CODE_PATTERN.matcher(values.code()).matches()) {
        throw error("INPUT-001", "股票代码必须是六位纯数字。");
      }
      if (!codes.add(values.code())) {
        throw error("INPUT-001", "股票代码不能重复：" + values.code());
      }
      if (request.fillName() && (values.name() == null || values.name().isBlank())) {
        throw error("INPUT-001", "勾选股票名称时每行都必须提供名称。");
      }
      if (request.fillIdealBuy() && values.idealBuy() == null) {
        throw error("INPUT-001", "勾选理想进价时每行都必须提供价格。");
      }
      if (request.fillIdealSell() && values.idealSell() == null) {
        throw error("INPUT-001", "勾选理想出价时每行都必须提供价格。");
      }
    }
  }

  private static Row formatSampleRow(Sheet sheet, TemplateMetadata.PeriodBlock latest)
      throws WorkbookWriteException {
    if (latest.dataEndRow() < latest.dataStartRow()) {
      throw error("TEMPLATE-004", "最新时间段缺少可靠的格式样板行。");
    }
    Row row = sheet.getRow(latest.dataEndRow());
    if (row == null) {
      throw error("TEMPLATE-004", "最新时间段缺少可靠的格式样板行。");
    }
    return row;
  }

  private static Cell requiredCell(Sheet sheet, int rowIndex, int columnIndex, String label)
      throws WorkbookWriteException {
    Row row = sheet.getRow(rowIndex);
    Cell cell = row == null ? null : row.getCell(columnIndex);
    if (cell == null) {
      throw error("TEMPLATE-004", label + "单元格不存在。");
    }
    return cell;
  }

  private static Cell writableCell(
      Workbook workbook,
      Row targetRow,
      Row sampleRow,
      int columnIndex,
      Map<Short, CellStyle> copiedStyles) throws WorkbookWriteException {
    Cell cell = targetRow.getCell(columnIndex);
    if (cell != null) {
      return cell;
    }

    Cell sampleCell = sampleRow.getCell(columnIndex);
    if (sampleCell == null) {
      throw error("TEMPLATE-004", "格式样板行缺少 " + columnName(columnIndex) + " 列单元格。");
    }
    cell = targetRow.createCell(columnIndex);
    cell.setCellStyle(copyAllowedStyle(workbook, sampleCell.getCellStyle(), copiedStyles));
    return cell;
  }

  private static CellStyle copyAllowedStyle(
      Workbook workbook, CellStyle source, Map<Short, CellStyle> copiedStyles) {
    short sourceIndex = source.getIndex();
    CellStyle cached = copiedStyles.get(sourceIndex);
    if (cached != null) {
      return cached;
    }

    CellStyle target = workbook.createCellStyle();
    target.setFont(workbook.getFontAt(source.getFontIndexAsInt()));
    target.setFillForegroundColor(source.getFillForegroundColor());
    target.setFillBackgroundColor(source.getFillBackgroundColor());
    target.setFillPattern(source.getFillPattern());
    target.setBorderTop(source.getBorderTop());
    target.setBorderRight(source.getBorderRight());
    target.setBorderBottom(source.getBorderBottom());
    target.setBorderLeft(source.getBorderLeft());
    target.setTopBorderColor(source.getTopBorderColor());
    target.setRightBorderColor(source.getRightBorderColor());
    target.setBottomBorderColor(source.getBottomBorderColor());
    target.setLeftBorderColor(source.getLeftBorderColor());
    target.setAlignment(source.getAlignment());
    target.setVerticalAlignment(source.getVerticalAlignment());
    target.setWrapText(source.getWrapText());
    target.setShrinkToFit(source.getShrinkToFit());
    target.setRotation(source.getRotation());
    target.setIndention(source.getIndention());
    target.setDataFormat(source.getDataFormat());
    copiedStyles.put(sourceIndex, target);
    return target;
  }

  private static String columnName(int columnIndex) {
    return Character.toString((char) ('A' + columnIndex));
  }

  private static WorkbookWriteException error(String code, String message) {
    return new WorkbookWriteException(code, message);
  }

  public record WriteSummary(
      int sheetIndex,
      int titleRow,
      int dataStartRow,
      int oldDataEndRow,
      int newDataEndRow,
      int writtenRows) {}
}
