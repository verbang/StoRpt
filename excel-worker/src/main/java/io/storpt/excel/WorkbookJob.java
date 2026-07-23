package io.storpt.excel;

import java.nio.file.Path;

/** Fully parsed file operation passed from the JSON transport to the workbook layer. */
public record WorkbookJob(
    Path inputPath,
    Path outputPath,
    String format,
    WorkbookWriteRequest writeRequest) {}
