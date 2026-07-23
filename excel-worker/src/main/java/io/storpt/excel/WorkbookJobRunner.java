package io.storpt.excel;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/** Saves to a temporary sibling, verifies the reopened workbook, then publishes atomically. */
public final class WorkbookJobRunner {
  private static final long MAX_FILE_SIZE = 10L * 1024L * 1024L;
  private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("uuuu.MM.dd");

  private final TemplateAnalyzer analyzer;
  private final WorkbookWriter writer;
  private final OutputVerifier verifier;

  public WorkbookJobRunner() {
    this(new TemplateAnalyzer(), new WorkbookWriter(), WorkbookJobRunner::verifyOutput);
  }

  WorkbookJobRunner(TemplateAnalyzer analyzer, WorkbookWriter writer, OutputVerifier verifier) {
    this.analyzer = analyzer;
    this.writer = writer;
    this.verifier = verifier;
  }

  public TemplateMetadata run(WorkbookJob job)
      throws IOException, TemplateAnalysisException, WorkbookWriteException {
    validateFiles(job);
    Path output = job.outputPath().toAbsolutePath().normalize();
    Path temporary = null;
    try {
      temporary = Files.createTempFile(output.getParent(), ".storpt-", "." + job.format());
      VerificationPlan plan;
      try (Workbook workbook = WorkbookFactory.create(job.inputPath().toFile())) {
        validateWorkbookFormat(workbook, job.format());
        TemplateMetadata metadata = analyzer.analyze(workbook);
        int newEnd = job.writeRequest().dataStartRow() + job.writeRequest().rows().size() - 1;
        int maximumEnd = Math.max(metadata.latestPeriod().dataEndRow(), newEnd);
        WorkbookSnapshot.CellSelector protectedCells = protectedCells(
            metadata.sheetIndex(),
            metadata.latestPeriod().titleRow(),
            metadata.latestPeriod().dataStartRow(),
            maximumEnd);
        WorkbookSnapshot protectedBefore = WorkbookSnapshot.capture(workbook, protectedCells);
        List<CellValue> unselectedBefore = captureUnselected(
            workbook, job.writeRequest(), maximumEnd);

        WorkbookWriter.WriteSummary summary = writer.write(workbook, job.writeRequest());
        plan = new VerificationPlan(
            job.format(), job.writeRequest(), summary, protectedCells, protectedBefore, unselectedBefore);
        try (OutputStream stream = Files.newOutputStream(temporary)) {
          workbook.write(stream);
        }
      }

      TemplateMetadata verified = verifier.verify(temporary, plan);
      publish(temporary, output);
      temporary = null;
      return verified;
    } finally {
      if (temporary != null) {
        Files.deleteIfExists(temporary);
      }
    }
  }

  private static void validateFiles(WorkbookJob job) throws IOException, WorkbookWriteException {
    if (job == null || job.inputPath() == null || job.outputPath() == null
        || job.writeRequest() == null) {
      throw error("INPUT-001", "Worker 请求缺少必要字段。");
    }
    Path input = job.inputPath().toAbsolutePath().normalize();
    Path output = job.outputPath().toAbsolutePath().normalize();
    if (!Files.isRegularFile(input)) {
      throw error("FILE-001", "输入文件不存在或不是普通文件。");
    }
    if (Files.size(input) > MAX_FILE_SIZE) {
      throw error("FILE-002", "输入文件超过 10 MB 限制。");
    }
    if (input.equals(output) || Files.exists(output)) {
      throw error("OUTPUT-001", "输出路径不得覆盖输入文件或已有文件。");
    }
    if (output.getParent() == null || !Files.isDirectory(output.getParent())) {
      throw error("OUTPUT-001", "输出目录不存在。");
    }
    String format = job.format() == null ? "" : job.format().toLowerCase(Locale.ROOT);
    if (!format.equals(job.format())
        || !(format.equals("xls") || format.equals("xlsx"))
        || !extension(input).equals(format)
        || !extension(output).equals(format)) {
      throw error("FILE-001", "输入、输出扩展名和 format 必须一致，且只能是 xls 或 xlsx。");
    }
  }

  private static String extension(Path path) {
    String name = path.getFileName().toString();
    int separator = name.lastIndexOf('.');
    return separator < 0 ? "" : name.substring(separator + 1).toLowerCase(Locale.ROOT);
  }

  private static void validateWorkbookFormat(Workbook workbook, String format)
      throws WorkbookWriteException {
    boolean valid = format.equals("xls")
        ? workbook instanceof HSSFWorkbook
        : workbook instanceof XSSFWorkbook;
    if (!valid) {
      throw error("FILE-001", "文件内容与声明的 Excel 格式不一致。");
    }
  }

  private static void publish(Path temporary, Path output) throws IOException {
    try {
      Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException exception) {
      Files.move(temporary, output);
    }
  }

  private static TemplateMetadata verifyOutput(Path output, VerificationPlan plan)
      throws IOException, TemplateAnalysisException, WorkbookWriteException {
    try (Workbook workbook = WorkbookFactory.create(output.toFile())) {
      validateWorkbookFormat(workbook, plan.format());
      if (!plan.protectedBefore().equals(
          WorkbookSnapshot.capture(workbook, plan.protectedCells()))) {
        throw error("OUTPUT-001", "保存后受保护工作簿内容发生变化。");
      }
      int maximumEnd = Math.max(
          plan.summary().oldDataEndRow(), plan.summary().newDataEndRow());
      if (!plan.unselectedBefore().equals(
          captureUnselected(workbook, plan.request(), maximumEnd))) {
        throw error("OUTPUT-001", "保存后未勾选字段发生变化。");
      }
      verifyWrittenValues(workbook, plan.request(), plan.summary());
      return new TemplateAnalyzer().analyze(workbook);
    }
  }

  private static void verifyWrittenValues(
      Workbook workbook,
      WorkbookWriteRequest request,
      WorkbookWriter.WriteSummary summary) throws WorkbookWriteException {
    Sheet sheet = workbook.getSheetAt(request.sheetIndex());
    String expectedTitle = DATE_FORMAT.format(request.startDate())
        + " - " + DATE_FORMAT.format(request.endDate());
    Cell title = cell(sheet, request.titleRow(), 0);
    if (title == null || title.getCellType() != CellType.STRING
        || !expectedTitle.equals(title.getStringCellValue())) {
      throw error("OUTPUT-001", "保存后的日期标题不正确。");
    }

    for (int index = 0; index < request.rows().size(); index++) {
      int rowIndex = request.dataStartRow() + index;
      WorkbookWriteRequest.StockValues values = request.rows().get(index);
      verifyString(cell(sheet, rowIndex, 0), values.code(), "股票代码");
      if (request.fillName()) {
        verifyString(cell(sheet, rowIndex, 1), values.name(), "股票名称");
      }
      if (request.fillIdealBuy()) {
        verifyNumber(cell(sheet, rowIndex, 2), values.idealBuy(), "理想进价");
      }
      if (request.fillIdealSell()) {
        verifyNumber(cell(sheet, rowIndex, 3), values.idealSell(), "理想出价");
      }
    }
    for (int rowIndex = summary.newDataEndRow() + 1;
         rowIndex <= summary.oldDataEndRow(); rowIndex++) {
      Cell code = cell(sheet, rowIndex, 0);
      if (code != null && code.getCellType() != CellType.BLANK
          && !(code.getCellType() == CellType.STRING && code.getStringCellValue().isBlank())) {
        throw error("OUTPUT-001", "保存后的 A 列旧数据尾部未清空。");
      }
    }
  }

  private static void verifyString(Cell cell, String expected, String label)
      throws WorkbookWriteException {
    if (cell == null || cell.getCellType() != CellType.STRING
        || !expected.equals(cell.getStringCellValue())) {
      throw error("OUTPUT-001", "保存后的" + label + "不正确。");
    }
  }

  private static void verifyNumber(Cell cell, BigDecimal expected, String label)
      throws WorkbookWriteException {
    if (cell == null || cell.getCellType() != CellType.NUMERIC
        || Double.compare(expected.doubleValue(), cell.getNumericCellValue()) != 0) {
      throw error("OUTPUT-001", "保存后的" + label + "不正确。");
    }
  }

  private static List<CellValue> captureUnselected(
      Workbook workbook, WorkbookWriteRequest request, int maximumEnd) {
    Sheet sheet = workbook.getSheetAt(request.sheetIndex());
    List<CellValue> values = new ArrayList<>();
    for (int rowIndex = request.dataStartRow(); rowIndex <= maximumEnd; rowIndex++) {
      if (!request.fillName()) {
        values.add(captureValue(cell(sheet, rowIndex, 1)));
      }
      if (!request.fillIdealBuy()) {
        values.add(captureValue(cell(sheet, rowIndex, 2)));
      }
      if (!request.fillIdealSell()) {
        values.add(captureValue(cell(sheet, rowIndex, 3)));
      }
    }
    return List.copyOf(values);
  }

  private static Cell cell(Sheet sheet, int rowIndex, int columnIndex) {
    Row row = sheet.getRow(rowIndex);
    return row == null ? null : row.getCell(columnIndex);
  }

  private static CellValue captureValue(Cell cell) {
    if (cell == null) {
      return new CellValue("MISSING", "");
    }
    return new CellValue(cell.getCellType().name(), switch (cell.getCellType()) {
      case BLANK, _NONE -> "";
      case BOOLEAN -> Boolean.toString(cell.getBooleanCellValue());
      case ERROR -> Byte.toString(cell.getErrorCellValue());
      case FORMULA -> cell.getCellFormula();
      case NUMERIC -> Double.toHexString(cell.getNumericCellValue());
      case STRING -> cell.getStringCellValue();
    });
  }

  private static WorkbookSnapshot.CellSelector protectedCells(
      int targetSheet, int titleRow, int dataStartRow, int dataEndRow) {
    return (sheetIndex, rowIndex, columnIndex) -> {
      if (sheetIndex != targetSheet) {
        return true;
      }
      boolean titleValue = rowIndex == titleRow && columnIndex == 0;
      boolean writableData = rowIndex >= dataStartRow
          && rowIndex <= dataEndRow
          && columnIndex >= 0
          && columnIndex <= 3;
      return !titleValue && !writableData;
    };
  }

  private static WorkbookWriteException error(String code, String message) {
    return new WorkbookWriteException(code, message);
  }

  @FunctionalInterface
  interface OutputVerifier {
    TemplateMetadata verify(Path output, VerificationPlan plan)
        throws IOException, TemplateAnalysisException, WorkbookWriteException;
  }

  record VerificationPlan(
      String format,
      WorkbookWriteRequest request,
      WorkbookWriter.WriteSummary summary,
      WorkbookSnapshot.CellSelector protectedCells,
      WorkbookSnapshot protectedBefore,
      List<CellValue> unselectedBefore) {}

  record CellValue(String type, String value) {}
}
