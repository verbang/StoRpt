package io.storpt.excel;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Validated market data and the fixed coordinates authorized for one write. */
public record WorkbookWriteRequest(
    int sheetIndex,
    int titleRow,
    int dataStartRow,
    LocalDate startDate,
    LocalDate endDate,
    List<StockValues> rows,
    boolean fillName,
    boolean fillIdealBuy,
    boolean fillIdealSell) {

  public WorkbookWriteRequest {
    rows = rows == null ? List.of() : List.copyOf(rows);
  }

  public record StockValues(
      String code,
      String name,
      BigDecimal idealBuy,
      BigDecimal idealSell) {}
}
