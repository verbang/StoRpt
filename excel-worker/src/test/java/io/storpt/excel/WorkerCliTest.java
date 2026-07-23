package io.storpt.excel;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkerCliTest {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @TempDir
  Path temporaryDirectory;

  @Test
  void publishesVerifiedXlsxAndReturnsMetadata() throws Exception {
    Path output = temporaryDirectory.resolve("result.xlsx");

    CliResult result = runCli(request(template(), output, "xlsx", 0, 27, 29));

    assertEquals(0, result.exitCode());
    assertEquals("success", result.response().path("status").asText());
    assertEquals(output.toString(), result.response().path("outputPath").asText());
    assertEquals(36, result.response().path("metadata").path("latestPeriod")
        .path("dataEndRow").asInt());
    assertTrue(Files.isRegularFile(output));
    try (Workbook workbook = WorkbookFactory.create(output.toFile())) {
      assertInstanceOf(XSSFWorkbook.class, workbook);
      Sheet sheet = workbook.getSheetAt(0);
      assertEquals("000008", sheet.getRow(36).getCell(0).getStringCellValue());
      assertEquals(18.25d, sheet.getRow(36).getCell(2).getNumericCellValue());
    }
  }

  @Test
  void preservesXlsContainerFormat() throws Exception {
    Path input = temporaryDirectory.resolve("input.xls");
    Path output = temporaryDirectory.resolve("result.xls");
    createCompatibleXls(input);

    CliResult result = runCli(request(input, output, "xls", 0, 0, 2));

    assertEquals(0, result.exitCode());
    assertTrue(Files.isRegularFile(output));
    try (Workbook workbook = WorkbookFactory.create(output.toFile())) {
      assertInstanceOf(HSSFWorkbook.class, workbook);
      assertEquals("000008", workbook.getSheetAt(0).getRow(9).getCell(0).getStringCellValue());
    }
  }

  @Test
  void neverOverwritesAnExistingOutput() throws Exception {
    Path output = temporaryDirectory.resolve("existing.xlsx");
    byte[] original = "keep-me".getBytes(StandardCharsets.UTF_8);
    Files.write(output, original);

    CliResult result = runCli(request(template(), output, "xlsx", 0, 27, 29));

    assertEquals(1, result.exitCode());
    assertEquals("error", result.response().path("status").asText());
    assertEquals("OUTPUT-001", result.response().path("errors").get(0).path("code").asText());
    assertFalse(result.response().has("outputPath"));
    assertArrayEquals(original, Files.readAllBytes(output));
  }

  @Test
  void failedSelfCheckPublishesNothingAndDeletesTemporaryFile() throws Exception {
    Path output = temporaryDirectory.resolve("failed.xlsx");
    WorkbookJobRunner runner = new WorkbookJobRunner(
        new TemplateAnalyzer(),
        new WorkbookWriter(),
        (path, plan) -> {
          throw new WorkbookWriteException("OUTPUT-001", "forced verification failure");
        });
    WorkbookJob job = typedJob(template(), output);

    WorkbookWriteException exception = assertThrows(
        WorkbookWriteException.class,
        () -> runner.run(job));

    assertEquals("OUTPUT-001", exception.code());
    assertFalse(Files.exists(output));
    try (var files = Files.list(temporaryDirectory)) {
      assertEquals(0, files.filter(path -> path.getFileName().toString().startsWith(".storpt-"))
          .count());
    }
  }

  @Test
  void malformedRequestReturnsStructuredErrorWithoutOutputPath() throws Exception {
    ObjectNode invalid = MAPPER.createObjectNode();
    invalid.put("inputPath", template().toString());

    CliResult result = runCli(invalid);

    assertEquals(1, result.exitCode());
    assertEquals("INPUT-001", result.response().path("errors").get(0).path("code").asText());
    assertFalse(result.response().has("outputPath"));
  }

  private static CliResult runCli(JsonNode request) throws Exception {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    int exitCode = new WorkerCli().run(
        new ByteArrayInputStream(MAPPER.writeValueAsBytes(request)), output);
    return new CliResult(exitCode, MAPPER.readTree(output.toByteArray()));
  }

  private static ObjectNode request(
      Path input,
      Path output,
      String format,
      int sheetIndex,
      int titleRow,
      int dataStartRow) {
    ObjectNode root = MAPPER.createObjectNode();
    root.put("inputPath", input.toString());
    root.put("outputPath", output.toString());
    root.put("format", format);
    root.put("sheetIndex", sheetIndex);
    ObjectNode latest = root.putObject("latestPeriod");
    latest.put("titleRow", titleRow);
    latest.put("dataStartRow", dataStartRow);
    ObjectNode changes = root.putObject("changes");
    changes.put("startDate", "2026.02.02");
    changes.put("endDate", "2026.02.06");
    changes.put("fillName", true);
    changes.put("fillIdealBuy", true);
    changes.put("fillIdealSell", true);
    ArrayNode rows = changes.putArray("rows");
    for (int index = 1; index <= 8; index++) {
      ObjectNode row = rows.addObject();
      row.put("code", String.format("%06d", index));
      row.put("name", "股票" + index);
      row.put("idealBuy", 10.25d + index);
      row.put("idealSell", 11.50d + index);
    }
    return root;
  }

  private static WorkbookJob typedJob(Path input, Path output) {
    List<WorkbookWriteRequest.StockValues> rows = List.of(
        new WorkbookWriteRequest.StockValues(
            "000001", "股票1", BigDecimal.valueOf(11.25d), BigDecimal.valueOf(12.50d)));
    return new WorkbookJob(
        input,
        output,
        "xlsx",
        new WorkbookWriteRequest(
            0, 27, 29,
            LocalDate.of(2026, 2, 2),
            LocalDate.of(2026, 2, 6),
            rows, true, true, true));
  }

  private static Path template() {
    return Path.of(System.getProperty("storpt.template", "../platform.xlsx"))
        .toAbsolutePath()
        .normalize();
  }

  private static void createCompatibleXls(Path output) throws Exception {
    try (Workbook workbook = new HSSFWorkbook()) {
      Sheet sheet = workbook.createSheet("Sheet1");
      Row title = sheet.createRow(0);
      title.createCell(0).setCellValue("2026.01.26 - 2026.01.30");
      sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 18));
      Row header = sheet.createRow(1);
      header.createCell(0).setCellValue("股票代码");
      header.createCell(1).setCellValue("股票名称");
      header.createCell(2).setCellValue("理想进价");
      header.createCell(3).setCellValue("理想出价");
      for (int index = 0; index < 6; index++) {
        Row row = sheet.createRow(index + 2);
        row.createCell(0, CellType.STRING).setCellValue(String.format("%06d", index + 1));
        row.createCell(1, CellType.STRING).setCellValue("旧股票" + (index + 1));
        row.createCell(2, CellType.NUMERIC).setCellValue(index + 1.25d);
        row.createCell(3, CellType.NUMERIC).setCellValue(index + 2.50d);
      }
      try (OutputStream stream = Files.newOutputStream(output)) {
        workbook.write(stream);
      }
    }
  }

  private record CliResult(int exitCode, JsonNode response) {}
}
