package io.storpt.excel;

import java.time.LocalDate;
import java.util.List;

/** Structural metadata passed from the Excel Worker to the orchestrator. */
public record TemplateMetadata(
    int sheetIndex,
    String sheetName,
    List<PeriodBlock> periods,
    PeriodBlock latestPeriod) {

  public TemplateMetadata {
    periods = List.copyOf(periods);
  }

  public record PeriodBlock(
      LocalDate startDate,
      LocalDate endDate,
      int titleRow,
      int headerRow,
      int dataStartRow,
      int dataEndRow) {}
}
