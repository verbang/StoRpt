package io.storpt.excel;

/** A deterministic template incompatibility that must stop processing. */
public final class TemplateAnalysisException extends Exception {
  private final String code;

  public TemplateAnalysisException(String code, String message) {
    super(message);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
