package io.storpt.excel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Hyperlink;
import org.apache.poi.ss.usermodel.Name;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;

/**
 * A format-neutral, semantic workbook snapshot used to detect unauthorized
 * changes after Apache POI saves and reopens a file.
 */
public record WorkbookSnapshot(
    int activeSheetIndex,
    int firstVisibleTab,
    boolean forceFormulaRecalculation,
    String calculationMode,
    List<NameSnapshot> names,
    List<SheetSnapshot> sheets) {

  public WorkbookSnapshot {
    names = List.copyOf(names);
    sheets = List.copyOf(sheets);
  }

  public static WorkbookSnapshot capture(Workbook workbook) {
    return capture(workbook, CellSelector.ALL);
  }

  public static WorkbookSnapshot capture(Workbook workbook, CellSelector selector) {
    Objects.requireNonNull(workbook, "workbook");
    Objects.requireNonNull(selector, "selector");

    List<NameSnapshot> names = workbook.getAllNames().stream()
        .map(WorkbookSnapshot::captureName)
        .sorted(Comparator.comparing(NameSnapshot::name)
            .thenComparingInt(NameSnapshot::sheetIndex))
        .toList();

    List<SheetSnapshot> sheets = new ArrayList<>();
    for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
      sheets.add(captureSheet(workbook, sheetIndex, selector));
    }

    return new WorkbookSnapshot(
        workbook.getActiveSheetIndex(),
        workbook.getFirstVisibleTab(),
        workbook.getForceFormulaRecalculation(),
        WorkbookCalculation.mode(workbook),
        names,
        sheets);
  }

  private static NameSnapshot captureName(Name name) {
    return new NameSnapshot(
        name.getNameName(),
        name.getRefersToFormula(),
        name.getSheetIndex(),
        name.isFunctionName());
  }

  private static SheetSnapshot captureSheet(
      Workbook workbook, int sheetIndex, CellSelector selector) {
    Sheet sheet = workbook.getSheetAt(sheetIndex);
    int maximumColumn = maximumColumn(sheet);

    List<ColumnSnapshot> columns = new ArrayList<>();
    for (int columnIndex = 0; columnIndex <= maximumColumn; columnIndex++) {
      columns.add(new ColumnSnapshot(
          columnIndex,
          sheet.getColumnWidth(columnIndex),
          sheet.isColumnHidden(columnIndex),
          captureStyle(workbook, sheet.getColumnStyle(columnIndex))));
    }

    List<RowSnapshot> rows = new ArrayList<>();
    for (Row row : sheet) {
      List<CellSnapshot> cells = new ArrayList<>();
      for (Cell cell : row) {
        if (selector.include(sheetIndex, row.getRowNum(), cell.getColumnIndex())) {
          cells.add(captureCell(workbook, cell));
        }
      }
      boolean hasNonDefaultRowMetadata = row.getHeight() != sheet.getDefaultRowHeight()
          || row.getZeroHeight()
          || row.getOutlineLevel() != 0;
      if (!cells.isEmpty() || hasNonDefaultRowMetadata) {
        rows.add(new RowSnapshot(
            row.getRowNum(),
            row.getHeight(),
            row.getZeroHeight(),
            row.getOutlineLevel(),
            cells));
      }
    }

    List<String> mergedRegions = new ArrayList<>();
    for (int index = 0; index < sheet.getNumMergedRegions(); index++) {
      mergedRegions.add(sheet.getMergedRegion(index).formatAsString());
    }
    mergedRegions.sort(String::compareTo);

    return new SheetSnapshot(
        sheetIndex,
        sheet.getSheetName(),
        workbook.getSheetVisibility(sheetIndex).name(),
        sheet.getDefaultColumnWidth(),
        sheet.getDefaultRowHeight(),
        sheet.isDisplayGridlines(),
        sheet.isPrintGridlines(),
        sheet.getFitToPage(),
        columns,
        rows,
        mergedRegions);
  }

  private static int maximumColumn(Sheet sheet) {
    int maximumColumn = 18; // The current template contract spans A:S.
    for (Row row : sheet) {
      if (row.getLastCellNum() > 0) {
        maximumColumn = Math.max(maximumColumn, row.getLastCellNum() - 1);
      }
    }
    return maximumColumn;
  }

  private static CellSnapshot captureCell(Workbook workbook, Cell cell) {
    String commentAuthor = null;
    String commentText = null;
    if (cell.getCellComment() != null) {
      commentAuthor = cell.getCellComment().getAuthor();
      commentText = cell.getCellComment().getString().getString();
    }

    Hyperlink hyperlink = cell.getHyperlink();
    String hyperlinkType = hyperlink == null ? null : hyperlink.getType().name();
    String hyperlinkAddress = hyperlink == null ? null : hyperlink.getAddress();

    return new CellSnapshot(
        cell.getRowIndex(),
        cell.getColumnIndex(),
        cell.getCellType().name(),
        cellValue(cell),
        captureStyle(workbook, cell.getCellStyle()),
        commentAuthor,
        commentText,
        hyperlinkType,
        hyperlinkAddress);
  }

  private static String cellValue(Cell cell) {
    return switch (cell.getCellType()) {
      case BLANK -> "";
      case BOOLEAN -> Boolean.toString(cell.getBooleanCellValue());
      case ERROR -> Byte.toString(cell.getErrorCellValue());
      case FORMULA -> cell.getCellFormula() + "|cached=" + cell.getCachedFormulaResultType();
      case NUMERIC -> Double.toHexString(cell.getNumericCellValue());
      case STRING -> cell.getRichStringCellValue().getString();
      case _NONE -> "";
    };
  }

  private static StyleSnapshot captureStyle(Workbook workbook, CellStyle style) {
    if (style == null) {
      return null;
    }

    Font font = workbook.getFontAt(style.getFontIndexAsInt());
    FontSnapshot fontSnapshot = new FontSnapshot(
        font.getFontName(),
        font.getFontHeight(),
        font.getBold(),
        font.getItalic(),
        font.getStrikeout(),
        font.getColor(),
        font.getTypeOffset(),
        font.getUnderline(),
        font.getCharSet());

    return new StyleSnapshot(
        style.getDataFormatString(),
        style.getAlignment().name(),
        style.getVerticalAlignment().name(),
        style.getFillPattern().name(),
        style.getFillForegroundColor(),
        style.getFillBackgroundColor(),
        style.getBorderTop().name(),
        style.getBorderRight().name(),
        style.getBorderBottom().name(),
        style.getBorderLeft().name(),
        style.getTopBorderColor(),
        style.getRightBorderColor(),
        style.getBottomBorderColor(),
        style.getLeftBorderColor(),
        style.getWrapText(),
        style.getShrinkToFit(),
        style.getHidden(),
        style.getLocked(),
        style.getRotation(),
        style.getIndention(),
        style.getQuotePrefixed(),
        fontSnapshot);
  }

  @FunctionalInterface
  public interface CellSelector {
    CellSelector ALL = (sheetIndex, rowIndex, columnIndex) -> true;

    boolean include(int sheetIndex, int rowIndex, int columnIndex);
  }

  public record NameSnapshot(
      String name, String formula, int sheetIndex, boolean functionName) {}

  public record SheetSnapshot(
      int index,
      String name,
      String visibility,
      int defaultColumnWidth,
      short defaultRowHeight,
      boolean displayGridlines,
      boolean printGridlines,
      boolean fitToPage,
      List<ColumnSnapshot> columns,
      List<RowSnapshot> rows,
      List<String> mergedRegions) {

    public SheetSnapshot {
      columns = List.copyOf(columns);
      rows = List.copyOf(rows);
      mergedRegions = List.copyOf(mergedRegions);
    }
  }

  public record ColumnSnapshot(
      int index, int width, boolean hidden, StyleSnapshot style) {}

  public record RowSnapshot(
      int index,
      short height,
      boolean hidden,
      int outlineLevel,
      List<CellSnapshot> cells) {

    public RowSnapshot {
      cells = List.copyOf(cells);
    }
  }

  public record CellSnapshot(
      int row,
      int column,
      String type,
      String value,
      StyleSnapshot style,
      String commentAuthor,
      String commentText,
      String hyperlinkType,
      String hyperlinkAddress) {}

  public record StyleSnapshot(
      String dataFormat,
      String horizontalAlignment,
      String verticalAlignment,
      String fillPattern,
      short fillForegroundColor,
      short fillBackgroundColor,
      String borderTop,
      String borderRight,
      String borderBottom,
      String borderLeft,
      short borderTopColor,
      short borderRightColor,
      short borderBottomColor,
      short borderLeftColor,
      boolean wrapText,
      boolean shrinkToFit,
      boolean hidden,
      boolean locked,
      short rotation,
      short indentation,
      boolean quotePrefixed,
      FontSnapshot font) {}

  public record FontSnapshot(
      String name,
      short height,
      boolean bold,
      boolean italic,
      boolean strikeout,
      short color,
      short typeOffset,
      byte underline,
      int charset) {}
}
