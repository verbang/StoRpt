package io.storpt.excel;

/** A deterministic write request or output failure. */
public final class WorkbookWriteException extends Exception {
  private final String code;

  public WorkbookWriteException(String code, String message) {
    super(message);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
