package io.storpt.excel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.List;

/** One-request JSON transport for the local Java subprocess. */
public final class WorkerCli {
  private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter
      .ofPattern("uuuu.MM.dd")
      .withResolverStyle(ResolverStyle.STRICT);

  private final ObjectMapper mapper;
  private final WorkbookJobRunner runner;

  public WorkerCli() {
    this(new ObjectMapper(), new WorkbookJobRunner());
  }

  WorkerCli(ObjectMapper mapper, WorkbookJobRunner runner) {
    this.mapper = mapper;
    this.runner = runner;
  }

  public int run(InputStream input, OutputStream output) {
    ObjectNode response;
    int exitCode;
    try {
      WorkbookJob job = parse(mapper.readTree(input));
      TemplateMetadata metadata = runner.run(job);
      response = success(job.outputPath(), metadata);
      exitCode = 0;
    } catch (TemplateAnalysisException | WorkbookWriteException exception) {
      response = failure(exception instanceof TemplateAnalysisException analysis
          ? analysis.code() : ((WorkbookWriteException) exception).code(), exception.getMessage());
      exitCode = 1;
    } catch (Exception exception) {
      response = failure(
          exception instanceof IOException ? "OUTPUT-001" : "INPUT-001",
          exception.getMessage() == null ? "Worker 处理失败。" : exception.getMessage());
      exitCode = 1;
    }

    try {
      mapper.writeValue(output, response);
      output.write('\n');
      output.flush();
    } catch (IOException exception) {
      return 1;
    }
    return exitCode;
  }

  private WorkbookJob parse(JsonNode root) throws WorkbookWriteException {
    if (root == null || !root.isObject()) {
      throw error("INPUT-001", "Worker 请求必须是 JSON 对象。");
    }
    String inputPath = requiredText(root, "inputPath");
    String outputPath = requiredText(root, "outputPath");
    String format = requiredText(root, "format");
    int sheetIndex = requiredInteger(root, "sheetIndex");
    JsonNode latest = requiredObject(root, "latestPeriod");
    int titleRow = requiredInteger(latest, "titleRow");
    int dataStartRow = requiredInteger(latest, "dataStartRow");
    JsonNode changes = requiredObject(root, "changes");
    LocalDate startDate = requiredDate(changes, "startDate");
    LocalDate endDate = requiredDate(changes, "endDate");
    boolean fillName = requiredBoolean(changes, "fillName");
    boolean fillIdealBuy = requiredBoolean(changes, "fillIdealBuy");
    boolean fillIdealSell = requiredBoolean(changes, "fillIdealSell");
    JsonNode rowsNode = changes.get("rows");
    if (rowsNode == null || !rowsNode.isArray()) {
      throw error("INPUT-001", "changes.rows 必须是数组。");
    }
    List<WorkbookWriteRequest.StockValues> rows = new ArrayList<>();
    for (JsonNode row : rowsNode) {
      if (!row.isObject()) {
        throw error("INPUT-001", "每个股票数据项必须是对象。");
      }
      rows.add(new WorkbookWriteRequest.StockValues(
          requiredText(row, "code"),
          optionalText(row, "name"),
          optionalDecimal(row, "idealBuy"),
          optionalDecimal(row, "idealSell")));
    }
    WorkbookWriteRequest writeRequest = new WorkbookWriteRequest(
        sheetIndex, titleRow, dataStartRow, startDate, endDate, rows,
        fillName, fillIdealBuy, fillIdealSell);
    return new WorkbookJob(Path.of(inputPath), Path.of(outputPath), format, writeRequest);
  }

  private ObjectNode success(Path outputPath, TemplateMetadata metadata) {
    ObjectNode root = mapper.createObjectNode();
    root.put("status", "success");
    root.put("outputPath", outputPath.toString());
    root.set("metadata", metadata(metadata));
    return root;
  }

  private ObjectNode failure(String code, String detail) {
    ErrorDefinition definition = ErrorDefinition.forCode(code);
    ObjectNode root = mapper.createObjectNode();
    root.put("status", "error");
    ArrayNode errors = root.putArray("errors");
    ObjectNode error = errors.addObject();
    error.put("code", code);
    error.put("category", definition.category());
    error.put("stage", definition.stage());
    error.put("title", definition.title());
    error.put("message", detail);
    return root;
  }

  private ObjectNode metadata(TemplateMetadata metadata) {
    ObjectNode node = mapper.createObjectNode();
    node.put("sheetIndex", metadata.sheetIndex());
    node.put("sheetName", metadata.sheetName());
    ArrayNode periods = node.putArray("periods");
    for (TemplateMetadata.PeriodBlock period : metadata.periods()) {
      periods.add(period(period));
    }
    node.set("latestPeriod", period(metadata.latestPeriod()));
    return node;
  }

  private ObjectNode period(TemplateMetadata.PeriodBlock period) {
    ObjectNode node = mapper.createObjectNode();
    node.put("startDate", DATE_FORMAT.format(period.startDate()));
    node.put("endDate", DATE_FORMAT.format(period.endDate()));
    node.put("titleRow", period.titleRow());
    node.put("headerRow", period.headerRow());
    node.put("dataStartRow", period.dataStartRow());
    node.put("dataEndRow", period.dataEndRow());
    return node;
  }

  private static JsonNode requiredObject(JsonNode node, String field)
      throws WorkbookWriteException {
    JsonNode value = node.get(field);
    if (value == null || !value.isObject()) {
      throw error("INPUT-001", field + " 必须是对象。");
    }
    return value;
  }

  private static String requiredText(JsonNode node, String field)
      throws WorkbookWriteException {
    String value = optionalText(node, field);
    if (value == null || value.isBlank()) {
      throw error("INPUT-001", field + " 必须是非空字符串。");
    }
    return value;
  }

  private static String optionalText(JsonNode node, String field)
      throws WorkbookWriteException {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isTextual()) {
      throw error("INPUT-001", field + " 必须是字符串。");
    }
    return value.textValue();
  }

  private static int requiredInteger(JsonNode node, String field)
      throws WorkbookWriteException {
    JsonNode value = node.get(field);
    if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()
        || value.intValue() < 0) {
      throw error("INPUT-001", field + " 必须是非负整数。");
    }
    return value.intValue();
  }

  private static boolean requiredBoolean(JsonNode node, String field)
      throws WorkbookWriteException {
    JsonNode value = node.get(field);
    if (value == null || !value.isBoolean()) {
      throw error("INPUT-001", field + " 必须是布尔值。");
    }
    return value.booleanValue();
  }

  private static BigDecimal optionalDecimal(JsonNode node, String field)
      throws WorkbookWriteException {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isNumber()) {
      throw error("INPUT-001", field + " 必须是数值。");
    }
    return value.decimalValue();
  }

  private static LocalDate requiredDate(JsonNode node, String field)
      throws WorkbookWriteException {
    String value = requiredText(node, field);
    try {
      return LocalDate.parse(value, DATE_FORMAT);
    } catch (DateTimeParseException exception) {
      throw error("INPUT-001", field + " 必须是有效的 yyyy.MM.dd 日期。");
    }
  }

  private static WorkbookWriteException error(String code, String message) {
    return new WorkbookWriteException(code, message);
  }

  private record ErrorDefinition(String category, String stage, String title) {
    private static ErrorDefinition forCode(String code) {
      return switch (code) {
        case "FILE-001" -> new ErrorDefinition("FILE", "upload", "不支持的文件格式");
        case "FILE-002" -> new ErrorDefinition("FILE", "upload", "文件超过大小限制");
        case "TEMPLATE-001" -> new ErrorDefinition("TEMPLATE", "template", "未找到唯一目标工作表");
        case "TEMPLATE-002" -> new ErrorDefinition("TEMPLATE", "template", "时间段标题无效");
        case "TEMPLATE-003" -> new ErrorDefinition("TEMPLATE", "template", "时间段顺序无效");
        case "TEMPLATE-004" -> new ErrorDefinition("TEMPLATE", "template", "最新数据区不兼容");
        case "TEMPLATE-005" -> new ErrorDefinition("TEMPLATE", "template", "工作簿包含不支持功能");
        case "INPUT-001" -> new ErrorDefinition("INPUT", "input", "股票代码输入无效");
        case "OUTPUT-001" -> new ErrorDefinition("OUTPUT", "output", "工作簿写入失败");
        default -> new ErrorDefinition("SYSTEM", "system", "Worker 处理失败");
      };
    }
  }
}
